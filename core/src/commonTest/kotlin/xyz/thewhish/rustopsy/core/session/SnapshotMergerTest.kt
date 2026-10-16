package xyz.thewhish.rustopsy.core.session

import xyz.thewhish.rustopsy.core.log.CombatRow
import xyz.thewhish.rustopsy.core.log.HiddenEvents
import xyz.thewhish.rustopsy.core.log.Party
import xyz.thewhish.rustopsy.core.log.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotMergerTest {
    private fun row(ageSec: Double, oldHp: Double, newHp: Double, target: Long = 1) = CombatRow(
        ageSec = ageSec, attacker = Party("you", null), target = Party("player", target),
        weapon = "ak47u", ammo = "riflebullet", area = null, distanceM = 10.0,
        oldHp = oldHp, newHp = newHp, info = "", hits = 0, integrity = 1.0,
        travelSec = 0.02, mismatchM = 0.0, desync = 10, raw = "raw $ageSec",
    )

    private fun snapshot(vararg rows: CombatRow, hidden: HiddenEvents? = null) =
        Snapshot(printedAt = null, columns = emptyList(), rows = rows.toList(), hidden = hidden, issues = emptyList())

    @Test
    fun firstSnapshotAddsAllRowsWithAbsoluteTime() {
        val result = SnapshotMerger.merge(emptyList(), snapshot(row(5.0, 100.0, 80.0), row(2.0, 80.0, 60.0)), printedAtSec = 1_000.0)

        assertEquals(listOf(995.0, 998.0), result.events.map { it.timeSec })
        assertEquals(2, result.added.size)
        assertTrue(result.continuous)
    }

    @Test
    fun overlappingSnapshotAddsOnlyNewRows() {
        val first = SnapshotMerger.merge(emptyList(), snapshot(row(5.0, 100.0, 80.0)), printedAtSec = 1_000.0)
        val second = SnapshotMerger.merge(first.events, snapshot(row(9.6, 100.0, 80.0), row(1.0, 80.0, 60.0)), printedAtSec = 1_004.0)

        assertEquals(listOf(995.0, 1_003.0), second.events.map { it.timeSec })
        assertEquals(listOf(1_003.0), second.added.map { it.timeSec })
        assertTrue(second.continuous)
    }

    @Test
    fun identicalRowsInsideOneSnapshotAreKeptAsSeparateEvents() {
        val pellet = row(3.0, 0.0, 0.0)
        val first = SnapshotMerger.merge(emptyList(), snapshot(pellet, pellet), printedAtSec = 100.0)
        val second = SnapshotMerger.merge(first.events, snapshot(pellet.copy(ageSec = 5.0), pellet.copy(ageSec = 5.0), pellet.copy(ageSec = 5.0)), printedAtSec = 102.0)

        assertEquals(2, first.events.size)
        assertEquals(3, second.events.size)
        assertEquals(1, second.added.size)
    }

    @Test
    fun sameRowFarApartInTimeIsANewEvent() {
        val first = SnapshotMerger.merge(emptyList(), snapshot(row(5.0, 100.0, 80.0)), printedAtSec = 1_000.0)
        val second = SnapshotMerger.merge(first.events, snapshot(row(5.0, 100.0, 80.0)), printedAtSec = 1_010.0)

        assertEquals(2, second.events.size)
        assertFalse(second.continuous)
    }

    @Test
    fun hiddenWindowEndsDelayAfterPrint() {
        val snapshot = snapshot(hidden = HiddenEvents(count = 2, windowSec = 10))

        assertEquals(HiddenWindow(count = 2, untilSec = 1_010.0, windowSec = 10), snapshot.hiddenWindow(1_000.0))
        assertEquals(null, snapshot().hiddenWindow(1_000.0))
    }
}
