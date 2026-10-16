package xyz.thewhish.rustopsy.core.session

import xyz.thewhish.rustopsy.core.printedSec
import xyz.thewhish.rustopsy.core.log.CombatLogParser
import xyz.thewhish.rustopsy.core.log.ConsoleTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AimSeriesMergeTest {
    private val lines = assertNotNull(javaClass.getResource("/combatlogs/console-aim-series.txt")).readText().lines()

    private val snapshots = lines.indices
        .filter { lines[it].contains(" time ") }
        .let { headers -> headers.drop(1) + lines.size }
        .map { consoleEnd -> assertNotNull(CombatLogParser.parse(lines.subList(0, consoleEnd).joinToString("\n"))) }

    @Test
    fun nineGrowingSnapshotsGiveEightUniqueEvents() {
        var events = emptyList<CombatEvent>()
        snapshots.forEach { snapshot ->
            val result = SnapshotMerger.merge(events, snapshot, snapshot.printedSec())
            assertTrue(result.continuous)
            events = result.events
        }

        assertEquals(9, snapshots.size)
        assertEquals(8, events.size)
        assertEquals(events.map { it.row.target!!.id }.distinct(), events.map { it.row.target!!.id })
        assertEquals(events.sortedBy { it.timeSec }, events)
    }

    @Test
    fun hiddenEventsAppearInsideAnnouncedWindow() {
        val first = snapshots.first()
        val printed = first.printedSec()
        val window = assertNotNull(first.hiddenWindow(printed))
        val later = SnapshotMerger.merge(
            SnapshotMerger.merge(emptyList(), first, printed).events,
            snapshots[1],
            snapshots[1].printedSec(),
        )

        assertEquals(2, window.count)
        assertEquals(2, later.added.size)
        assertTrue(later.added.all { it.timeSec in (printed - 10.5)..window.untilSec })
    }

    @Test
    fun localSecondsMatchJavaTime() {
        listOf("10/15/2026 13:57:24", "02/29/2024 23:59:59", "01/01/1970 00:00:00", "03/01/2100 00:00:00").forEach { stamp ->
            val time = assertNotNull(ConsoleTime.parse(stamp))
            val expected = LocalDateTime.of(time.year, time.month, time.day, time.hour, time.minute, time.second).toEpochSecond(ZoneOffset.UTC)
            assertEquals(expected, time.toEpochSeconds(), stamp)
        }
    }
}
