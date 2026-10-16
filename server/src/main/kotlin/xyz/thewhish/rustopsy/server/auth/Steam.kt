package xyz.thewhish.rustopsy.server.auth

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import xyz.thewhish.rustopsy.core.sync.FriendActivity
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class SteamProfile(val name: String, val avatarUrl: String?, val activity: FriendActivity = FriendActivity.OFFLINE)

sealed interface FriendList {
    class Visible(val steamIds: List<Long>) : FriendList
    data object Private : FriendList
}

class Steam(
    val verifyAssertion: suspend (Map<String, String>) -> Boolean,
    val profiles: suspend (Collection<Long>) -> Map<Long, SteamProfile>,
    val friends: suspend (Long) -> FriendList,
    val resolveVanity: suspend (String) -> Long?,
)

object SteamOpenId {
    const val ENDPOINT = "https://steamcommunity.com/openid/login"
    private const val NAMESPACE = "http://specs.openid.net/auth/2.0"
    private const val IDENTIFIER_SELECT = "$NAMESPACE/identifier_select"
    private val CLAIMED_ID = Regex("https://steamcommunity\\.com/openid/id/(\\d{17})")

    fun loginUrl(returnTo: String, realm: String): String = ENDPOINT + "?" + formEncode(
        mapOf(
            "openid.ns" to NAMESPACE,
            "openid.mode" to "checkid_setup",
            "openid.return_to" to returnTo,
            "openid.realm" to realm,
            "openid.identity" to IDENTIFIER_SELECT,
            "openid.claimed_id" to IDENTIFIER_SELECT,
        ),
    )

    fun claimedSteamId(params: Map<String, String>, expectedReturnTo: String): Long? {
        if (params["openid.mode"] != "id_res") return null
        if (params["openid.op_endpoint"] != ENDPOINT) return null
        if (params["openid.return_to"] != expectedReturnTo) return null
        val claimedId = params["openid.claimed_id"] ?: return null
        if (params["openid.identity"] != claimedId) return null
        return CLAIMED_ID.matchEntire(claimedId)?.groupValues?.get(1)?.toLongOrNull()
    }
}

fun formEncode(params: Map<String, String>): String = params.entries.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
}

private const val API = "https://api.steampowered.com/ISteamUser"
private const val RUST_APP_ID = "252490"
private const val MAX_IDS_PER_REQUEST = 100
private const val MAX_NAME_LENGTH = 64
private const val MAX_URL_LENGTH = 512
private const val STEAM_PRIVATE = 401
private val TIMEOUT: Duration = Duration.ofSeconds(5)
private val json = Json { ignoreUnknownKeys = true }

fun steamWeb(apiKey: String?, http: HttpClient): Steam {
    suspend fun get(method: String, params: Map<String, String>): HttpResponse<String> {
        val key = apiKey ?: throw IOException("STEAM_API_KEY is not configured")
        val uri = URI("$API/$method/?${formEncode(params + ("key" to key))}")
        return http.sendAsync(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString()).await()
    }

    suspend fun body(method: String, params: Map<String, String>): JsonObject {
        val response = get(method, params)
        if (response.statusCode() != 200) throw IOException("Steam $method returned ${response.statusCode()}")
        return parse(response.body())
    }

    return Steam(
        verifyAssertion = { params -> checkAuthentication(params, http) },
        profiles = { steamIds ->
            steamIds.distinct().chunked(MAX_IDS_PER_REQUEST).flatMap { chunk ->
                parsePlayers(body("GetPlayerSummaries/v2", mapOf("steamids" to chunk.joinToString(","))))
            }.toMap()
        },
        friends = { steamId ->
            val response = get("GetFriendList/v1", mapOf("steamid" to steamId.toString(), "relationship" to "friend"))
            when (response.statusCode()) {
                200 -> FriendList.Visible(parseFriends(parse(response.body())))
                STEAM_PRIVATE -> FriendList.Private
                else -> throw IOException("Steam GetFriendList returned ${response.statusCode()}")
            }
        },
        resolveVanity = { name -> parseVanity(body("ResolveVanityURL/v1", mapOf("vanityurl" to name))) },
    )
}

private suspend fun checkAuthentication(params: Map<String, String>, http: HttpClient): Boolean {
    val form = formEncode(params.filterKeys { it.startsWith("openid.") } + ("openid.mode" to "check_authentication"))
    val request = HttpRequest.newBuilder(URI(SteamOpenId.ENDPOINT))
        .timeout(TIMEOUT)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()
    val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    if (response.statusCode() != 200) throw IOException("Steam check_authentication returned ${response.statusCode()}")
    return response.body().lineSequence().any { it.trim() == "is_valid:true" }
}

private fun parse(body: String): JsonObject = try {
    json.parseToJsonElement(body) as? JsonObject ?: throw IOException("Steam response is not an object")
} catch (e: IllegalArgumentException) {
    throw IOException("Steam response is malformed", e)
}

internal fun parsePlayers(body: JsonObject): List<Pair<Long, SteamProfile>> =
    ((body["response"] as? JsonObject)?.get("players") as? JsonArray).orEmpty().mapNotNull { element ->
        val player = element as? JsonObject ?: return@mapNotNull null
        val steamId = player.text("steamid")?.toLongOrNull() ?: return@mapNotNull null
        val name = player.text("personaname")?.trim()?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val avatar = player.text("avatarfull")?.takeIf { it.startsWith("https://") && it.length <= MAX_URL_LENGTH }
        val activity = when {
            player.text("gameid") == RUST_APP_ID -> FriendActivity.PLAYING_RUST
            ((player["personastate"] as? JsonPrimitive)?.intOrNull ?: 0) > 0 -> FriendActivity.ONLINE
            else -> FriendActivity.OFFLINE
        }
        steamId to SteamProfile(name, avatar, activity)
    }

internal fun parseFriends(body: JsonObject): List<Long> =
    ((body["friendslist"] as? JsonObject)?.get("friends") as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.text("steamid")?.toLongOrNull() }

internal fun parseVanity(body: JsonObject): Long? {
    val response = body["response"] as? JsonObject ?: return null
    if ((response["success"] as? JsonPrimitive)?.intOrNull != 1) return null
    return response.text("steamid")?.toLongOrNull()
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
