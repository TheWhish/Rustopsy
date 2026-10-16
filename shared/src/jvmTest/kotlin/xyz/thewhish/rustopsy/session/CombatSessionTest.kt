package xyz.thewhish.rustopsy.session

import xyz.thewhish.rustopsy.capture.Capture
import xyz.thewhish.rustopsy.core.log.CombatLogParser
import xyz.thewhish.rustopsy.core.log.ConsoleTime
import xyz.thewhish.rustopsy.core.session.HiddenWindow
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

class CombatSessionTest {
    private val columns = listOf(
        "time", "attacker", "target", "weapon", "ammo", "area", "distance", "old_hp", "new_hp",
        "info", "hits", "integrity", "travel", "mismatch", "desync",
    )

    private fun hit(age: String, target: String, oldHp: String, newHp: String) =
        listOf(age, "you", target, "ak47u", "riflebullet", "chest", "10.0m", oldHp, newHp, "", "0", "1.00", "0.02s", "0.00m", "10")

    private fun capture(stamp: String, vararg rows: List<String>, footer: String? = null, capturedAt: Instant = Instant.fromEpochSeconds(0)): Capture {
        val table = listOf(columns) + rows
        val widths = columns.indices.map { column -> table.maxOf { it[column].length } + 1 }
        val lines = table.map { cells -> cells.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("").trimEnd() } +
            listOfNotNull(footer)
        val text = lines.joinToString("\n") { "$stamp $it" }
        return Capture(assertNotNull(CombatLogParser.parse(text)), capturedAt)
    }

    private fun printedSec(stamp: String): Double = assertNotNull(ConsoleTime.parse(stamp)).toEpochSeconds() + 0.5

    private val session = CombatSession(ZoneOffset.UTC)

    @Test
    fun mergesOverlappingCapturesAndClearsHiddenWindow() {
        session.accept(capture("10/15/2026 13:24:31", hit("5.00s", "player_1", "100.0", "80.0"), footer = "+ 1 event in the last 10 seconds"))
        session.accept(capture("10/15/2026 13:24:36", hit("10.00s", "player_1", "100.0", "80.0"), hit("3.00s", "player_2", "100.0", "50.0")))

        val state = session.state.value
        assertEquals(listOf(1L, 2L), state.events.map { it.row.target?.id })
        assertEquals(null, state.hidden)
        assertEquals(emptyList(), state.gaps)
        assertEquals(printedSec("10/15/2026 13:24:36"), state.printedAtSec)
    }

    @Test
    fun keepsHiddenWindowFromLatestCapture() {
        session.accept(capture("10/15/2026 13:24:31", hit("5.00s", "player_1", "100.0", "80.0"), footer = "+ 2 events in the last 10 seconds"))

        assertEquals(HiddenWindow(2, printedSec("10/15/2026 13:24:31") + 10, 10), session.state.value.hidden)
    }

    @Test
    fun captureWithoutOverlapRecordsGap() {
        session.accept(capture("10/15/2026 13:24:31", hit("5.00s", "player_1", "100.0", "80.0")))
        session.accept(capture("10/15/2026 13:25:31", hit("4.00s", "player_2", "100.0", "60.0"), hit("2.00s", "player_2", "60.0", "20.0")))

        val gap = session.state.value.gaps.single()
        assertEquals(printedSec("10/15/2026 13:24:31") - 5, gap.fromSec)
        assertEquals(printedSec("10/15/2026 13:25:31") - 4, gap.untilSec)
        assertEquals(true, gap.overlaps(gap.fromSec - 10, gap.fromSec + 1))
        assertEquals(false, gap.overlaps(gap.untilSec + 1, gap.untilSec + 30))
    }

    @Test
    fun lagIsTimeBetweenPrintAndCopy() {
        session.accept(capture("10/15/2026 13:24:31", hit("5.00s", "player_1", "100.0", "80.0"), capturedAt = Instant.parse("2026-10-15T13:24:40Z")))

        assertEquals(8.5, session.state.value.lagSec)
    }

    @Test
    fun ignoresOlderTableCopiedLater() {
        session.accept(capture("10/15/2026 13:24:36", hit("10.00s", "player_1", "100.0", "80.0")))
        session.accept(capture("10/15/2026 13:24:31", hit("5.00s", "player_9", "100.0", "80.0")))

        assertEquals(listOf(1L), session.state.value.events.map { it.row.target?.id })
    }
}
