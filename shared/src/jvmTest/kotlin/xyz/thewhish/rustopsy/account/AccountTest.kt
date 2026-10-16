package xyz.thewhish.rustopsy.account

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_HEADER
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_VERSION
import xyz.thewhish.rustopsy.core.sync.Tokens
import xyz.thewhish.rustopsy.core.sync.UserProfile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountTest {
    private var meStatus = 200
    private var logoutStatus = 204
    private val requests = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                requests += "${it.requestMethod} ${it.requestURI.path} ${it.requestHeaders.getFirst(PROTOCOL_HEADER)} ${it.requestHeaders.getFirst("Authorization")}"
                val (status, body) = when (it.requestURI.path) {
                    "/me" -> meStatus to """{"steamId":76561198000000001,"name":"Whish","avatarUrl":null}"""
                    else -> logoutStatus to ""
                }
                val bytes = body.toByteArray()
                it.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) it.responseBody.write(bytes)
            }
        }
        start()
    }
    private val sessionFile = createTempDirectory("rustopsy-account").resolve("session.bin")
    private val vault = SessionVault(sessionFile)
    private val token = Tokens.random()
    private val baseUrl = "http://127.0.0.1:${server.address.port}"
    private val account = Account(ServerApi(baseUrl, HttpClient.newHttpClient()), SteamLogin(baseUrl) {}, vault, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun restoresSavedSession() = runBlocking {
        vault.save(token)
        account.restore()
        assertEquals(AccountStatus.SignedIn(UserProfile(76561198000000001, "Whish", null)), account.status.value)
        assertEquals("GET /me $PROTOCOL_VERSION Bearer $token", requests.single())
    }

    @Test
    fun noSavedSessionMeansSignedOut() = runBlocking {
        account.restore()
        assertEquals(AccountStatus.SignedOut(), account.status.value)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun revokedSessionIsForgotten() = runBlocking {
        vault.save(token)
        meStatus = 401
        account.restore()
        assertIs<AccountStatus.SignedOut>(account.status.value)
        assertFalse(Files.exists(sessionFile))
    }

    @Test
    fun unreachableServerKeepsSession() = runBlocking {
        vault.save(token)
        meStatus = 503
        account.restore()
        assertIs<AccountStatus.Unavailable>(account.status.value)
        assertTrue(Files.exists(sessionFile))
    }

    @Test
    fun signOutRevokesOnServerThenForgetsLocally() = runBlocking {
        vault.save(token)
        account.restore()
        account.signOut(everywhere = true)
        assertEquals("POST /auth/logout-all $PROTOCOL_VERSION Bearer $token", requests.last())
        assertEquals(AccountStatus.SignedOut(), account.status.value)
        assertFalse(Files.exists(sessionFile))
    }

    @Test
    fun failedSignOutKeepsSessionAndExplains() = runBlocking {
        vault.save(token)
        account.restore()
        logoutStatus = 500
        account.signOut(everywhere = false)
        val status = assertIs<AccountStatus.SignedIn>(account.status.value)
        assertTrue(status.problem != null)
        assertTrue(Files.exists(sessionFile))
    }
}
