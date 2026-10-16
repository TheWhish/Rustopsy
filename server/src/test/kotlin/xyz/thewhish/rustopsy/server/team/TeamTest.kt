package xyz.thewhish.rustopsy.server.team

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import xyz.thewhish.rustopsy.core.sync.ApiError
import xyz.thewhish.rustopsy.core.sync.FriendActivity
import xyz.thewhish.rustopsy.core.sync.FriendsResponse
import xyz.thewhish.rustopsy.core.sync.FriendsSource
import xyz.thewhish.rustopsy.core.sync.InviteAnswer
import xyz.thewhish.rustopsy.core.sync.InviteRequest
import xyz.thewhish.rustopsy.core.sync.KickRequest
import xyz.thewhish.rustopsy.core.sync.LiveMessage
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_HEADER
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_VERSION
import xyz.thewhish.rustopsy.core.sync.TEAM_MAX_MEMBERS
import xyz.thewhish.rustopsy.core.sync.TeamErrors
import xyz.thewhish.rustopsy.core.sync.TeamHit
import xyz.thewhish.rustopsy.core.sync.TeamState
import xyz.thewhish.rustopsy.core.sync.Tokens
import xyz.thewhish.rustopsy.server.TEST_PUBLIC_URL
import xyz.thewhish.rustopsy.server.TestDatabase
import xyz.thewhish.rustopsy.server.auth.AuthStore
import xyz.thewhish.rustopsy.server.auth.FriendList
import xyz.thewhish.rustopsy.server.auth.Steam
import xyz.thewhish.rustopsy.server.auth.SteamProfile
import xyz.thewhish.rustopsy.server.connectDatabase
import xyz.thewhish.rustopsy.server.fakeSteam
import xyz.thewhish.rustopsy.server.module
import xyz.thewhish.rustopsy.server.nextSteamId
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TeamTest {
    private class Player(val steamId: Long, val token: String)

    private class Env(val client: HttpClient, private val dataSource: DataSource) {
        suspend fun player(name: String = "Player"): Player {
            val steamId = nextSteamId()
            val store = AuthStore(dataSource)
            store.saveUser(steamId, SteamProfile(name, null))
            val token = Tokens.random()
            store.createSession(Tokens.sha256(token), steamId)
            return Player(steamId, token)
        }

        suspend fun call(player: Player, path: String, json: String? = null, method: String = "POST"): HttpResponse {
            val block: HttpRequestBuilder.() -> Unit = {
                header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                bearerAuth(player.token)
                if (json != null) {
                    contentType(ContentType.Application.Json)
                    setBody(json)
                }
            }
            return when (method) {
                "GET" -> client.get(path, block)
                "DELETE" -> client.delete(path, block)
                else -> client.post(path, block)
            }
        }

        suspend fun state(player: Player): TeamState = Json.decodeFromString(call(player, "/team", method = "GET").bodyAsText())

        suspend fun invite(from: Player, target: String): HttpResponse = call(from, "/team/invite", Json.encodeToString(InviteRequest(target)))

        suspend fun invite(from: Player, to: Player): TeamState {
            val response = invite(from, to.steamId.toString())
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return Json.decodeFromString(response.bodyAsText())
        }

        suspend fun accept(player: Player, teamId: Long): HttpResponse = call(player, "/team/invites/accept", Json.encodeToString(InviteAnswer(teamId)))

        suspend fun team(leader: Player, vararg members: Player): Long {
            members.forEach { member ->
                val teamId = assertNotNull(invite(leader, member).team).id
                assertEquals(HttpStatusCode.OK, accept(member, teamId).status)
            }
            return assertNotNull(state(leader).team).id
        }
    }

    private fun teamTest(steam: Steam = fakeSteam(), block: suspend Env.() -> Unit) = connectDatabase(TestDatabase.config()).use { dataSource ->
        testApplication {
            application { module(dataSource, TEST_PUBLIC_URL, steam) }
            Env(createClient { install(WebSockets) }, dataSource).block()
        }
    }

    private suspend fun HttpResponse.error(): String = Json.decodeFromString<ApiError>(bodyAsText()).error

    @Test
    fun invitingCreatesTeamAndFriendAccepts() = teamTest {
        val leader = player("Leader")
        val friend = player("Friend")
        val team = assertNotNull(invite(leader, friend).team)
        assertEquals(leader.steamId, team.leaderId)
        assertEquals(listOf("Leader"), team.members.map { it.name })

        val invite = state(friend).invites.single()
        assertEquals(team.id, invite.teamId)
        assertEquals("Leader", invite.from.name)

        val joined = Json.decodeFromString<TeamState>(accept(friend, team.id).bodyAsText())
        assertEquals(listOf("Leader", "Friend"), joined.team?.members?.map { it.name })
        assertTrue(joined.invites.isEmpty())
        assertEquals(TeamErrors.INVITE_NOT_FOUND, accept(friend, team.id).error())
    }

    @Test
    fun invitesBySteamProfileLinkOrCustomUrl() {
        val vanity = mutableMapOf<String, Long>()
        teamTest(fakeSteam(vanity = vanity)) {
            val leader = player()
            val byLink = player()
            val byName = player()
            vanity["petya"] = byName.steamId
            assertEquals(HttpStatusCode.OK, invite(leader, "https://steamcommunity.com/profiles/${byLink.steamId}/").status)
            assertEquals(HttpStatusCode.OK, invite(leader, "https://steamcommunity.com/id/petya/").status)
            assertEquals(1, state(byLink).invites.size)
            assertEquals(1, state(byName).invites.size)
            assertEquals(TeamErrors.PLAYER_NOT_FOUND, invite(leader, "nobody").error())
            assertEquals(TeamErrors.PLAYER_NOT_FOUND, invite(leader, "https://evil.test/").error())
            assertEquals(TeamErrors.PLAYER_NOT_REGISTERED, invite(leader, nextSteamId().toString()).error())
            assertEquals(TeamErrors.SELF, invite(leader, leader.steamId.toString()).error())
        }
    }

    @Test
    fun acceptingMovesPlayerBetweenTeams() = teamTest {
        val first = player("First")
        val second = player("Second")
        val mover = player("Mover")
        val oldTeam = team(first, mover)
        val newTeam = assertNotNull(invite(second, mover).team).id
        assertEquals(HttpStatusCode.OK, accept(mover, newTeam).status)
        assertEquals(listOf(second.steamId, mover.steamId), state(mover).team?.members?.map { it.steamId })
        assertEquals(oldTeam, state(first).team?.id)
        assertEquals(listOf(first.steamId), state(first).team?.members?.map { it.steamId })
    }

    @Test
    fun fullTeamRejectsInvitesAndAccepts() = teamTest {
        val leader = player()
        val late = player()
        val teamId = assertNotNull(invite(leader, late).team).id
        team(leader, *Array(TEAM_MAX_MEMBERS - 1) { player() })
        assertEquals(TeamErrors.FULL, invite(leader, player().steamId.toString()).error())
        assertEquals(TeamErrors.FULL, accept(late, teamId).error())
    }

    @Test
    fun declinedInviteIsGone() = teamTest {
        val leader = player()
        val friend = player()
        val teamId = assertNotNull(invite(leader, friend).team).id
        assertEquals(HttpStatusCode.OK, call(friend, "/team/invites/decline", Json.encodeToString(InviteAnswer(teamId))).status)
        assertTrue(state(friend).invites.isEmpty())
        assertEquals(TeamErrors.INVITE_NOT_FOUND, accept(friend, teamId).error())
    }

    @Test
    fun leadershipPassesOnAndOnlyLeaderKicks() = teamTest {
        val leader = player()
        val second = player()
        val third = player()
        team(leader, second, third)
        assertEquals(TeamErrors.NOT_LEADER, call(second, "/team/kick", Json.encodeToString(KickRequest(third.steamId))).error())
        assertEquals(HttpStatusCode.OK, call(leader, "/team/kick", Json.encodeToString(KickRequest(third.steamId))).status)
        assertNull(state(third).team)
        assertEquals(TeamErrors.NOT_IN_TEAM, call(leader, "/team/kick", Json.encodeToString(KickRequest(third.steamId))).error())

        assertEquals(HttpStatusCode.OK, call(leader, "/team/leave").status)
        assertEquals(second.steamId, state(second).team?.leaderId)
        call(second, "/team/leave")
        assertNull(state(second).team)
        assertEquals(TeamErrors.NOT_IN_TEAM, call(second, "/team/leave").error())
    }

    @Test
    fun deletedLeaderHandsTeamOver() = teamTest {
        val leader = player()
        val friend = player()
        team(leader, friend)
        assertEquals(HttpStatusCode.NoContent, call(leader, "/me", method = "DELETE").status)
        val team = assertNotNull(state(friend).team)
        assertEquals(friend.steamId, team.leaderId)
        assertEquals(listOf(friend.steamId), team.members.map { it.steamId })
    }

    @Test
    fun friendsAreSteamFriendsWhoUseRustopsy() {
        val ids = mutableListOf<Long>()
        val playing = SteamProfile("Fresh name", "https://avatars.steamstatic.com/f_full.jpg", FriendActivity.PLAYING_RUST)
        teamTest(fakeSteam(profile = playing, friends = { FriendList.Visible(ids) })) {
            val me = player()
            val teammate = player()
            val invited = player()
            team(me, teammate)
            invite(me, invited)
            ids += listOf(teammate.steamId, invited.steamId, nextSteamId())
            val response = Json.decodeFromString<FriendsResponse>(call(me, "/friends", method = "GET").bodyAsText())
            assertEquals(FriendsSource.STEAM, response.source)
            assertEquals(listOf(invited.steamId, teammate.steamId), response.friends.map { it.steamId })
            assertEquals(listOf(true, false), response.friends.map { it.invited })
            assertEquals(listOf(false, true), response.friends.map { it.teammate })
            assertTrue(response.friends.all { it.name == "Fresh name" && it.activity == FriendActivity.PLAYING_RUST })
        }
    }

    @Test
    fun privateFriendListIsReported() = teamTest(fakeSteam(friends = { FriendList.Private })) {
        val response = Json.decodeFromString<FriendsResponse>(call(player(), "/friends", method = "GET").bodyAsText())
        assertEquals(FriendsSource.PRIVATE, response.source)
        assertTrue(response.friends.isEmpty())
    }

    @Test
    fun liveChannelDeliversInvitesStateAndHits() = teamTest {
        val leader = player()
        val friend = player()
        val stranger = player()
        val hit = TeamHit(System.currentTimeMillis(), 76561198999999999, "rifle.ak", "riflebullet", "head", 41.5, 100.0, 49.0, "")

        live(friend) {
            assertNull(state(next()).team)
            val teamId = assertNotNull(invite(leader, friend).team).id
            assertEquals(teamId, state(next()).invites.single().teamId)
            accept(friend, teamId)
            assertEquals(2, state(next()).team?.members?.size)
            live(stranger) {
                state(next())
                live(leader) {
                    assertEquals(listOf(true, true), state(next()).team?.members?.map { it.online })
                    send(LiveMessage.Hits(listOf(hit, hit.copy(targetId = 5)), from = stranger.steamId))
                    send(LiveMessage.Ping(42))
                    assertEquals(42, assertIs<LiveMessage.Pong>(next()).clientTimeMs)
                }
            }
            assertEquals(listOf(true, true), state(next()).team?.members?.map { it.online })
            val relayed = assertIs<LiveMessage.Hits>(next())
            assertEquals(leader.steamId, relayed.from)
            assertEquals(listOf(hit), relayed.hits)
            assertEquals(listOf(false, true), state(next()).team?.members?.map { it.online })

            call(leader, "/team/kick", Json.encodeToString(KickRequest(friend.steamId)))
            assertNull(state(next()).team)
        }
    }

    @Test
    fun liveChannelRequiresSessionAndProtocol() = teamTest {
        assertFailsWith<IllegalStateException> {
            client.webSocket("/team/live", request = { header(PROTOCOL_HEADER, PROTOCOL_VERSION) }) {}
        }
        assertEquals(HttpStatusCode.UpgradeRequired, client.get("/team") { bearerAuth(player().token) }.status)
    }

    private suspend fun Env.live(player: Player, block: suspend DefaultClientWebSocketSession.() -> Unit) {
        client.webSocket("/team/live", request = {
            header(PROTOCOL_HEADER, PROTOCOL_VERSION)
            bearerAuth(player.token)
        }) { block() }
    }

    private fun state(message: LiveMessage): TeamState = assertIs<LiveMessage.State>(message).state

    private suspend fun DefaultClientWebSocketSession.next(): LiveMessage = withTimeout(5.seconds) {
        Json.decodeFromString<LiveMessage>((incoming.receive() as Frame.Text).readText())
    }

    private suspend fun DefaultClientWebSocketSession.send(message: LiveMessage) {
        outgoing.send(Frame.Text(Json.encodeToString(message)))
    }
}
