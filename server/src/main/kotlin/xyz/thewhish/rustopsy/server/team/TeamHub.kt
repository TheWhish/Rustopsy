package xyz.thewhish.rustopsy.server.team

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.thewhish.rustopsy.core.sync.LiveMessage
import xyz.thewhish.rustopsy.core.sync.TeamHit
import xyz.thewhish.rustopsy.core.sync.TeamInfo
import xyz.thewhish.rustopsy.core.sync.TeamInvite
import xyz.thewhish.rustopsy.core.sync.TeamMember
import xyz.thewhish.rustopsy.core.sync.TeamState
import kotlin.math.abs

class LiveConnection(val steamId: Long) {
    val outbox = Channel<LiveMessage>(OUTBOX_CAPACITY)

    fun offer(message: LiveMessage) {
        if (outbox.trySend(message).isFailure) outbox.close()
    }

    private companion object {
        const val OUTBOX_CAPACITY = 64
    }
}

class TeamHub(private val store: TeamStore, private val clock: () -> Long = System::currentTimeMillis) {
    private class Recent(val receivedAtMs: Long, val message: LiveMessage.Hits)

    private val mutex = Mutex()
    private val connections = HashMap<Long, MutableSet<LiveConnection>>()
    private val states = HashMap<Long, StoredState>()
    private val recent = HashMap<Long, ArrayDeque<Recent>>()

    suspend fun connect(connection: LiveConnection) {
        val state = store.stateOf(connection.steamId)
        mutex.withLock {
            connections.getOrPut(connection.steamId) { mutableSetOf() } += connection
            states[connection.steamId] = state
            push(connection.steamId)
            state.team?.let { team ->
                teammates(team, connection.steamId).forEach(::push)
                recentFor(team.id, connection.steamId).forEach(connection::offer)
            }
        }
    }

    suspend fun disconnect(connection: LiveConnection) = mutex.withLock {
        val own = connections[connection.steamId] ?: return@withLock
        own -= connection
        if (own.isNotEmpty()) return@withLock
        connections -= connection.steamId
        val team = states.remove(connection.steamId)?.team ?: return@withLock
        val online = teammates(team, connection.steamId)
        online.forEach(::push)
        if (online.isEmpty()) recent -= team.id
    }

    suspend fun teamsChanged(teamIds: Collection<Long>, others: Collection<Long> = emptyList()) {
        val cached = mutex.withLock { states.filterValues { it.team?.id in teamIds }.keys.toList() }
        val current = teamIds.mapNotNull { store.team(it) }.flatMap { team -> team.members.map { it.steamId } }
        refresh(cached + current + others)
    }

    suspend fun refresh(users: Collection<Long>) {
        val online = mutex.withLock { users.distinct().filter { it in connections } }
        val loaded = online.associateWith { store.stateOf(it) }
        mutex.withLock {
            loaded.forEach { (steamId, state) ->
                if (steamId !in connections) return@forEach
                states[steamId] = state
                push(steamId)
            }
        }
    }

    suspend fun hits(from: LiveConnection, hits: List<TeamHit>) {
        val now = clock()
        val valid = hits.filter { it.isPlausible(now) }.take(MAX_HITS_PER_MESSAGE)
        if (valid.isEmpty()) return
        val message = LiveMessage.Hits(valid, from.steamId)
        mutex.withLock {
            val team = states[from.steamId]?.team ?: return@withLock
            val log = recent.getOrPut(team.id) { ArrayDeque() }
            log.addLast(Recent(now, message))
            prune(log, now)
            teammates(team, from.steamId).forEach { steamId -> connections[steamId]?.forEach { it.offer(message) } }
        }
    }

    suspend fun view(state: StoredState): TeamState = mutex.withLock { state.view() }

    suspend fun online(steamIds: Collection<Long>): Set<Long> = mutex.withLock { steamIds.filterTo(HashSet()) { it in connections } }

    private fun push(steamId: Long) {
        val state = states[steamId] ?: return
        val message = LiveMessage.State(state.view())
        connections[steamId]?.forEach { it.offer(message) }
    }

    private fun teammates(team: TeamRecord, self: Long): List<Long> =
        team.members.map { it.steamId }.filter { it != self && it in connections }

    private fun StoredState.view() = TeamState(
        team = team?.let { team ->
            TeamInfo(team.id, team.leaderId, team.members.map { TeamMember(it.steamId, it.name, it.avatarUrl, it.steamId in connections) })
        },
        invites = invites.map { TeamInvite(it.teamId, it.from, it.members) },
    )

    private fun recentFor(teamId: Long, viewer: Long): List<LiveMessage.Hits> {
        val log = recent[teamId] ?: return emptyList()
        prune(log, clock())
        return log.map { it.message }.filter { it.from != viewer }
    }

    private fun prune(log: ArrayDeque<Recent>, now: Long) {
        var total = log.sumOf { it.message.hits.size }
        while (log.isNotEmpty() && (now - log.first().receivedAtMs > RECENT_MS || total > MAX_RECENT_HITS)) {
            total -= log.removeFirst().message.hits.size
        }
    }

    private fun TeamHit.isPlausible(now: Long): Boolean =
        targetId in STEAM_ID_RANGE &&
            abs(timeMs - now) <= MAX_CLOCK_SKEW_MS &&
            weapon.length <= MAX_TEXT && ammo.length <= MAX_TEXT && info.length <= MAX_TEXT && area.length <= MAX_AREA &&
            listOfNotNull(oldHp, newHp).all { it in 0.0..MAX_HP } &&
            distanceM.let { it == null || it in 0.0..MAX_DISTANCE_M }

    companion object {
        val STEAM_ID_RANGE = 76561197960265728L..76561202255233023L
        private const val RECENT_MS = 15 * 60_000L
        private const val MAX_RECENT_HITS = 2_000
        private const val MAX_HITS_PER_MESSAGE = 200
        private const val MAX_CLOCK_SKEW_MS = 60 * 60_000L
        private const val MAX_TEXT = 128
        private const val MAX_AREA = 16
        private const val MAX_HP = 1_000.0
        private const val MAX_DISTANCE_M = 5_000.0
    }
}
