package xyz.thewhish.rustopsy.account

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.thewhish.rustopsy.core.sync.LoginErrors
import xyz.thewhish.rustopsy.core.sync.Tokens
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

class AuthorizationCode(val code: String, val verifier: String)

class LoginException(val reason: String, cause: Throwable? = null) : IOException("Steam login failed: $reason", cause)

class SteamLogin(private val baseUrl: String, private val openBrowser: (URI) -> Unit) {
    suspend fun authorize(): AuthorizationCode = withContext(Dispatchers.IO) {
        val state = Tokens.random()
        val verifier = Tokens.random()
        val result = CompletableDeferred<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(CALLBACK_PATH) { exchange -> exchange.use { handle(it, state, result) } }
        server.start()
        try {
            val query = "port=${server.address.port}&state=$state&challenge=${Tokens.challenge(verifier)}"
            try {
                openBrowser(URI("$baseUrl/auth/steam/start?$query"))
            } catch (e: IOException) {
                throw LoginException(BROWSER_FAILED, e)
            } catch (e: UnsupportedOperationException) {
                throw LoginException(BROWSER_FAILED, e)
            }
            val params = withTimeoutOrNull(TIMEOUT) { result.await() } ?: throw LoginException(LoginErrors.EXPIRED)
            params["error"]?.let { throw LoginException(it) }
            val code = params["code"]?.takeIf(Tokens::isValid) ?: throw LoginException(LoginErrors.REJECTED)
            AuthorizationCode(code, verifier)
        } finally {
            server.stop(0)
        }
    }

    private fun handle(exchange: HttpExchange, state: String, result: CompletableDeferred<Map<String, String>>) {
        val params = try {
            parseQuery(exchange.requestURI.rawQuery)
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
        val received = params["state"]
        if (exchange.requestMethod != "GET" || received == null || !MessageDigest.isEqual(received.toByteArray(), state.toByteArray())) {
            respond(exchange, 400, "Неверная ссылка входа.")
            return
        }
        respond(exchange, 200, if (params.containsKey("code")) SUCCESS_PAGE else FAILURE_PAGE)
        result.complete(params)
    }

    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val body = page(message).toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.write(body)
    }

    companion object {
        const val BROWSER_FAILED = "browser"
        private const val CALLBACK_PATH = "/callback"
        private val TIMEOUT = 5.minutes
        private const val SUCCESS_PAGE = "Вход выполнен. Вернись в Rustopsy, эту вкладку можно закрыть."
        private const val FAILURE_PAGE = "Вход не выполнен. Вернись в Rustopsy и попробуй ещё раз."

        private fun page(message: String) =
            "<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\"><title>Rustopsy</title>" +
                "<body style=\"background:#141414;color:#e8e4df;font:18px system-ui,sans-serif;display:grid;place-items:center;height:100vh;margin:0\">" +
                "<p>$message</p></body></html>"

        private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split('&')
            .filter { it.isNotEmpty() }
            .map { it.split('=', limit = 2) }
            .associate { URLDecoder.decode(it[0], Charsets.UTF_8) to URLDecoder.decode(it.getOrElse(1) { "" }, Charsets.UTF_8) }
    }
}
