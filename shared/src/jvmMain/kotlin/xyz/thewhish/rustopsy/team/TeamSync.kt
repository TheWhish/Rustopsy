package xyz.thewhish.rustopsy.team

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import xyz.thewhish.rustopsy.account.Account
import xyz.thewhish.rustopsy.account.AccountStatus
import xyz.thewhish.rustopsy.account.ApiException
import xyz.thewhish.rustopsy.account.ServerApi
import xyz.thewhish.rustopsy.core.sync.LiveMessage
import xyz.thewhish.rustopsy.core.sync.TeamErrors
import xyz.thewhish.rustopsy.core.sync.TeamState
import xyz.thewhish.rustopsy.platform.appLogger
import java.io.IOException
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.util.concurrent.CompletionStage
import java.util.logging.Level
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TeamSync(private val api: ServerApi, private val account: Account) {
    private val state = MutableStateFlow<TeamStatus>(TeamStatus.SignedOut)
    private val actions = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val status: StateFlow<TeamStatus> = state.asStateFlow()

    fun access() = TeamAccess(
        status = status,
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
            if (signedIn) stayLinked() else state.value = TeamStatus.SignedOut
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

    private suspend fun link(token: String) {
        val inbox = Channel<String>(INBOX_CAPACITY)
        val socket = api.openLive(token, Listener(inbox)).await()
        try {
            for (text in inbox) receive(json.decodeFromString<LiveMessage>(text))
        } catch (e: IllegalArgumentException) {
            throw IOException("Malformed live message", e)
        } finally {
            socket.abort()
        }
    }

    private fun receive(message: LiveMessage) {
        when (message) {
            is LiveMessage.State -> state.update { TeamStatus.Ready(message.state, linked = true, problem = (it as? TeamStatus.Ready)?.problem) }
            is LiveMessage.Hits, is LiveMessage.Ping, is LiveMessage.Pong -> Unit
        }
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
        const val INBOX_CAPACITY = 256
        const val MAX_MESSAGE_CHARS = 256 * 1024
        const val SIGNED_OUT = "Войди через Steam"
    }
}
