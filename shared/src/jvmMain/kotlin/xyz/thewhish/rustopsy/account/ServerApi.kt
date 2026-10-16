package xyz.thewhish.rustopsy.account

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import xyz.thewhish.rustopsy.core.sync.ApiError
import xyz.thewhish.rustopsy.core.sync.FriendsResponse
import xyz.thewhish.rustopsy.core.sync.InviteAnswer
import xyz.thewhish.rustopsy.core.sync.InviteRequest
import xyz.thewhish.rustopsy.core.sync.KickRequest
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_HEADER
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_VERSION
import xyz.thewhish.rustopsy.core.sync.SessionResponse
import xyz.thewhish.rustopsy.core.sync.TeamState
import xyz.thewhish.rustopsy.core.sync.TokenRequest
import xyz.thewhish.rustopsy.core.sync.UserProfile
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture

class ApiException(val status: Int, val error: String? = null) : IOException("Server responded with $status${error?.let { " ($it)" }.orEmpty()}") {
    val unauthorized get() = status == UNAUTHORIZED
    val updateRequired get() = status == UPDATE_REQUIRED

    companion object {
        const val UNAUTHORIZED = 401
        const val UPDATE_REQUIRED = 426
    }
}

class ServerApi(private val baseUrl: String, private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exchange(code: String, verifier: String): SessionResponse =
        decode(send(request("/auth/token").post(json.encodeToString(TokenRequest(code, verifier)))))

    suspend fun me(token: String): UserProfile = decode(send(request("/me", token).GET()))

    suspend fun logout(token: String, everywhere: Boolean) {
        send(request(if (everywhere) "/auth/logout-all" else "/auth/logout", token).post())
    }

    suspend fun deleteAccount(token: String) {
        send(request("/me", token).DELETE())
    }

    suspend fun friends(token: String): FriendsResponse = decode(send(request("/friends", token).GET()))

    suspend fun invite(token: String, target: String): TeamState =
        decode(send(request("/team/invite", token).post(json.encodeToString(InviteRequest(target)))))

    suspend fun acceptInvite(token: String, teamId: Long): TeamState =
        decode(send(request("/team/invites/accept", token).post(json.encodeToString(InviteAnswer(teamId)))))

    suspend fun declineInvite(token: String, teamId: Long): TeamState =
        decode(send(request("/team/invites/decline", token).post(json.encodeToString(InviteAnswer(teamId)))))

    suspend fun leaveTeam(token: String): TeamState = decode(send(request("/team/leave", token).post()))

    suspend fun kick(token: String, steamId: Long): TeamState =
        decode(send(request("/team/kick", token).post(json.encodeToString(KickRequest(steamId)))))

    fun openLive(token: String, listener: WebSocket.Listener): CompletableFuture<WebSocket> =
        http.newWebSocketBuilder()
            .connectTimeout(TIMEOUT)
            .header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString())
            .header("Authorization", "Bearer $token")
            .buildAsync(URI(baseUrl.replaceFirst("http", "ws") + "/team/live"), listener)

    private fun request(path: String, token: String? = null): HttpRequest.Builder =
        HttpRequest.newBuilder(URI("$baseUrl$path"))
            .timeout(TIMEOUT)
            .header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString())
            .header("Content-Type", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }

    private fun HttpRequest.Builder.post(body: String? = null): HttpRequest.Builder =
        POST(if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))

    private suspend fun send(builder: HttpRequest.Builder): String {
        val response = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) throw ApiException(response.statusCode(), errorCode(response.body()))
        return response.body()
    }

    private fun errorCode(body: String): String? = try {
        json.decodeFromString<ApiError>(body).error
    } catch (_: IllegalArgumentException) {
        null
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: IllegalArgumentException) {
        throw IOException("Malformed server response", e)
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
