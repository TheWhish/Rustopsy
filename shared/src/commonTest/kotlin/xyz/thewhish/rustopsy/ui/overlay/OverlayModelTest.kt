package xyz.thewhish.rustopsy.ui.overlay

import xyz.thewhish.rustopsy.core.fight.Condition
import xyz.thewhish.rustopsy.core.fight.Fight
import xyz.thewhish.rustopsy.core.fight.FightAnalyzer
import xyz.thewhish.rustopsy.core.fight.KILLED
import xyz.thewhish.rustopsy.core.fight.WOUNDED
import xyz.thewhish.rustopsy.core.log.CombatRow
import xyz.thewhish.rustopsy.core.log.HitArea
import xyz.thewhish.rustopsy.core.log.Party
import xyz.thewhish.rustopsy.core.session.CombatEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayModelTest {
    private val you: Party = Party("you", null)
    private val mate: Long = 76561198000000002L

    private fun row(attacker: Party, target: Long, oldHp: Double, newHp: Double, info: String): CombatRow = CombatRow(
        ageSec = 0.0, attacker = attacker, target = Party("player", target),
        weapon = "assets/prefabs/weapons/ak47u/ak47u.entity.prefab", ammo = "riflebullet", area = HitArea.CHEST,
        distanceM = 20.0, oldHp = oldHp, newHp = newHp, info = info, hits = 0, integrity = 1.0,
        travelSec = 0.05, mismatchM = 0.0, desync = 10, raw = "",
    )

    private fun mine(timeSec: Double, target: Long, oldHp: Double, newHp: Double, info: String = ""): CombatEvent =
        CombatEvent(timeSec, row(you, target, oldHp, newHp, info))

    private fun theirs(timeSec: Double, target: Long, oldHp: Double, newHp: Double): CombatEvent =
        CombatEvent(timeSec, row(Party("player", mate), target, oldHp, newHp, ""), teammate = mate)

    private fun fights(vararg events: CombatEvent): List<Fight> =
        FightAnalyzer.analyze(events.sortedBy { it.timeSec }, setOf(mate)).asReversed()

    @Test
    fun recentFightIsShownForAMinuteAfterTheLastHit() {
        val fights = fights(mine(10.0, 7, 100.0, 60.0))

        val enemy = assertNotNull(overlayModel(fights, 10.0 + OVERLAY_SHOW_SEC - 0.1)).enemies.single()
        assertEquals(60.0, enemy.hp)
        assertEquals(20.0, enemy.distanceM)
        assertNull(overlayModel(fights, 10.0 + OVERLAY_SHOW_SEC))
        assertNull(overlayModel(emptyList(), 0.0))
    }

    @Test
    fun deadAreCollapsedAndRowsAreLimited() {
        val events = (1L..7L).map { mine(it * 0.1, it, 100.0, 70.0) } +
            listOf(mine(1.0, 2, 70.0, 9.0, WOUNDED), mine(1.1, 2, 9.0, 4.0, KILLED))
        val fight = fights(*events.toTypedArray()).single()
        val deadNumber = fight.enemies.single { it.party.id == 2L }.number

        val model = assertNotNull(overlayModel(listOf(fight), 2.0))

        assertEquals(7, model.total)
        assertEquals(1, model.killed)
        assertEquals(fight.endSec, model.lastHitSec)
        assertEquals(OVERLAY_MAX_ROWS, model.enemies.size)
        val numbers = model.enemies.map { it.number }
        assertEquals(numbers.sorted(), numbers)
        assertTrue(deadNumber !in numbers)
    }

    @Test
    fun knockedEnemyStaysInTheList() {
        val fights = fights(mine(0.0, 7, 100.0, 40.0), mine(1.0, 7, 40.0, 9.0, WOUNDED))

        val model = assertNotNull(overlayModel(fights, 2.0))

        assertEquals(Condition.KNOCKED, model.enemies.single().condition)
        assertEquals(0, model.killed)
    }

    @Test
    fun enemySeenOnlyByTeamIsMarked() {
        val fights = fights(mine(0.0, 7, 100.0, 60.0), theirs(1.0, 9, 100.0, 70.0))

        val enemies = assertNotNull(overlayModel(fights, 2.0)).enemies

        assertEquals(listOf(true, false), enemies.map { it.seenByYou })
    }
}
