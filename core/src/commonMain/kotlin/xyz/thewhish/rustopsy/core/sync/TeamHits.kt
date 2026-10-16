package xyz.thewhish.rustopsy.core.sync

import xyz.thewhish.rustopsy.core.fight.isRejected
import xyz.thewhish.rustopsy.core.log.CombatRow
import xyz.thewhish.rustopsy.core.log.HitArea
import xyz.thewhish.rustopsy.core.log.Party
import xyz.thewhish.rustopsy.core.session.CombatEvent
import kotlin.math.roundToLong

private const val PLAYER = "player"

fun CombatEvent.toTeamHit(clockOffsetMs: Long): TeamHit? {
    val target = row.target ?: return null
    val shareable = teammate == null && row.attacker?.isYou == true && target.name == PLAYER && target.id in STEAM_ID_RANGE &&
        !row.isRejected && row.oldHp != null && row.newHp != null
    if (!shareable) return null
    return TeamHit(
        timeMs = (timeSec * 1_000).roundToLong() + clockOffsetMs,
        targetId = target.id!!,
        weapon = row.weapon,
        ammo = row.ammo,
        area = row.area?.name?.lowercase().orEmpty(),
        distanceM = null,
        oldHp = row.oldHp,
        newHp = row.newHp,
        info = row.info,
    )
}

fun TeamHit.toEvent(from: Long, clockOffsetMs: Long): CombatEvent = CombatEvent(
    timeSec = (timeMs - clockOffsetMs) / 1_000.0,
    row = CombatRow(
        ageSec = 0.0,
        attacker = Party(PLAYER, from),
        target = Party(PLAYER, targetId),
        weapon = weapon,
        ammo = ammo,
        area = HitArea.parse(area),
        distanceM = distanceM,
        oldHp = oldHp,
        newHp = newHp,
        info = info,
        hits = null,
        integrity = null,
        travelSec = null,
        mismatchM = null,
        desync = null,
        raw = "",
    ),
    teammate = from,
)
