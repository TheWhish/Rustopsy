package xyz.thewhish.rustopsy.ui.overlay

import xyz.thewhish.rustopsy.core.fight.Condition
import xyz.thewhish.rustopsy.core.fight.Fight
import xyz.thewhish.rustopsy.ui.radar.EnemySummary
import xyz.thewhish.rustopsy.ui.radar.summary

const val OVERLAY_SHOW_SEC = 60.0
const val OVERLAY_MAX_ROWS = 5

data class OverlayModel(val enemies: List<EnemySummary>, val total: Int, val killed: Int, val lastHitSec: Double)

fun overlayModel(fights: List<Fight>, nowSec: Double): OverlayModel? {
    val fight = fights.firstOrNull()?.takeIf { nowSec - it.endSec < OVERLAY_SHOW_SEC } ?: return null
    val summaries = fight.enemies.map { it.summary() }
    return OverlayModel(
        enemies = summaries.filter { it.condition != Condition.DEAD }.sortedBy { it.number }.take(OVERLAY_MAX_ROWS),
        total = summaries.size,
        killed = summaries.count { it.condition == Condition.DEAD },
        lastHitSec = fight.endSec,
    )
}

fun sampleOverlay(nowSec: Double): OverlayModel {
    fun enemy(number: Int, hp: Double, condition: Condition, distanceM: Double) =
        EnemySummary(number, null, distanceM, hp, condition, null, null, 0, 0.0, 0.0, 0.0, 0.0, 0, seenByYou = true)
    return OverlayModel(
        enemies = listOf(
            enemy(1, 12.4, Condition.ALIVE, 45.0),
            enemy(2, 6.0, Condition.KNOCKED, 30.0),
            enemy(3, 100.0, Condition.ALIVE, 120.0),
        ),
        total = 4,
        killed = 1,
        lastHitSec = nowSec - SAMPLE_HIT_AGE_SEC,
    )
}

private const val SAMPLE_HIT_AGE_SEC = 12
