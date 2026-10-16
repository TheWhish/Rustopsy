package xyz.thewhish.rustopsy.account

import kotlinx.coroutines.runBlocking
import xyz.thewhish.rustopsy.core.sync.LoginErrors
import xyz.thewhish.rustopsy.core.sync.Tokens
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SteamLoginTest {
    private val http = HttpClient.newHttpClient()
    private val base = "https://rustopsy.test"

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate {
        val (key, value) = it.split('=', limit = 2)
        key to URLDecoder.decode(value, Charsets.UTF_8)
    }

    private fun callback(start: URI, params: String): Int {
        val port = query(start).getValue("port")
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/callback?$params")).GET().build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun returnsCodeAndVerifierMatchingChallenge() = runBlocking {
        val code = Tokens.random()
        var challenge = ""
        val result = SteamLogin(base) { start ->
            assertTrue(start.toString().startsWith("$base/auth/steam/start?"))
            val params = query(start)
            challenge = params.getValue("challenge")
            assertEquals(400, callback(start, "state=${Tokens.random()}&code=$code"))
            assertEquals(200, callback(start, "state=${params.getValue("state")}&code=$code"))
        }.authorize()
        assertEquals(code, result.code)
        assertTrue(Tokens.matchesChallenge(result.verifier, challenge))
    }

    @Test
    fun reportsErrorFromServer() = runBlocking {
        val error = assertFailsWith<LoginException> {
            SteamLogin(base) { start ->
                callback(start, "state=${query(start).getValue("state")}&error=${LoginErrors.CANCELLED}")
            }.authorize()
        }
        assertEquals(LoginErrors.CANCELLED, error.reason)
    }

    @Test
    fun reportsBrowserFailure() = runBlocking {
        val error = assertFailsWith<LoginException> { SteamLogin(base) { throw IOException("no browser") }.authorize() }
        assertEquals(SteamLogin.BROWSER_FAILED, error.reason)
    }
}
