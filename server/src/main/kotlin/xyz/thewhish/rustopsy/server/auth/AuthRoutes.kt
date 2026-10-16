package xyz.thewhish.rustopsy.server.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import xyz.thewhish.rustopsy.core.sync.ApiError
import xyz.thewhish.rustopsy.core.sync.ApiErrors
import xyz.thewhish.rustopsy.core.sync.LoginErrors
import xyz.thewhish.rustopsy.core.sync.SessionResponse
import xyz.thewhish.rustopsy.core.sync.TokenRequest
import xyz.thewhish.rustopsy.core.sync.Tokens
import java.io.IOException

const val SESSION_AUTH = "session"
private val STATE = Regex("[A-Za-z0-9_-]{16,128}")
private val LOOPBACK_PORTS = 1024..65535

class Session(val steamId: Long, val tokenHash: ByteArray)

fun AuthenticationConfig.sessions(store: AuthStore) {
    bearer(SESSION_AUTH) {
        authenticate { credential ->
            if (!Tokens.isValid(credential.token)) return@authenticate null
            val hash = Tokens.sha256(credential.token)
            store.sessionOwner(hash)?.let { Session(it, hash) }
        }
    }
}

fun Route.loginRoutes(store: AuthStore, steam: Steam, publicUrl: String) {
    get("/auth/steam/start") {
        val port = call.parameters["port"]?.toIntOrNull()?.takeIf { it in LOOPBACK_PORTS }
        val state = call.parameters["state"]?.takeIf(STATE::matches)
        val challenge = call.parameters["challenge"]?.takeIf(Tokens::isValid)
        if (port == null || state == null || challenge == null) {
            call.respondText("Некорректная ссылка входа. Начни вход заново в Rustopsy.", status = HttpStatusCode.BadRequest)
            return@get
        }
        val id = Tokens.random()
        store.createLogin(id, state, challenge, port)
        call.respondRedirect(SteamOpenId.loginUrl(returnTo(publicUrl, id), publicUrl))
    }

    get("/auth/steam/callback") {
        val id = call.parameters["login"]?.takeIf(Tokens::isValid)
        val login = id?.let { store.pendingLogin(it) }
        if (id == null || login == null) {
            call.respondText("Ссылка входа устарела. Начни вход заново в Rustopsy.", status = HttpStatusCode.BadRequest)
            return@get
        }
        val redirect = when (val result = call.verifiedSteamId(steam, returnTo(publicUrl, id))) {
            is LoginResult.Failed -> {
                store.deleteLogin(id)
                loopback(login, "error" to result.error)
            }
            is LoginResult.Verified -> {
                store.saveUser(result.steamId, call.steamProfile(steam, result.steamId))
                val code = Tokens.random()
                if (store.completeLogin(id, result.steamId, Tokens.sha256(code))) {
                    loopback(login, "code" to code)
                } else {
                    loopback(login, "error" to LoginErrors.EXPIRED)
                }
            }
        }
        call.respondRedirect(redirect)
    }
}

fun Route.accountRoutes(store: AuthStore, beforeDelete: suspend (Long) -> Unit) {
    get("/me") {
        val profile = store.user(call.session().steamId)
        if (profile == null) call.respond(HttpStatusCode.Unauthorized) else call.respond(profile)
    }
    delete("/me") {
        val steamId = call.session().steamId
        beforeDelete(steamId)
        store.deleteUser(steamId)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/auth/logout") {
        store.deleteSession(call.session().tokenHash)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/auth/logout-all") {
        store.deleteSessions(call.session().steamId)
        call.respond(HttpStatusCode.NoContent)
    }
}

fun Route.tokenRoute(store: AuthStore) {
    post {
        val request = call.receive<TokenRequest>()
        val redeemed = if (Tokens.isValid(request.code)) store.redeemCode(Tokens.sha256(request.code)) else null
        val profile = redeemed
            ?.takeIf { Tokens.isValid(request.verifier) && Tokens.matchesChallenge(request.verifier, it.codeChallenge) }
            ?.let { store.user(it.steamId) }
        if (profile == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError(ApiErrors.INVALID_CODE))
            return@post
        }
        val token = Tokens.random()
        store.createSession(Tokens.sha256(token), profile.steamId)
        call.respond(SessionResponse(token, profile))
    }
}

private sealed interface LoginResult {
    class Verified(val steamId: Long) : LoginResult
    class Failed(val error: String) : LoginResult
}

private suspend fun ApplicationCall.verifiedSteamId(steam: Steam, expectedReturnTo: String): LoginResult {
    val raw = request.queryParameters
    if (raw["openid.mode"] == "cancel") return LoginResult.Failed(LoginErrors.CANCELLED)
    if (raw.entries().any { it.value.size != 1 }) return LoginResult.Failed(LoginErrors.REJECTED)
    val params = raw.entries().associate { it.key to it.value.single() }
    val steamId = SteamOpenId.claimedSteamId(params, expectedReturnTo) ?: return LoginResult.Failed(LoginErrors.REJECTED)
    val valid = try {
        steam.verifyAssertion(params)
    } catch (e: IOException) {
        application.log.warn("Steam assertion check failed: {}", e.toString())
        return LoginResult.Failed(LoginErrors.UNAVAILABLE)
    }
    return if (valid) LoginResult.Verified(steamId) else LoginResult.Failed(LoginErrors.REJECTED)
}

fun ApplicationCall.session(): Session = checkNotNull(principal<Session>())

private fun returnTo(publicUrl: String, loginId: String) = "$publicUrl/auth/steam/callback?login=$loginId"

private fun loopback(login: PendingLogin, result: Pair<String, String>) =
    "http://127.0.0.1:${login.port}/callback?" + formEncode(mapOf("state" to login.state, result))

private suspend fun ApplicationCall.steamProfile(steam: Steam, steamId: Long): SteamProfile? = try {
    steam.profiles(listOf(steamId))[steamId]
} catch (e: IOException) {
    application.log.warn("Steam profile is unavailable: {}", e.message)
    null
}
