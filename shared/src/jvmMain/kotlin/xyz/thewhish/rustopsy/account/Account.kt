package xyz.thewhish.rustopsy.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.thewhish.rustopsy.core.sync.LoginErrors
import xyz.thewhish.rustopsy.platform.appLogger
import java.io.IOException
import java.util.logging.Level

class Account(
    private val api: ServerApi,
    private val login: SteamLogin,
    private val vault: SessionVault,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<AccountStatus>(AccountStatus.Loading)
    private val mutex = Mutex()
    private var signInJob: Job? = null
    @Volatile
    private var token: String? = null

    val status: StateFlow<AccountStatus> = state.asStateFlow()

    fun token(): String? = token

    fun access() = AccountAccess(status, ::startSignIn, ::cancelSignIn, ::signOut, ::deleteAccount, ::restore)

    fun startSignIn() {
        if (signInJob?.isActive == true) return
        signInJob = scope.launch { signIn() }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
    }

    suspend fun restore() = mutex.withLock {
        val saved = try {
            withContext(Dispatchers.IO) { vault.load() }
        } catch (e: IOException) {
            appLogger.log(Level.WARNING, "Saved session is unreadable, signing out", e)
            clearLocal()
            null
        }
        token = saved
        state.value = if (saved == null) AccountStatus.SignedOut() else profileState(saved)
    }

    private suspend fun signIn() = mutex.withLock {
        state.value = AccountStatus.SigningIn
        try {
            val authorization = login.authorize()
            val session = api.exchange(authorization.code, authorization.verifier)
            withContext(Dispatchers.IO) { vault.save(session.token) }
            token = session.token
            state.value = AccountStatus.SignedIn(session.profile)
        } catch (e: IOException) {
            appLogger.log(Level.INFO, "Steam sign-in failed", e)
            state.value = AccountStatus.SignedOut(signInProblem(e))
        } finally {
            if (state.value == AccountStatus.SigningIn) state.value = AccountStatus.SignedOut()
        }
    }

    suspend fun signOut(everywhere: Boolean) = signedInAction { current -> api.logout(current, everywhere) }

    suspend fun deleteAccount() = signedInAction { current -> api.deleteAccount(current) }

    private suspend fun signedInAction(call: suspend (String) -> Unit) = mutex.withLock {
        val current = token ?: return@withLock
        val signedIn = state.value as? AccountStatus.SignedIn
        try {
            call(current)
        } catch (e: IOException) {
            if (e !is ApiException || !e.unauthorized) {
                appLogger.log(Level.INFO, "Account request failed", e)
                state.value = signedIn?.copy(problem = requestProblem(e)) ?: AccountStatus.Unavailable(requestProblem(e))
                return@withLock
            }
        }
        clearLocal()
        state.value = AccountStatus.SignedOut()
    }

    private suspend fun profileState(saved: String): AccountStatus = try {
        AccountStatus.SignedIn(api.me(saved))
    } catch (e: ApiException) {
        if (e.unauthorized) {
            clearLocal()
            AccountStatus.SignedOut("Вход истёк. Войди через Steam снова")
        } else {
            AccountStatus.Unavailable(requestProblem(e))
        }
    } catch (e: IOException) {
        appLogger.log(Level.INFO, "Profile request failed", e)
        AccountStatus.Unavailable(requestProblem(e))
    }

    private suspend fun clearLocal() {
        token = null
        withContext(Dispatchers.IO) { vault.clear() }
    }

    private fun signInProblem(e: IOException): String = when {
        e is LoginException -> when (e.reason) {
            LoginErrors.CANCELLED -> "Вход отменён на сайте Steam"
            LoginErrors.EXPIRED -> "Время на вход истекло. Попробуй ещё раз"
            LoginErrors.UNAVAILABLE -> "Steam не ответил. Попробуй позже"
            SteamLogin.BROWSER_FAILED -> "Не удалось открыть браузер для входа"
            else -> "Steam не подтвердил вход"
        }
        else -> requestProblem(e)
    }

    private fun requestProblem(e: IOException): String =
        if (e is ApiException && e.updateRequired) "Обнови Rustopsy: эта версия не поддерживается сервером" else "Сервер Rustopsy недоступен. Попробуй позже"
}
