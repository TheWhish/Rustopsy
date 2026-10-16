package xyz.thewhish.rustopsy.capture

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class LogFileTailerTest {
    private val file = Files.createTempDirectory("rustopsy-tail").resolve("output_log.txt")
    private val captures = mutableListOf<Capture>()

    private val header = "2026-10-15T10:24:31.874Z|0x5f18|time attacker target weapon ammo area distance old_hp new_hp info hits integrity travel mismatch desync"
    private val trace = "UnityEngine.Debug:Log(Object)"

    private fun row(age: String) = "$age you player_1 ak47u riflebullet chest 10.0m 0.0 100.0 projectile_los 0 1.00 0.02s 0.00m 10"

    private fun append(text: String) {
        Files.writeString(file, text, CREATE, APPEND)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.startTailer() {
        val tailer = LogFileTailer(
            file = file,
            clock = object : Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
            },
            pollInterval = 100.milliseconds,
            quietFlush = 400.milliseconds,
            timeSource = testScheduler.timeSource,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch { tailer.captures().toList(captures) }
        runCurrent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.wait(duration: Duration = 100.milliseconds) {
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun ignoresExistingContentAndEmitsAppendedTable() = runTest {
        append("$header\n${row("90.00s")}\n$trace\n\n")
        startTailer()

        append("$header\n${row("5.00s")}\n${row("3.00s")}\n$trace\n\n")
        wait()

        assertEquals(listOf(listOf(5.0, 3.0)), captures.map { capture -> capture.snapshot.rows.map { it.ageSec } })
    }

    @Test
    fun tableWrittenInPartsIsEmittedOnce() = runTest {
        startTailer()

        val second = row("3.00s")
        append("$header\n${row("5.00s")}\n${second.take(3)}")
        wait()
        append("${second.drop(3)}\n")
        wait()
        append("$trace\n")
        wait()
        wait(1_000.milliseconds)

        assertEquals(listOf(listOf(5.0, 3.0)), captures.map { capture -> capture.snapshot.rows.map { it.ageSec } })
    }

    @Test
    fun tableWithoutTerminatorIsFlushedAfterQuietPeriod() = runTest {
        startTailer()

        append("$header\n${row("5.00s")}\n")
        wait()
        assertEquals(emptyList(), captures)

        wait(500.milliseconds)
        assertEquals(1, captures.size)
    }

    @Test
    fun recreatedFileIsReadFromStart() = runTest {
        append("noise\n".repeat(1_000))
        startTailer()

        Files.writeString(file, "$header\n${row("5.00s")}\n$trace\n")
        wait()

        assertEquals(1, captures.size)
    }

    @Test
    fun recreatedLargerFileIsDetectedByItsHead() = runTest {
        append("2026-10-15T10:18:29.345Z|0x5f18|Input System module state changed\n")
        startTailer()

        Files.writeString(file, "2026-10-15T13:40:02.101Z|0x61a0|Input System module state changed\n" + "noise\n".repeat(50) + "$header\n${row("5.00s")}\n$trace\n")
        wait()

        assertEquals(listOf(listOf(5.0)), captures.map { capture -> capture.snapshot.rows.map { it.ageSec } })
    }

    @Test
    fun fileCreatedAfterStartIsReadFromStart() = runTest {
        startTailer()
        wait()

        append("$header\n${row("5.00s")}\n$trace\n")
        wait()

        assertEquals(1, captures.size)
    }

    @Test
    fun linesWithoutTableAreDropped() = runTest {
        startTailer()

        append("2026-10-15T10:24:31.874Z|0x5f18|graphics.fov: \"90\"\n")
        wait(1_000.milliseconds)

        assertEquals(emptyList(), captures)
    }
}
