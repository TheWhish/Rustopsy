package xyz.thewhish.rustopsy.team

import kotlinx.coroutines.flow.StateFlow
import xyz.thewhish.rustopsy.core.sync.FriendsResponse
import xyz.thewhish.rustopsy.core.sync.TeamState

sealed interface TeamStatus {
    data object SignedOut : TeamStatus
    data object Connecting : TeamStatus
    data class Unavailable(val problem: String) : TeamStatus
    data class Ready(val state: TeamState, val linked: Boolean, val problem: String? = null) : TeamStatus
}

sealed interface FriendsLoad {
    data class Loaded(val response: FriendsResponse) : FriendsLoad
    data class Failed(val problem: String) : FriendsLoad
}

class TeamAccess(
    val status: StateFlow<TeamStatus>,
    val friends: suspend () -> FriendsLoad,
    val invite: suspend (target: String) -> Boolean,
    val accept: suspend (teamId: Long) -> Unit,
    val decline: suspend (teamId: Long) -> Unit,
    val leave: suspend () -> Unit,
    val kick: suspend (steamId: Long) -> Unit,
    val dismissProblem: () -> Unit,
)
