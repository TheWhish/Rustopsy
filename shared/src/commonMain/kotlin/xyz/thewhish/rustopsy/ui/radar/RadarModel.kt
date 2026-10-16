package xyz.thewhish.rustopsy.ui.radar

import xyz.thewhish.rustopsy.core.fight.Actor
import xyz.thewhish.rustopsy.core.fight.Condition
import xyz.thewhish.rustopsy.core.fight.Enemy
import xyz.thewhish.rustopsy.core.fight.Fight
import xyz.thewhish.rustopsy.core.fight.Hit
import xyz.thewhish.rustopsy.core.fight.Outcome
import xyz.thewhish.rustopsy.core.fight.weaponName
import xyz.thewhish.rustopsy.core.log.HitArea

data class EnemySummary(
    val number: Int,
    val weapon: String?,
    val distanceM: Double?,
    val hp: Double?,
    val condition: Condition,
    val knockedBy: Actor?,
    val killedBy: Actor?,
    val deaths: Int,
    val dealt: Double,
    val team: Double,
    val others: Double,
    val healed: Double,
    val invalid: Int,
    val seenByYou: Boolean,
)

enum class EventKind { Hit, Team, Rejected, Incoming, Heal, OtherDamage, KnockedByOthers }

data class EventLine(
    val timeSec: Double,
    val kind: EventKind,
    val outcome: Outcome?,
    val weapon: String,
    val area: HitArea?,
    val distanceM: Double?,
    val hpBefore: Double?,
    val hpAfter: Double?,
    val amount: Double?,
    val code: String?,
    val teammate: String? = null,
    val count: Int = 1,
    val key: String = "",
)

private const val SHOT_WINDOW_SEC = 0.05
private const val TEAMMATE = "Тиммейт"
private val SHOT_KINDS = setOf(EventKind.Hit, EventKind.Team, EventKind.Incoming)

fun Enemy.summary(): EnemySummary {
    val current = lives.lastOrNull()
    return EnemySummary(
        number = number,
        weapon = weapons.firstOrNull(),
        distanceM = medianDistanceM,
        hp = current?.hp,
        condition = current?.condition ?: Condition.ALIVE,
        knockedBy = current?.knockedBy,
        killedBy = current?.killedBy,
        deaths = lives.count { it.condition == Condition.DEAD },
        dealt = lives.sumOf { it.dealtDamage },
        team = lives.sumOf { it.teamDamage.values.sum() },
        others = lives.sumOf { it.othersDamage },
        healed = lives.sumOf { it.healed },
        invalid = lives.sumOf { life -> life.rejected.values.sum() },
        seenByYou = seenByYou,
    )
}

fun Enemy.eventLines(names: Map<Long, String> = emptyMap()): List<EventLine> {
    val outgoing = lives.flatMap { it.hits }.map { hit ->
        val teammate = hit.teammate
        when {
            teammate != null -> hit.line(EventKind.Team).copy(teammate = names[teammate] ?: TEAMMATE)
            hit.outcome == Outcome.REJECTED -> hit.line(EventKind.Rejected)
            else -> hit.line(EventKind.Hit)
        }
    }.mergeShots()
    val received = incoming.map { it.line(EventKind.Incoming) }.mergeShots()
    val changes = lives.flatMap { it.hpChanges }.map { change ->
        val kind = when {
            change.knocked -> EventKind.KnockedByOthers
            change.delta > 0 -> EventKind.Heal
            else -> EventKind.OtherDamage
        }
        EventLine(change.timeSec, kind, null, "", null, null, null, null, change.delta, null)
    }
    return (outgoing + received + changes).sortedByDescending { it.timeSec }.withKeys()
}

private fun List<EventLine>.withKeys(): List<EventLine> {
    val seen = HashMap<String, Int>()
    return asReversed().map { line ->
        val base = "${line.timeSec}-${line.kind}"
        val ordinal = (seen[base] ?: 0) + 1
        seen[base] = ordinal
        line.copy(key = "$base-$ordinal")
    }.asReversed()
}

private fun List<EventLine>.mergeShots(): List<EventLine> {
    val merged = mutableListOf<EventLine>()
    for (line in this) {
        val shot = merged.lastOrNull()
        if (shot != null && line.kind in SHOT_KINDS && shot.kind == line.kind && shot.teammate == line.teammate && shot.weapon == line.weapon && line.timeSec - shot.timeSec <= SHOT_WINDOW_SEC) {
            merged[merged.lastIndex] = shot.copy(
                outcome = maxOf(shot.outcome, line.outcome, compareBy { it.weight }),
                area = shot.area.takeIf { it == line.area },
                hpAfter = line.hpAfter,
                amount = (shot.amount ?: 0.0) + (line.amount ?: 0.0),
                count = shot.count + 1,
            )
        } else {
            merged += line
        }
    }
    return merged
}

private val Outcome?.weight: Int
    get() = when (this) {
        Outcome.KILL, Outcome.FINISH -> 3
        Outcome.KNOCK -> 2
        Outcome.DOWNED -> 1
        Outcome.HIT, Outcome.REJECTED, null -> 0
    }

private fun Hit.line(kind: EventKind): EventLine {
    val row = event.row
    val rejected = outcome == Outcome.REJECTED
    return EventLine(
        timeSec = event.timeSec,
        kind = kind,
        outcome = outcome.takeUnless { rejected },
        weapon = weaponName(row.weapon),
        area = row.area,
        distanceM = row.distanceM,
        hpBefore = row.oldHp.takeUnless { rejected },
        hpAfter = hpAfter,
        amount = damage.takeUnless { rejected },
        code = row.info.takeIf { rejected },
    )
}

fun List<Enemy>.defaultSelection(): Int? = maxByOrNull { it.summary().dealt }?.number

data class FightTotals(val dealt: Double, val knocks: Int, val kills: Int, val validHits: Int, val headshots: Int)

fun Fight.totals(): FightTotals {
    val hits = enemies.flatMap { enemy -> enemy.lives.flatMap { it.hits } }.filter { it.outcome != Outcome.REJECTED && it.teammate == null }
    return FightTotals(
        dealt = hits.sumOf { it.damage },
        knocks = hits.count { it.outcome == Outcome.KNOCK },
        kills = hits.count { it.outcome == Outcome.KILL || it.outcome == Outcome.FINISH },
        validHits = hits.size,
        headshots = hits.count { it.event.row.area == HitArea.HEAD },
    )
}

data class Contribution(val actor: Actor, val damage: Double, val knocks: Int, val kills: Int)

fun Enemy.contributions(): List<Contribution> {
    val hits = lives.flatMap { it.hits }.filter { it.outcome != Outcome.REJECTED }
    if (hits.none { it.teammate != null }) return emptyList()
    return hits.groupBy { it.teammate }.map { (teammate, own) ->
        Contribution(
            actor = teammate?.let(Actor::Teammate) ?: Actor.You,
            damage = own.sumOf { it.damage },
            knocks = own.count { it.outcome == Outcome.KNOCK },
            kills = own.count { it.outcome == Outcome.KILL || it.outcome == Outcome.FINISH },
        )
    }.sortedByDescending { it.damage }
}
