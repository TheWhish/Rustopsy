package xyz.thewhish.rustopsy.server.team

import xyz.thewhish.rustopsy.core.sync.TEAM_MAX_MEMBERS
import xyz.thewhish.rustopsy.core.sync.TeamErrors
import xyz.thewhish.rustopsy.core.sync.UserProfile
import xyz.thewhish.rustopsy.server.auth.SteamProfile
import xyz.thewhish.rustopsy.server.isUniqueViolation
import xyz.thewhish.rustopsy.server.queryList
import xyz.thewhish.rustopsy.server.queryOne
import xyz.thewhish.rustopsy.server.sql
import xyz.thewhish.rustopsy.server.transaction
import xyz.thewhish.rustopsy.server.update
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

class TeamRecord(val id: Long, val leaderId: Long, val members: List<UserProfile>)

class InviteRecord(val teamId: Long, val from: UserProfile, val members: Int)

class StoredState(val team: TeamRecord?, val invites: List<InviteRecord>)

class TeamException(val error: String) : Exception(error)

class Invited(val teamId: Long)

class Accepted(val teamId: Long, val previousTeamId: Long?)

class TeamStore(private val dataSource: DataSource) {
    suspend fun stateOf(steamId: Long): StoredState = dataSource.sql { connection ->
        StoredState(connection.teamIdOf(steamId)?.let { connection.record(it) }, connection.invitesFor(steamId))
    }

    suspend fun teamOf(steamId: Long): TeamRecord? = dataSource.sql { connection -> connection.teamIdOf(steamId)?.let { connection.record(it) } }

    suspend fun team(teamId: Long): TeamRecord? = dataSource.sql { connection -> connection.record(teamId) }

    suspend fun registered(steamIds: Collection<Long>): List<UserProfile> {
        if (steamIds.isEmpty()) return emptyList()
        return dataSource.sql { connection ->
            connection.queryList("SELECT steam_id, name, avatar_url FROM users WHERE steam_id = ANY(?)", {
                setArray(1, connection.createArrayOf("bigint", steamIds.toTypedArray()))
            }) { UserProfile(getLong(1), getString(2), getString(3)) }
        }
    }

