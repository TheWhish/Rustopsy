package xyz.thewhish.rustopsy.server.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import xyz.thewhish.rustopsy.core.sync.ApiError
import xyz.thewhish.rustopsy.core.sync.ApiErrors
import xyz.thewhish.rustopsy.core.sync.LoginErrors
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_HEADER
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_VERSION
import xyz.thewhish.rustopsy.core.sync.SessionResponse
import xyz.thewhish.rustopsy.core.sync.TokenRequest
import xyz.thewhish.rustopsy.core.sync.Tokens
import xyz.thewhish.rustopsy.core.sync.UserProfile
import xyz.thewhish.rustopsy.server.TEST_PUBLIC_URL
import xyz.thewhish.rustopsy.server.TestDatabase
import xyz.thewhish.rustopsy.server.connectDatabase
import xyz.thewhish.rustopsy.server.fakeSteam
import xyz.thewhish.rustopsy.server.module
import java.io.IOException
import xyz.thewhish.rustopsy.server.nextSteamId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AuthTest {
    private val port = 50123

    private class Loopback(val url: Url) {
        val state get() = url.parameters["state"]
        val code get() = url.parameters["code"]
        val error get() = url.parameters["error"]
    }

    private class Flow(val verifier: String, val state: String, val returnTo: String)

    private fun authTest(steam: Steam = fakeSteam(), block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
        connectDatabase(TestDatabase.config()).use { dataSource ->
            testApplication {
                application { module(dataSource, TEST_PUBLIC_URL, steam) }
                block(createClient { followRedirects = false })
            }
        }

    private suspend fun HttpClient.start(): Flow {
        val verifier = Tokens.random()
        val state = Tokens.random()
        val response = get("/auth/steam/start?port=$port&state=$state&challenge=${Tokens.challenge(verifier)}")
        assertEquals(HttpStatusCode.Found, response.status)
        val steamUrl = Url(response.headers[HttpHeaders.Location]!!)
        assertEquals("steamcommunity.com", steamUrl.host)
        assertEquals(TEST_PUBLIC_URL, steamUrl.parameters["openid.realm"])
        return Flow(verifier, state, steamUrl.parameters["openid.return_to"]!!)
    }

    private suspend fun HttpClient.callback(flow: Flow, assertion: Map<String, String>): Loopback {
        val response = get(flow.returnTo.removePrefix(TEST_PUBLIC_URL) + "&" + formEncode(assertion))
        assertEquals(HttpStatusCode.Found, response.status)
        val url = Url(response.headers[HttpHeaders.Location]!!)
        assertEquals("127.0.0.1", url.host)
        assertEquals(port, url.port)
        return Loopback(url).also { assertEquals(flow.state, it.state) }
    }

    private suspend fun HttpClient.exchange(code: String, verifier: String): HttpResponse = post("/auth/token") {
        header(PROTOCOL_HEADER, PROTOCOL_VERSION)
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(TokenRequest(code, verifier)))
    }

    private suspend fun HttpClient.login(steamId: Long = nextSteamId()): SessionResponse {
        val flow = start()
        val code = callback(flow, assertion(flow.returnTo, steamId)).code!!
        val response = exchange(code, flow.verifier)
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.decodeFromString(response.bodyAsText())
    }

    private suspend fun HttpClient.me(token: String): HttpResponse = get("/me") {
        header(PROTOCOL_HEADER, PROTOCOL_VERSION)
        bearerAuth(token)
    }

    @Test
    fun fullLoginIssuesSessionWithSteamProfile() = authTest { client ->
        val steamId = nextSteamId()
        val session = client.login(steamId)
        assertEquals(UserProfile(steamId, "Whish", "https://avatars.steamstatic.com/whish_full.jpg"), session.profile)
        val me = client.me(session.token)
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(session.profile, Json.decodeFromString<UserProfile>(me.bodyAsText()))
    }

    @Test
    fun steamIdBecomesNameWhenProfileIsUnavailable() = authTest(fakeSteam(profile = null)) { client ->
        val steamId = nextSteamId()
        assertEquals(UserProfile(steamId, steamId.toString(), null), client.login(steamId).profile)
    }

    @Test
    fun codeIsSingleUseAndBoundToVerifier() = authTest { client ->
        val flow = client.start()
        val code = client.callback(flow, assertion(flow.returnTo, nextSteamId())).code!!
        val wrong = client.exchange(code, Tokens.random())
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertEquals(ApiError(ApiErrors.INVALID_CODE), Json.decodeFromString(wrong.bodyAsText()))
        assertEquals(HttpStatusCode.BadRequest, client.exchange(code, flow.verifier).status)
    }

    @Test
    fun loginLinkWorksOnlyOnce() = authTest { client ->
        val flow = client.start()
        val assertion = assertion(flow.returnTo, nextSteamId())
        client.callback(flow, assertion)
        val replay = client.get(flow.returnTo.removePrefix(TEST_PUBLIC_URL) + "&" + formEncode(assertion))
        assertEquals(HttpStatusCode.BadRequest, replay.status)
    }

    @Test
    fun assertionRejectedBySteamIsReported() = authTest(fakeSteam(verify = { false })) { client ->
        val flow = client.start()
        val loopback = client.callback(flow, assertion(flow.returnTo, nextSteamId()))
        assertEquals(LoginErrors.REJECTED, loopback.error)
        assertNull(loopback.code)
    }

    @Test
    fun tamperedAssertionIsRejectedWithoutAskingSteam() {
        var asked = false
        authTest(fakeSteam(verify = { asked = true; true })) { client ->
            val flow = client.start()
            val foreignReturnTo = assertion(flow.returnTo, nextSteamId()) + ("openid.return_to" to "https://evil.test/callback")
            assertEquals(LoginErrors.REJECTED, client.callback(flow, foreignReturnTo).error)
            val flow2 = client.start()
            val badClaim = assertion(flow2.returnTo, nextSteamId()) + ("openid.claimed_id" to "https://steamcommunity.com/openid/id/123")
            assertEquals(LoginErrors.REJECTED, client.callback(flow2, badClaim).error)
        }
        assertFalse(asked)
    }

    @Test
    fun cancelledAndUnavailableLoginsAreReported() = authTest(fakeSteam(verify = { throw IOException("timeout") })) { client ->
        val cancelled = client.start()
        assertEquals(LoginErrors.CANCELLED, client.callback(cancelled, mapOf("openid.mode" to "cancel")).error)
        val unavailable = client.start()
        assertEquals(LoginErrors.UNAVAILABLE, client.callback(unavailable, assertion(unavailable.returnTo, nextSteamId())).error)
    }

    @Test
    fun startRejectsInvalidParameters() = authTest { client ->
        val challenge = Tokens.challenge(Tokens.random())
        val state = Tokens.random()
        listOf(
            "port=80&state=$state&challenge=$challenge",
            "port=$port&state=short&challenge=$challenge",
            "port=$port&state=$state&challenge=bad",
            "state=$state&challenge=$challenge",
        ).forEach { query -> assertEquals(HttpStatusCode.BadRequest, client.get("/auth/steam/start?$query").status, query) }
    }

    @Test
    fun logoutRevokesOnlyThatSession() = authTest { client ->
        val steamId = nextSteamId()
        val first = client.login(steamId)
        val second = client.login(steamId)
        assertEquals(HttpStatusCode.NoContent, client.post("/auth/logout") { header(PROTOCOL_HEADER, PROTOCOL_VERSION); bearerAuth(first.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.me(first.token).status)
        assertEquals(HttpStatusCode.OK, client.me(second.token).status)
    }

    @Test
    fun logoutAllRevokesEverySession() = authTest { client ->
        val steamId = nextSteamId()
        val first = client.login(steamId)
        val second = client.login(steamId)
        client.post("/auth/logout-all") { header(PROTOCOL_HEADER, PROTOCOL_VERSION); bearerAuth(first.token) }
        assertEquals(HttpStatusCode.Unauthorized, client.me(first.token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.me(second.token).status)
    }

    @Test
    fun deletingAccountRemovesProfileAndSessions() = authTest { client ->
        val session = client.login()
        assertEquals(HttpStatusCode.NoContent, client.delete("/me") { header(PROTOCOL_HEADER, PROTOCOL_VERSION); bearerAuth(session.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.me(session.token).status)
    }

    @Test
    fun rejectsMissingOrGarbageToken() = authTest { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me") { header(PROTOCOL_HEADER, PROTOCOL_VERSION) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.me("garbage").status)
        assertEquals(HttpStatusCode.Unauthorized, client.me(Tokens.random()).status)
    }

    @Test
    fun outdatedClientIsAskedToUpdate() = authTest { client ->
        val session = client.login()
        val response = client.get("/me") { bearerAuth(session.token) }
        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
        assertEquals(ApiError(ApiErrors.UPDATE_REQUIRED), Json.decodeFromString(response.bodyAsText()))
    }

    private companion object {
        fun assertion(returnTo: String, steamId: Long): Map<String, String> {
            val claimed = "https://steamcommunity.com/openid/id/$steamId"
            return mapOf(
                "openid.ns" to "http://specs.openid.net/auth/2.0",
                "openid.mode" to "id_res",
                "openid.op_endpoint" to SteamOpenId.ENDPOINT,
                "openid.claimed_id" to claimed,
                "openid.identity" to claimed,
                "openid.return_to" to returnTo,
                "openid.response_nonce" to "2026-10-16T08:00:00Zabc",
                "openid.assoc_handle" to "1234567890",
                "openid.signed" to "signed,op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle",
                "openid.sig" to "c2lnbmF0dXJl",
            )
        }
    }
}
