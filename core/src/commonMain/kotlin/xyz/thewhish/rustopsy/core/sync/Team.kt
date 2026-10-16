package xyz.thewhish.rustopsy.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val TEAM_MAX_MEMBERS = 8

val STEAM_ID_RANGE = 76561197960265728L..76561202255233023L

@Serializable
data class TeamMember(val steamId: Long, val name: String, val avatarUrl: String?, val online: Boolean)

@Serializable
data class TeamInfo(val id: Long, val leaderId: Long, val members: List<TeamMember>)

@Serializable
data class TeamInvite(val teamId: Long, val from: UserProfile, val members: Int)

@Serializable
data class TeamState(val team: TeamInfo?, val invites: List<TeamInvite>)

@Serializable
data class InviteRequest(val target: String)

@Serializable
data class InviteAnswer(val teamId: Long)

@Serializable
data class KickRequest(val steamId: Long)

@Serializable
enum class FriendActivity { PLAYING_RUST, ONLINE, OFFLINE }

@Serializable
data class Friend(
    val steamId: Long,
    val name: String,
    val avatarUrl: String?,
    val activity: FriendActivity,
    val inApp: Boolean,
    val teammate: Boolean,
    val invited: Boolean,
)

@Serializable
enum class FriendsSource { STEAM, PRIVATE, UNAVAILABLE }

@Serializable
data class FriendsResponse(val friends: List<Friend>, val source: FriendsSource)

@Serializable
data class TeamHit(
    val timeMs: Long,
    val targetId: Long,
    val weapon: String,
    val ammo: String,
    val area: String,
    val distanceM: Double?,
    val oldHp: Double?,
    val newHp: Double?,
    val info: String,
)

@Serializable
sealed interface LiveMessage {
    @Serializable
    @SerialName("state")
    data class State(val state: TeamState) : LiveMessage

    @Serializable
    @SerialName("hits")
    data class Hits(val hits: List<TeamHit>, val from: Long = 0) : LiveMessage

    @Serializable
    @SerialName("ping")
    data class Ping(val clientTimeMs: Long) : LiveMessage

    @Serializable
    @SerialName("pong")
    data class Pong(val clientTimeMs: Long, val serverTimeMs: Long) : LiveMessage
}

object TeamErrors {
    const val FULL = "team_full"
    const val ALREADY_IN_TEAM = "already_in_team"
    const val NOT_IN_TEAM = "not_in_team"
    const val NOT_LEADER = "not_leader"
    const val PLAYER_NOT_FOUND = "player_not_found"
    const val PLAYER_NOT_REGISTERED = "player_not_registered"
    const val SELF = "self"
    const val INVITE_NOT_FOUND = "invite_not_found"
    const val STEAM_UNAVAILABLE = "steam_unavailable"
}
