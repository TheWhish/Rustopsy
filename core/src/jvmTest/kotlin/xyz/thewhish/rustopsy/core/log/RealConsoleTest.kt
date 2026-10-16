package xyz.thewhish.rustopsy.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealConsoleTest {
    private val console = assertNotNull(javaClass.getResource("/combatlogs/console-real-players.txt")).readText()

    @Test
    fun parsesRealPlayersTableFromConsoleCopy() {
        val snapshot = assertNotNull(CombatLogParser.parse(console))

        assertEquals(PrintTime.Local(ConsoleTime(2026, 10, 15, 13, 57, 24)), snapshot.printedAt)
        assertEquals(emptyList(), snapshot.issues)
        assertNull(snapshot.hidden)
        assertEquals(30, snapshot.rows.size)
        assertEquals(42.87, snapshot.rows.first().ageSec)
        assertEquals(12.53, snapshot.rows.last().ageSec)
    }

    @Test
    fun crlfGivesSameResult() {
        assertEquals(CombatLogParser.parse(console), CombatLogParser.parse(console.replace("\n", "\r\n")))
    }

    @Test
    fun incomingHitHasSteamIdAttackerAndEmptyInfo() {
        val row = CombatLogParser.parse(console)!!.rows.first()

        assertEquals(Party("player", 76561190000000001), row.attacker)
        assertTrue(row.target!!.isYou)
        assertEquals(HitArea.STOMACH, row.area)
        assertEquals(50.0, row.oldHp)
        assertEquals(25.0, row.newHp)
        assertEquals("", row.info)
        assertEquals(0, row.hits)
        assertEquals(11, row.desync)
    }

    @Test
    fun missWithoutTargetKeepsRowWithNulls() {
        val row = CombatLogParser.parse(console)!!.rows.first { it.info == "projectile_invalid" }

        assertTrue(row.attacker!!.isYou)
        assertNull(row.target)
        assertEquals("", row.weapon)
        assertEquals("", row.ammo)
        assertEquals(HitArea.GENERIC, row.area)
        assertEquals(140.3, row.distanceM)
        assertEquals(0.0, row.integrity)
    }

    @Test
    fun infoValuesAreCollected() {
        val infos = CombatLogParser.parse(console)!!.rows.map { it.info }.toSet()

        assertEquals(setOf("", "projectile_invalid", "projectile_network_range", "trajectory_start", "trajectory_end"), infos)
    }
}
