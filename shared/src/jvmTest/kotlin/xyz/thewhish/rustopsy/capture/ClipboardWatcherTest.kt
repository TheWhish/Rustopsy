package xyz.thewhish.rustopsy.capture

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ClipboardWatcherTest {
    private val table = """
        10/15/2026 13:57:24 time   attacker target       weapon ammo        area    distance old_hp new_hp info hits integrity travel mismatch desync
        10/15/2026 13:57:24 42.87s you      player_15374 ak47u  riflebullet stomach 9.0m     50.0   25.0        0    1.00      0.02s  0.00m    11
    """.trimIndent()

    private var count = 1L
    private var text: String? = table
    private var busy = false
    private val captures = mutableListOf<Capture>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.startWatcher() {
        val watcher = ClipboardWatcher(
            changeCount = { count },
            readText = { if (busy) throw IllegalStateException("Clipboard is busy") else text },
            clock = object : Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
            },
            pollInterval = 100.milliseconds,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch { watcher.captures().toList(captures) }
        runCurrent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.poll() {
        advanceTimeBy(100.milliseconds)
        runCurrent()
    }

    @Test
    fun ignoresContentPresentAtStart() = runTest {
        startWatcher()
        repeat(5) { poll() }

        assertEquals(emptyList(), captures)
    }

    @Test
    fun emitsCombatLogOncePerClipboardChange() = runTest {
        startWatcher()

        count = 2
        poll()
        repeat(5) { poll() }

        assertEquals(1, captures.size)
        assertEquals(1, captures.single().snapshot.rows.size)
        assertEquals(Instant.fromEpochMilliseconds(100), captures.single().capturedAt)
    }

    @Test
    fun skipsTextWithoutCombatLog() = runTest {
        startWatcher()

        count = 2
        text = "my password"
        poll()
        count = 3
        text = null
        poll()

        assertEquals(emptyList(), captures)
    }

    @Test
    fun retriesWhenClipboardIsBusy() = runTest {
        startWatcher()

        count = 2
        busy = true
        poll()
        assertEquals(emptyList(), captures)

        busy = false
        poll()
        assertEquals(1, captures.size)
    }
}
