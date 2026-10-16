package xyz.thewhish.rustopsy.account

import kotlinx.coroutines.flow.StateFlow
import xyz.thewhish.rustopsy.core.sync.UserProfile

sealed interface AccountStatus {
    data object Loading : AccountStatus
    data class SignedOut(val problem: String? = null) : AccountStatus
    data object SigningIn : AccountStatus
    data class SignedIn(val profile: UserProfile, val problem: String? = null) : AccountStatus
    data class Unavailable(val problem: String) : AccountStatus
}

class AccountAccess(
    val status: StateFlow<AccountStatus>,
    val signIn: () -> Unit,
    val cancelSignIn: () -> Unit,
    val signOut: suspend (everywhere: Boolean) -> Unit,
    val deleteAccount: suspend () -> Unit,
    val retry: suspend () -> Unit,
)
