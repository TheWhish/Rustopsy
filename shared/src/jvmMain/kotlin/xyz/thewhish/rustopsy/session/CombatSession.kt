package xyz.thewhish.rustopsy.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import xyz.thewhish.rustopsy.capture.Capture
import xyz.thewhish.rustopsy.core.log.PrintTime
import xyz.thewhish.rustopsy.core.session.CombatEvent
import xyz.thewhish.rustopsy.core.session.SnapshotMerger
import xyz.thewhish.rustopsy.core.session.hiddenWindow
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Instant

class CombatSession(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    fun accept(capture: Capture): List<CombatEvent> {
        val captured = capture.capturedAt.epochSec()
        val printed = capture.snapshot.printedAt?.epochSec() ?: captured
        var added = emptyList<CombatEvent>()
        mutableState.update { current ->
            added = emptyList()
            if (current.printedAtSec != null && printed < current.printedAtSec) return@update current.copy(capturedAtSec = captured)
            val result = SnapshotMerger.merge(current.events, capture.snapshot, printed)
            added = result.added
            SessionState(
                events = result.events,
                hidden = capture.snapshot.hiddenWindow(printed),
                printedAtSec = printed,
                capturedAtSec = captured,
                gaps = if (result.continuous) current.gaps else current.gaps + gap(current, capture, printed),
            )
        }
        return added
    }

    private fun gap(current: SessionState, capture: Capture, printedSec: Double) = DataGap(
        fromSec = current.events.last().timeSec,
        untilSec = printedSec - capture.snapshot.rows.maxOf { it.ageSec },
    )

    private fun Instant.epochSec(): Double = epochSeconds + nanosecondsOfSecond / 1e9

    private fun PrintTime.epochSec(): Double = when (this) {
        is PrintTime.Utc -> epochMillis / 1_000.0
        is PrintTime.Local -> LocalDateTime.of(time.year, time.month, time.day, time.hour, time.minute, time.second)
            .atZone(zone)
            .toEpochSecond() + 0.5
    }
}
