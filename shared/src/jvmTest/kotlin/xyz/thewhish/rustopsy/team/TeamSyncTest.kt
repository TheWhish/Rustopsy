package xyz.thewhish.rustopsy.team

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import xyz.thewhish.rustopsy.account.Account
import xyz.thewhish.rustopsy.account.AccountStatus
import xyz.thewhish.rustopsy.account.ServerApi
import xyz.thewhish.rustopsy.account.SessionVault
import xyz.thewhish.rustopsy.account.SteamLogin
import xyz.thewhish.rustopsy.core.sync.FriendsResponse
import xyz.thewhish.rustopsy.core.sync.FriendsSource
import xyz.thewhish.rustopsy.core.sync.LiveMessage
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_HEADER
import xyz.thewhish.rustopsy.core.sync.TeamInfo
import xyz.thewhish.rustopsy.core.sync.TeamMember
import xyz.thewhish.rustopsy.core.sync.TeamState
import xyz.thewhish.rustopsy.core.sync.Tokens
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class TeamSyncTest {
    private val selfId = 76561198000000001
    private val state = TeamState(TeamInfo(7, selfId, listOf(TeamMember(selfId, "Whish", null, online = true))), emptyList())
    private val deny = AtomicBoolean(false)
    private val dropAfterState = AtomicBoolean(false)
    private val connections = AtomicInteger()

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        install(WebSockets)
        install(
            createApplicationPlugin("Deny") {
                onCall { call -> if (deny.get()) call.respond(HttpStatusCode.Unauthorized) }
            },
        )
        routing {
            get("/me") { call.respondText("""{"steamId":$selfId,"name":"Whish","avatarUrl":null}""", ContentType.Application.Json) }
            get("/friends") { call.respondText(Json.encodeToString(FriendsResponse(emptyList(), FriendsSource.PRIVATE)), ContentType.Application.Json) }
            post("/team/invite") {
                call.respondText("""{"error":"player_not_registered"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            }
            webSocket("/team/live") {
                check(call.request.headers[PROTOCOL_HEADER] != null)
                connections.incrementAndGet()
                send(Frame.Text(Json.encodeToString<LiveMessage>(LiveMessage.State(state))))
                if (dropAfterState.getAndSet(false)) close() else for (frame in incoming) Unit
            }
        }
    }.start()

    private val baseUrl = runBlocking { "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}" }
    private val vault = SessionVault(createTempDirectory("rustopsy-team").resolve("session.bin")).apply { save(Tokens.random()) }
    private val api = ServerApi(baseUrl, HttpClient.newHttpClient())
    private val account = Account(api, SteamLogin(baseUrl) {}, vault, CoroutineScope(Dispatchers.Default))
    private val sync = TeamSync(api, account)

    @AfterTest
    fun stop() = server.stop(0, 0)

    private fun withSync(block: suspend () -> Unit) = runBlocking {
        account.restore()
        val job = launch { sync.run() }
        try {
            withTimeout(10.seconds) { block() }
        } finally {
            job.cancel()
        }
    }

    private suspend fun ready(linked: Boolean = true) = sync.status.first { it is TeamStatus.Ready && it.linked == linked } as TeamStatus.Ready

    @Test
    fun followsTeamStateFromLiveChannel() = withSync {
        assertEquals(state, ready().state)
    }

    @Test
    fun reconnectsAfterServerDrop() = withSync {
        dropAfterState.set(true)
        ready(linked = false)
        ready(linked = true)
        assertEquals(2, connections.get())
    }

    @Test
    fun revokedSessionSignsOut() {
        dropAfterState.set(true)
        withSync {
            sync.status.first { it is TeamStatus.Ready }
            deny.set(true)
            sync.status.first { it == TeamStatus.SignedOut }
            assertIs<AccountStatus.SignedOut>(account.status.value)
        }
    }

    @Test
    fun explainsRejectedInviteAndCanDismissIt() = withSync {
        ready()
        val access = sync.access()
        assertFalse(access.invite("76561198000000002"))
        assertEquals("Этот игрок ещё не входил в Rustopsy", (sync.status.value as TeamStatus.Ready).problem)
        access.dismissProblem()
        assertEquals(null, (sync.status.value as TeamStatus.Ready).problem)
    }

    @Test
    fun loadsFriends() = withSync {
        ready()
        val load = assertIs<FriendsLoad.Loaded>(sync.access().friends())
        assertEquals(FriendsSource.PRIVATE, load.response.source)
    }
}
