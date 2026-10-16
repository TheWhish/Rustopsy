package xyz.thewhish.rustopsy.ui.overlay

import xyz.thewhish.rustopsy.core.fight.Condition
import xyz.thewhish.rustopsy.core.fight.Fight
import xyz.thewhish.rustopsy.ui.radar.EnemySummary
import xyz.thewhish.rustopsy.ui.radar.summary

const val OVERLAY_SHOW_SEC = 60.0
const val OVERLAY_MAX_ROWS = 5

data class OverlayModel(val enemies: List<EnemySummary>, val more: Int, val dead: List<Int>)

fun overlayModel(fights: List<Fight>, nowSec: Double): OverlayModel? {
    val fight = fights.firstOrNull()?.takeIf { nowSec - it.endSec < OVERLAY_SHOW_SEC } ?: return null
    val (dead, standing) = fight.enemies.map { it.summary() }.sortedBy { it.number }.partition { it.condition == Condition.DEAD }
    return OverlayModel(
        enemies = standing.take(OVERLAY_MAX_ROWS),
        more = (standing.size - OVERLAY_MAX_ROWS).coerceAtLeast(0),
        dead = dead.map { it.number },
    )
}

fun sampleOverlay(): OverlayModel {
    fun enemy(number: Int, hp: Double, condition: Condition, distanceM: Double) =
        EnemySummary(number, null, distanceM, hp, condition, null, null, 0, 0.0, 0.0, 0.0, 0.0, 0, seenByYou = true)
    return OverlayModel(
        enemies = listOf(
            enemy(1, 12.4, Condition.ALIVE, 45.0),
            enemy(2, 6.0, Condition.KNOCKED, 30.0),
            enemy(3, 52.0, Condition.ALIVE, 80.0),
        ),
        more = 0,
        dead = listOf(4),
    )
}
