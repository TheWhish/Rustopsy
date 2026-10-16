package xyz.thewhish.rustopsy.team

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import xyz.thewhish.rustopsy.account.Account
import xyz.thewhish.rustopsy.account.AccountStatus
import xyz.thewhish.rustopsy.account.ApiException
import xyz.thewhish.rustopsy.account.ServerApi
import xyz.thewhish.rustopsy.core.session.CombatEvent
import xyz.thewhish.rustopsy.core.sync.LiveMessage
import xyz.thewhish.rustopsy.core.sync.TeamErrors
import xyz.thewhish.rustopsy.core.sync.TeamHit
import xyz.thewhish.rustopsy.core.sync.TeamState
import xyz.thewhish.rustopsy.core.sync.toEvent
import xyz.thewhish.rustopsy.core.sync.toTeamHit
import xyz.thewhish.rustopsy.platform.appLogger
import java.io.IOException
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.util.concurrent.CompletionStage
import java.util.logging.Level
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TeamSync(private val api: ServerApi, private val account: Account, private val clock: () -> Long = System::currentTimeMillis) {
    private val state = MutableStateFlow<TeamStatus>(TeamStatus.SignedOut)
    private val actions = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val outbox = Channel<List<CombatEvent>>(OUTBOX_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val pongs = Channel<Sample>(Channel.CONFLATED)
    private val offset = MutableStateFlow<Long?>(null)
    private val shared = LinkedHashSet<SharedHit>()
    private val sharedEvents = MutableStateFlow<List<CombatEvent>>(emptyList())

    val status: StateFlow<TeamStatus> = state.asStateFlow()

    fun access() = TeamAccess(
        status = status,
        events = sharedEvents.asStateFlow(),
        clockOffsetMs = offset.asStateFlow(),
        friends = ::friends,
        invite = { target -> act { api.invite(it, target) } },
        accept = { teamId -> act { api.acceptInvite(it, teamId) } },
        decline = { teamId -> act { api.declineInvite(it, teamId) } },
        leave = { act { api.leaveTeam(it) } },
        kick = { steamId -> act { api.kick(it, steamId) } },
        dismissProblem = { state.update { (it as? TeamStatus.Ready)?.copy(problem = null) ?: it } },
    )

    suspend fun run() {
        account.status.map { it is AccountStatus.SignedIn }.distinctUntilChanged().collectLatest { signedIn ->
            if (signedIn) {
                stayLinked()
            } else {
                state.value = TeamStatus.SignedOut
                keepShared { false }
            }
        }
    }

    private suspend fun stayLinked() {
        state.value = TeamStatus.Connecting
        var pause = MIN_PAUSE
        while (true) {
            val token = account.token() ?: return
            try {
                link(token)
                pause = MIN_PAUSE
            } catch (e: IOException) {
                if (e.status == ApiException.UNAUTHORIZED) {
                    account.restore()
                    return
                }
                appLogger.log(Level.INFO, "Team link failed", e)
                if (state.value !is TeamStatus.Ready) state.value = TeamStatus.Unavailable(problem(e))
            }
            state.update { (it as? TeamStatus.Ready)?.copy(linked = false) ?: it }
            delay(pause)
            pause = (pause * 2).coerceAtMost(MAX_PAUSE)
        }
    }

    fun share(events: List<CombatEvent>) {
        if (events.isNotEmpty()) outbox.trySend(events)
    }

    private suspend fun link(token: String) = coroutineScope {
        val inbox = Channel<String>(INBOX_CAPACITY)
        val socket = api.openLive(token, Listener(inbox)).await()
        val writer = Writer(socket)
        val tasks = listOf(launch { keepClockSynced(writer) }, launch { forwardHits(writer) })
        try {
            for (text in inbox) receive(json.decodeFromString<LiveMessage>(text))
        } catch (e: IllegalArgumentException) {
            throw IOException("Malformed live message", e)
        } finally {
            tasks.forEach { it.cancel() }
            socket.abort()
        }
    }

    private fun receive(message: LiveMessage) {
        when (message) {
            is LiveMessage.State -> {
                state.update { TeamStatus.Ready(message.state, linked = true, problem = (it as? TeamStatus.Ready)?.problem) }
                val members = message.state.team?.members.orEmpty().mapTo(HashSet()) { it.steamId }
                keepShared { it.from in members }
            }
            is LiveMessage.Hits -> addShared(message)
            is LiveMessage.Pong -> pongs.trySend(Sample(message, clock()))
            is LiveMessage.Ping -> Unit
        }
    }

    private suspend fun keepClockSynced(writer: Writer) {
        while (true) {
            val samples = mutableListOf<Sample>()
            repeat(CLOCK_SAMPLES) {
                writer.send(LiveMessage.Ping(clock()))
                withTimeoutOrNull(PONG_TIMEOUT) { pongs.receive() }?.takeIf { it.rttMs <= MAX_RTT_MS }?.let(samples::add)
                delay(SAMPLE_GAP)
            }
            if (samples.size >= MIN_CLOCK_SAMPLES) {
                offset.value = samples.map { it.offsetMs }.sorted()[samples.size / 2]
                publishShared()
            }
            delay(if (samples.size >= MIN_CLOCK_SAMPLES) CLOCK_RESYNC else CLOCK_RETRY)
        }
    }

    private suspend fun forwardHits(writer: Writer) {
        offset.filterNotNull().first()
        for (first in outbox) {
            val events = buildList {
                addAll(first)
                while (true) addAll(outbox.tryReceive().getOrNull() ?: break)
            }
            val team = (state.value as? TeamStatus.Ready)?.state?.team ?: continue
            if (team.members.size < 2) continue
            val clockOffset = offset.value ?: continue
            val freshAfterSec = (clock() - SHARED_WINDOW_MS) / 1_000.0
            val hits = events.filter { it.timeSec >= freshAfterSec }.mapNotNull { it.toTeamHit(clockOffset) }
            hits.chunked(MAX_HITS_PER_MESSAGE).forEach { writer.send(LiveMessage.Hits(it)) }
        }
    }

    private fun addShared(message: LiveMessage.Hits) {
        val members = (state.value as? TeamStatus.Ready)?.state?.team?.members.orEmpty()
        if (members.none { it.steamId == message.from }) return
        val oldestMs = clock() + (offset.value ?: 0) - SHARED_WINDOW_MS
        synchronized(shared) {
            message.hits.forEach { shared += SharedHit(message.from, it) }
            shared.removeAll { it.hit.timeMs < oldestMs }
            val excess = shared.size - MAX_SHARED_HITS
            if (excess > 0) shared.removeAll(shared.take(excess).toSet())
        }
        publishShared()
    }

    private fun keepShared(predicate: (SharedHit) -> Boolean) {
        val changed = synchronized(shared) { shared.retainAll(predicate) }
        if (changed) publishShared()
    }

    private fun publishShared() {
        val clockOffset = offset.value ?: 0
        sharedEvents.value = synchronized(shared) { shared.map { it.hit.toEvent(it.from, clockOffset) } }.sortedBy { it.timeSec }
    }

    private suspend fun friends(): FriendsLoad {
        val token = account.token() ?: return FriendsLoad.Failed(SIGNED_OUT)
        return try {
            FriendsLoad.Loaded(api.friends(token))
        } catch (e: IOException) {
            appLogger.log(Level.INFO, "Friends request failed", e)
            FriendsLoad.Failed(problem(e))
        }
    }

    private suspend fun act(call: suspend (String) -> TeamState): Boolean = actions.withLock {
        val token = account.token() ?: return@withLock false
        try {
            val result = call(token)
            state.update { TeamStatus.Ready(result, linked = (it as? TeamStatus.Ready)?.linked ?: false) }
            true
        } catch (e: IOException) {
            if (e.status == ApiException.UNAUTHORIZED) {
                account.restore()
                return@withLock false
            }
            appLogger.log(Level.INFO, "Team request failed", e)
            state.update { (it as? TeamStatus.Ready)?.copy(problem = problem(e)) ?: TeamStatus.Unavailable(problem(e)) }
            false
        }
    }

    private fun problem(e: IOException): String = when (e.status) {
        ApiException.UPDATE_REQUIRED -> "Обнови Rustopsy: эта версия не поддерживается сервером"
        null -> "Сервер Rustopsy недоступен. Подключение повторится автоматически"
        else -> when ((e as? ApiException)?.error) {
            TeamErrors.FULL -> "В команде уже 8 человек"
            TeamErrors.ALREADY_IN_TEAM -> "Этот игрок уже в твоей команде"
            TeamErrors.NOT_IN_TEAM -> "Ты не состоишь в команде"
            TeamErrors.NOT_LEADER -> "Это может сделать только лидер команды"
            TeamErrors.PLAYER_NOT_FOUND -> "Профиль Steam не найден. Проверь ссылку"
            TeamErrors.PLAYER_NOT_REGISTERED -> "Этот игрок ещё не входил в Rustopsy"
            TeamErrors.SELF -> "Нельзя пригласить самого себя"
            TeamErrors.INVITE_NOT_FOUND -> "Приглашение больше не действует"
            TeamErrors.STEAM_UNAVAILABLE -> "Steam не отвечает. Попробуй позже"
            else -> "Сервер отклонил запрос. Попробуй ещё раз"
        }
    }

    private val IOException.status: Int?
        get() = when (this) {
            is ApiException -> status
            is WebSocketHandshakeException -> response.statusCode()
            else -> null
        }

    private data class SharedHit(val from: Long, val hit: TeamHit)

    private class Sample(pong: LiveMessage.Pong, receivedAtMs: Long) {
        val rttMs = receivedAtMs - pong.clientTimeMs
        val offsetMs = pong.serverTimeMs - (pong.clientTimeMs + receivedAtMs) / 2
    }

    private inner class Writer(private val socket: WebSocket) {
        private val lock = Mutex()
        private var lastSentMs = 0L

        suspend fun send(message: LiveMessage) = lock.withLock {
            delay((lastSentMs + MIN_SEND_INTERVAL_MS - clock()).milliseconds)
            socket.sendText(json.encodeToString(message), true).await()
            lastSentMs = clock()
        }
    }

    private class Listener(private val inbox: Channel<String>) : WebSocket.Listener {
        private val text = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (text.length > MAX_MESSAGE_CHARS) {
                webSocket.abort()
                inbox.close(IOException("Live message is too large"))
                return null
            }
            if (last) {
                if (inbox.trySend(text.toString()).isFailure) webSocket.abort()
                text.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            inbox.close()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            inbox.close(IOException("Live connection failed", error))
        }
    }

    private companion object {
        val MIN_PAUSE: Duration = 1.seconds
        val MAX_PAUSE: Duration = 30.seconds
        val PONG_TIMEOUT: Duration = 3.seconds
        val SAMPLE_GAP: Duration = 200.milliseconds
        val CLOCK_RESYNC: Duration = 10.minutes
        val CLOCK_RETRY: Duration = 30.seconds
        const val CLOCK_SAMPLES = 5
        const val MIN_CLOCK_SAMPLES = 3
        const val MAX_RTT_MS = 2_000L
        const val MIN_SEND_INTERVAL_MS = 150L
        const val SHARED_WINDOW_MS = 15 * 60_000L
        const val MAX_SHARED_HITS = 4_000
        const val MAX_HITS_PER_MESSAGE = 200
        const val OUTBOX_CAPACITY = 64
        const val INBOX_CAPACITY = 256
        const val MAX_MESSAGE_CHARS = 256 * 1024
        const val SIGNED_OUT = "Войди через Steam"
    }
}
