package xyz.thewhish.rustopsy.core.fight

import xyz.thewhish.rustopsy.core.printedSec
import xyz.thewhish.rustopsy.core.log.CombatLogParser
import xyz.thewhish.rustopsy.core.log.Party
import xyz.thewhish.rustopsy.core.session.SnapshotMerger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealFightTest {
    private val fights = run {
        val console = assertNotNull(javaClass.getResource("/combatlogs/console-real-players.txt")).readText()
        val snapshot = assertNotNull(CombatLogParser.parse(console))
        val events = SnapshotMerger.merge(emptyList(), snapshot, snapshot.printedSec()).events
        FightAnalyzer.analyze(events)
    }

    private fun player(n: Int) = Party("player", 76561190000000000 + n)

    @Test
    fun yourDeathSplitsTableIntoTwoFights() {
        assertEquals(2, fights.size)
        assertTrue((fights[0].you == Condition.DEAD))
        assertFalse((fights[1].you == Condition.DEAD))
        assertEquals(50.0, fights[0].damageTaken, 1e-9)
        assertEquals(146.6, fights[1].damageTaken, 1e-9)
    }

    @Test
    fun firstFightHasOneWoundedEnemy() {
        val enemy = fights[0].enemies.single()

        assertEquals(1, enemy.number)
        assertEquals(player(1), enemy.party)
        assertEquals(listOf("ak47u"), enemy.weapons)
        assertEquals(3, enemy.incoming.size)
        val life = enemy.lives.single()
        assertEquals(17.5, life.dealtDamage, 1e-9)
        assertEquals(82.5, life.hp)
        assertFalse((life.condition == Condition.DEAD))
    }

    @Test
    fun secondFightNumbersEnemiesFromOne() {
        assertEquals(listOf(player(4), player(2), player(1), player(3)), fights[1].enemies.map { it.party })
        assertEquals(listOf(1, 2, 3, 4), fights[1].enemies.map { it.number })
    }

    @Test
    fun enemyKilledTwiceHasTwoLivesAndRejectedTail() {
        val enemy = fights[1].enemies.first { it.party == player(4) }

        assertEquals(2, enemy.lives.size)
        assertTrue(enemy.lives.all { (it.condition == Condition.DEAD) && it.hp == 0.0 })
        assertEquals(100.0, enemy.lives[0].dealtDamage, 1e-9)
        assertEquals(100.0, enemy.lives[1].dealtDamage, 1e-9)
        assertEquals(mapOf("projectile_network_range" to 1), enemy.lives[1].rejected)
        assertEquals(5, enemy.lives[1].hits.size)
    }

    @Test
    fun smallHpDriftIsNotReportedAsHealing() {
        val enemy = fights[1].enemies.first { it.party == player(2) }
        val life = enemy.lives.single()

        assertEquals(99.3, life.dealtDamage, 1e-9)
        assertEquals(emptyList(), life.hpChanges)
        assertEquals(2, enemy.incoming.size)
    }
}