    suspend fun refreshProfiles(profiles: Map<Long, SteamProfile>) {
        if (profiles.isEmpty()) return
        dataSource.sql { connection ->
            connection.prepareStatement("UPDATE users SET name = ?, avatar_url = ? WHERE steam_id = ?").use { statement ->
                profiles.forEach { (steamId, profile) ->
                    statement.setString(1, profile.name)
                    statement.setString(2, profile.avatarUrl)
                    statement.setLong(3, steamId)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    suspend fun invitedBy(teamId: Long): Set<Long> = dataSource.sql { connection ->
        connection.queryList("SELECT invitee_id FROM team_invites WHERE team_id = ? AND $INVITE_ALIVE", { setLong(1, teamId) }) { getLong(1) }.toSet()
    }

    suspend fun invite(inviterId: Long, inviteeId: Long): Invited = dataSource.transaction { connection ->
        if (inviterId == inviteeId) throw TeamException(TeamErrors.SELF)
        if (!connection.isRegistered(inviteeId)) throw TeamException(TeamErrors.PLAYER_NOT_REGISTERED)
        val existing = connection.teamIdOf(inviterId)
        val teamId = existing ?: connection.createTeam(inviterId)
        connection.lockTeams(listOf(teamId))
        if (connection.teamIdOf(inviterId) != teamId) throw TeamException(TeamErrors.NOT_IN_TEAM)
        if (connection.teamIdOf(inviteeId) == teamId) throw TeamException(TeamErrors.ALREADY_IN_TEAM)
        if (connection.size(teamId) >= TEAM_MAX_MEMBERS) throw TeamException(TeamErrors.FULL)
        connection.update("DELETE FROM team_invites WHERE NOT ($INVITE_ALIVE)")
        connection.update(
            "INSERT INTO team_invites (team_id, invitee_id, inviter_id) VALUES (?, ?, ?) " +
                "ON CONFLICT (team_id, invitee_id) DO UPDATE SET inviter_id = excluded.inviter_id, created_at = now()",
        ) {
            setLong(1, teamId)
            setLong(2, inviteeId)
            setLong(3, inviterId)
        }
        Invited(teamId)
    }

    suspend fun accept(steamId: Long, teamId: Long): Accepted = dataSource.transaction { connection ->
        val previous = connection.teamIdOf(steamId)
        connection.lockTeams(listOfNotNull(previous, teamId))
        if (connection.teamIdOf(steamId) != previous) throw TeamException(TeamErrors.ALREADY_IN_TEAM)
        val invited = connection.update("DELETE FROM team_invites WHERE team_id = ? AND invitee_id = ? AND $INVITE_ALIVE") {
            setLong(1, teamId)
            setLong(2, steamId)
        }
        if (invited == 0) throw TeamException(TeamErrors.INVITE_NOT_FOUND)
        if (previous == teamId) return@transaction Accepted(teamId, null)
        if (connection.size(teamId) >= TEAM_MAX_MEMBERS) throw TeamException(TeamErrors.FULL)
        if (previous != null) connection.removeMember(steamId, previous)
        connection.addMember(steamId, teamId)
        Accepted(teamId, previous)
    }

    suspend fun decline(steamId: Long, teamId: Long): Boolean = dataSource.sql { connection ->
        connection.update("DELETE FROM team_invites WHERE team_id = ? AND invitee_id = ?") {
            setLong(1, teamId)
            setLong(2, steamId)
        } > 0
    }

    suspend fun leave(steamId: Long): Long? = dataSource.transaction { connection ->
        val teamId = connection.teamIdOf(steamId) ?: return@transaction null
        connection.lockTeams(listOf(teamId))
        connection.removeMember(steamId, teamId)
        teamId
    }

    suspend fun kick(leaderId: Long, memberId: Long): Long = dataSource.transaction { connection ->
        val teamId = connection.teamIdOf(leaderId) ?: throw TeamException(TeamErrors.NOT_IN_TEAM)
        connection.lockTeams(listOf(teamId))
        val leader = connection.queryOne("SELECT leader_id FROM teams WHERE id = ?", { setLong(1, teamId) }) { getLong(1) }
        if (leader != leaderId) throw TeamException(TeamErrors.NOT_LEADER)
        if (memberId == leaderId || connection.teamIdOf(memberId) != teamId) throw TeamException(TeamErrors.NOT_IN_TEAM)
        connection.removeMember(memberId, teamId)
        teamId
    }

    private fun Connection.createTeam(leaderId: Long): Long {
        val teamId = checkNotNull(queryOne("INSERT INTO teams (leader_id) VALUES (?) RETURNING id", { setLong(1, leaderId) }) { getLong(1) })
        addMember(leaderId, teamId)
        return teamId
    }

    private fun Connection.addMember(steamId: Long, teamId: Long) {
        try {
            update("INSERT INTO team_members (steam_id, team_id) VALUES (?, ?)") {
                setLong(1, steamId)
                setLong(2, teamId)
            }
        } catch (e: SQLException) {
            if (e.isUniqueViolation) throw TeamException(TeamErrors.ALREADY_IN_TEAM)
            throw e
        }
    }

    private fun Connection.removeMember(steamId: Long, teamId: Long) {
        update("DELETE FROM team_members WHERE steam_id = ?") { setLong(1, steamId) }
        val successor = queryOne(
            "SELECT steam_id FROM team_members WHERE team_id = ? ORDER BY joined_at, steam_id LIMIT 1",
            { setLong(1, teamId) },
        ) { getLong(1) }
        if (successor == null) {
            update("DELETE FROM teams WHERE id = ?") { setLong(1, teamId) }
        } else {
            update("UPDATE teams SET leader_id = ? WHERE id = ? AND leader_id = ?") {
                setLong(1, successor)
                setLong(2, teamId)
                setLong(3, steamId)
            }
        }
    }

    private fun Connection.lockTeams(teamIds: List<Long>) {
        queryList("SELECT id FROM teams WHERE id = ANY(?) ORDER BY id FOR UPDATE", {
            setArray(1, createArrayOf("bigint", teamIds.distinct().toTypedArray()))
        }) { getLong(1) }
    }

    private fun Connection.teamIdOf(steamId: Long): Long? =
        queryOne("SELECT team_id FROM team_members WHERE steam_id = ?", { setLong(1, steamId) }) { getLong(1) }

    private fun Connection.isRegistered(steamId: Long): Boolean =
        queryOne("SELECT 1 FROM users WHERE steam_id = ?", { setLong(1, steamId) }) { true } == true

    private fun Connection.size(teamId: Long): Int =
        queryOne("SELECT count(*) FROM team_members WHERE team_id = ?", { setLong(1, teamId) }) { getInt(1) } ?: 0

    private fun Connection.record(teamId: Long): TeamRecord? {
        val leader = queryOne("SELECT leader_id FROM teams WHERE id = ?", { setLong(1, teamId) }) { getLong(1) } ?: return null
        val members = queryList(
            "SELECT u.steam_id, u.name, u.avatar_url FROM team_members m JOIN users u ON u.steam_id = m.steam_id " +
                "WHERE m.team_id = ? ORDER BY m.joined_at, u.steam_id",
            { setLong(1, teamId) },
        ) { UserProfile(getLong(1), getString(2), getString(3)) }
        return TeamRecord(teamId, leader, members)
    }

    private fun Connection.invitesFor(steamId: Long): List<InviteRecord> = queryList(
        "SELECT i.team_id, u.steam_id, u.name, u.avatar_url, (SELECT count(*) FROM team_members m WHERE m.team_id = i.team_id) " +
            "FROM team_invites i JOIN users u ON u.steam_id = i.inviter_id " +
            "WHERE i.invitee_id = ? AND i.$INVITE_ALIVE ORDER BY i.created_at DESC",
        { setLong(1, steamId) },
    ) { InviteRecord(getLong(1), UserProfile(getLong(2), getString(3), getString(4)), getInt(5)) }

    private companion object {
        const val INVITE_ALIVE = "created_at > now() - interval '1 hour'"
    }
}
