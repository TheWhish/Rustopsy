package xyz.thewhish.rustopsy.server.team

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.thewhish.rustopsy.core.sync.ApiError
import xyz.thewhish.rustopsy.core.sync.Friend
import xyz.thewhish.rustopsy.core.sync.FriendActivity
import xyz.thewhish.rustopsy.core.sync.FriendsResponse
import xyz.thewhish.rustopsy.core.sync.FriendsSource
import xyz.thewhish.rustopsy.core.sync.InviteAnswer
import xyz.thewhish.rustopsy.core.sync.InviteRequest
import xyz.thewhish.rustopsy.core.sync.KickRequest
import xyz.thewhish.rustopsy.core.sync.LiveMessage
import xyz.thewhish.rustopsy.core.sync.STEAM_ID_RANGE
import xyz.thewhish.rustopsy.core.sync.TeamErrors
import xyz.thewhish.rustopsy.server.auth.AuthStore
import xyz.thewhish.rustopsy.server.auth.FriendList
import xyz.thewhish.rustopsy.server.auth.Steam
import xyz.thewhish.rustopsy.server.auth.SteamProfile
import xyz.thewhish.rustopsy.server.auth.session
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

private val json = Json { ignoreUnknownKeys = true }
private val SESSION_CHECK = 1.minutes
private const val MESSAGES_PER_SECOND = 10
private val log = LoggerFactory.getLogger("Team")

fun Route.teamRoutes(teams: TeamStore, hub: TeamHub, auth: AuthStore, steam: Steam) {
    get("/friends") {
        call.respond(friendsOf(call.session().steamId, teams, hub, steam))
    }
    route("/team") {
        get {
            respondState(teams, hub)
        }
        post("/invite") {
            val target = InviteTarget.parse(call.receive<InviteRequest>().target)
            val inviteeId = try {
                when (target) {
                    is InviteTarget.Id -> target.steamId
                    is InviteTarget.Vanity -> steam.resolveVanity(target.name)
                    null -> null
                }
            } catch (e: IOException) {
                call.application.log.warn("Steam profile lookup failed: {}", e.message)
                fail(TeamErrors.STEAM_UNAVAILABLE)
                return@post
            }
            if (inviteeId == null || inviteeId !in STEAM_ID_RANGE) {
                fail(TeamErrors.PLAYER_NOT_FOUND)
                return@post
            }
            teamAction(teams, hub) { me ->
                val invited = teams.invite(me, inviteeId)
                hub.teamsChanged(listOf(invited.teamId), listOf(me, inviteeId))
            }
        }
        post("/invites/accept") {
            val teamId = call.receive<InviteAnswer>().teamId
            teamAction(teams, hub) { me ->
                val accepted = teams.accept(me, teamId)
                hub.teamsChanged(listOfNotNull(accepted.teamId, accepted.previousTeamId), listOf(me))
            }
        }
        post("/invites/decline") {
            val teamId = call.receive<InviteAnswer>().teamId
            teamAction(teams, hub) { me ->
                teams.decline(me, teamId)
                hub.refresh(listOf(me))
            }
        }
        post("/leave") {
            teamAction(teams, hub) { me ->
                val teamId = teams.leave(me) ?: throw TeamException(TeamErrors.NOT_IN_TEAM)
                hub.teamsChanged(listOf(teamId), listOf(me))
            }
        }
        post("/kick") {
            val memberId = call.receive<KickRequest>().steamId
            teamAction(teams, hub) { me ->
                val teamId = teams.kick(me, memberId)
                hub.teamsChanged(listOf(teamId), listOf(memberId))
            }
        }
        webSocket("/live") {
            val session = call.session()
            val connection = LiveConnection(session.steamId)
            hub.connect(connection)
            val writer = launch {
                for (message in connection.outbox) send(Frame.Text(json.encodeToString<LiveMessage>(message)))
                close(CloseReason(CloseReason.Codes.NORMAL, "Session ended"))
            }
            val watchdog = launch {
                while (true) {
                    delay(SESSION_CHECK)
                    if (auth.sessionOwner(session.tokenHash) == null) {
                        connection.outbox.close()
                        break
                    }
                }
            }
            try {
                val rate = RateWindow(MESSAGES_PER_SECOND)
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    if (!rate.allow(System.currentTimeMillis())) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Too many messages"))
                        break
                    }
                    val message = try {
                        json.decodeFromString<LiveMessage>(frame.readText())
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    when (message) {
                        is LiveMessage.Hits -> hub.hits(connection, message.hits)
                        is LiveMessage.Ping -> connection.offer(LiveMessage.Pong(message.clientTimeMs, System.currentTimeMillis()))
                        is LiveMessage.State, is LiveMessage.Pong -> Unit
                    }
                }
            } finally {
                watchdog.cancel()
                writer.cancel()
                hub.disconnect(connection)
            }
        }
    }
}

