package xyz.thewhish.rustopsy.core.log

import xyz.thewhish.rustopsy.core.printedSec
import xyz.thewhish.rustopsy.core.session.SnapshotMerger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OutputLogTest {
    private val log = assertNotNull(javaClass.getResource("/combatlogs/output-log-aim.txt")).readText()

    @Test
    fun parsesLastTableWithUtcMillisecondStamp() {
        val snapshot = assertNotNull(CombatLogParser.parse(log))

        assertEquals(PrintTime.Utc(Instant.parse("2026-10-15T10:24:36.570Z").toEpochMilli()), snapshot.printedAt)
        assertEquals(emptyList(), snapshot.issues)
        assertEquals(listOf(79.31, 70.07, 67.50), snapshot.rows.map { it.ageSec })
        assertEquals(HiddenEvents(1, 10), snapshot.hidden)
    }

    @Test
    fun tableIsIncompleteUntilALineAfterItArrives() {
        val lines = log.lines()
        val header = lines.indexOfFirst { it.startsWith("2026-10-15T10:24:36.570Z") }
        val partial = lines.take(header + 3).joinToString("\n", postfix = "\n")
        val withFooter = lines.take(header + 5).joinToString("\n", postfix = "\n")

        assertEquals(false, CombatLogParser.parse(partial)?.complete)
        assertEquals(2, CombatLogParser.parse(partial)?.rows?.size)
        assertEquals(true, CombatLogParser.parse(withFooter)?.complete)
        assertEquals(true, CombatLogParser.parse(log)?.complete)
    }

    @Test
    fun crlfGivesSameResult() {
        assertEquals(CombatLogParser.parse(log), CombatLogParser.parse(log.replace("\n", "\r\n")))
    }

    @Test
    fun consecutiveTablesMergeIntoSameEvents() {
        val lines = log.lines()
        val secondHeader = lines.indexOfFirst { it.startsWith("2026-10-15T10:24:36.570Z") }
        val first = assertNotNull(CombatLogParser.parse(lines.take(secondHeader).joinToString("\n")))
        val second = assertNotNull(CombatLogParser.parse(log))

        val merged = SnapshotMerger.merge(SnapshotMerger.merge(emptyList(), first, first.printedSec()).events, second, second.printedSec())

        assertEquals(3, merged.events.size)
        assertEquals(emptyList(), merged.added)
    }
}
