package xyz.thewhish.rustopsy.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import xyz.thewhish.rustopsy.core.fight.Fight
import xyz.thewhish.rustopsy.core.fight.FightAnalyzer
import xyz.thewhish.rustopsy.team.TeamAccess
import xyz.thewhish.rustopsy.team.TeamStatus

data class TeamFights(val fights: List<Fight> = emptyList(), val names: Map<Long, String> = emptyMap())

fun teamFights(session: Flow<SessionState>, team: TeamAccess): Flow<TeamFights> {
    val members = team.status.map { (it as? TeamStatus.Ready)?.state?.team?.members.orEmpty() }.distinctUntilChanged()
    return combine(session.map { it.events }.distinctUntilChanged(), team.events, members) { own, shared, members ->
        val events = if (shared.isEmpty()) own else (own + shared).sortedBy { it.timeSec }
        TeamFights(
            fights = FightAnalyzer.analyze(events, members.mapTo(HashSet()) { it.steamId }).asReversed(),
            names = members.associate { it.steamId to it.name },
        )
    }
}