private suspend fun friendsOf(me: Long, teams: TeamStore, hub: TeamHub, steam: Steam): FriendsResponse {
    val list = try {
        steam.friends(me)
    } catch (e: IOException) {
        log.warn("Steam friend list is unavailable: {}", e.message)
        return FriendsResponse(emptyList(), FriendsSource.UNAVAILABLE)
    }
    if (list !is FriendList.Visible) return FriendsResponse(emptyList(), FriendsSource.PRIVATE)
    val users = teams.registered(list.steamIds)
    val profiles = try {
        steam.profiles(users.map { it.steamId })
    } catch (e: IOException) {
        log.warn("Steam profiles are unavailable: {}", e.message)
        emptyMap<Long, SteamProfile>()
    }
    teams.refreshProfiles(profiles)
    val team = teams.teamOf(me)
    val teammates = team?.members?.map { it.steamId }.orEmpty().toSet()
    val invited = team?.let { teams.invitedBy(it.id) }.orEmpty()
    val online = hub.online(users.map { it.steamId })
    val friends = users.map { user ->
        val profile = profiles[user.steamId]
        Friend(
            steamId = user.steamId,
            name = profile?.name ?: user.name,
            avatarUrl = profile?.avatarUrl ?: user.avatarUrl,
            activity = profile?.activity ?: FriendActivity.OFFLINE,
            inApp = user.steamId in online,
            teammate = user.steamId in teammates,
            invited = user.steamId in invited,
        )
    }
    return FriendsResponse(friends.sortedWith(compareBy({ it.teammate }, { it.activity.ordinal }, { !it.inApp }, { it.name.lowercase() })), FriendsSource.STEAM)
}

private suspend fun RoutingContext.respondState(teams: TeamStore, hub: TeamHub) {
    call.respond(hub.view(teams.stateOf(call.session().steamId)))
}

private suspend fun RoutingContext.teamAction(teams: TeamStore, hub: TeamHub, action: suspend (Long) -> Unit) {
    try {
        action(call.session().steamId)
    } catch (e: TeamException) {
        fail(e.error)
        return
    }
    respondState(teams, hub)
}

private suspend fun RoutingContext.fail(error: String) {
    val status = when (error) {
        TeamErrors.PLAYER_NOT_FOUND, TeamErrors.PLAYER_NOT_REGISTERED, TeamErrors.INVITE_NOT_FOUND -> HttpStatusCode.NotFound
        TeamErrors.NOT_LEADER -> HttpStatusCode.Forbidden
        TeamErrors.STEAM_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
        else -> HttpStatusCode.Conflict
    }
    call.respond(status, ApiError(error))
}

private class RateWindow(private val limit: Int) {
    private var windowStart = 0L
    private var count = 0

    fun allow(now: Long): Boolean {
        if (now - windowStart >= 1_000) {
            windowStart = now
            count = 0
        }
        return ++count <= limit
    }
}
