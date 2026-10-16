package xyz.thewhish.rustopsy.capture

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import xyz.thewhish.rustopsy.core.log.CombatLogParser
import xyz.thewhish.rustopsy.core.log.Snapshot
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

data class Capture(val snapshot: Snapshot, val capturedAt: Instant)

class ClipboardWatcher(
    private val changeCount: () -> Long = ::clipboardSequenceNumber,
    private val readText: () -> String? = ::readClipboardText,
    private val clock: Clock = Clock.System,
    private val pollInterval: Duration = 100.milliseconds,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun captures(): Flow<Capture> = flow {
        var seen = changeCount()
        while (true) {
            delay(pollInterval)
            val current = changeCount()
            if (current == seen) continue
            val capturedAt = clock.now()
            val text = try {
                readText()
            } catch (_: IllegalStateException) {
                continue
            }
            seen = current
            val snapshot = text?.let(CombatLogParser::parse) ?: continue
            emit(Capture(snapshot, capturedAt))
        }
    }.flowOn(dispatcher)
}
