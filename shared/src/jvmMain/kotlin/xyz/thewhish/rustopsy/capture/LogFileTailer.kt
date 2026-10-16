package xyz.thewhish.rustopsy.capture

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import xyz.thewhish.rustopsy.core.log.CombatLogParser
import xyz.thewhish.rustopsy.platform.appLogger
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class LogFileTailer(
    private val file: Path,
    private val clock: Clock = Clock.System,
    private val pollInterval: Duration = 100.milliseconds,
    private val quietFlush: Duration = 400.milliseconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun captures(): Flow<Capture> = flow {
        var position = 0L
        var head: ByteArray? = null
        try {
            position = sizeOrNull() ?: 0L
            head = readHead()
        } catch (e: IOException) {
            appLogger.warning("Cannot read $file: $e")
        }
        val pending = StringBuilder()
        var lastAppend: TimeMark? = null
        var lastError: String? = null

        while (true) {
            delay(pollInterval)
            val chunk = try {
                val size = sizeOrNull() ?: continue
                val currentHead = readHead()
                val recreated = head?.let { !it.sharesPrefixWith(currentHead) } == true
                head = currentHead
                if (size < position || recreated) {
                    position = 0
                    pending.clear()
                }
                if (size > position) readCompleteLines(position, size) else null
            } catch (e: IOException) {
                if (e.toString() != lastError) appLogger.warning("Cannot read $file: $e")
                lastError = e.toString()
                continue
            }
            lastError = null
            if (chunk != null) {
                position += chunk.bytes
                pending.append(chunk.text)
                lastAppend = timeSource.markNow()
            }
            if (pending.isEmpty()) continue

            val snapshot = CombatLogParser.parse(pending.toString())
            val quiet = lastAppend?.let { it.elapsedNow() >= quietFlush } == true
            when {
                snapshot == null -> pending.clear()
                snapshot.complete || quiet -> {
                    pending.clear()
                    emit(Capture(snapshot, clock.now()))
                }
                pending.length > MAX_PENDING_CHARS -> pending.clear()
            }
        }
    }.flowOn(dispatcher)

    private class Chunk(val text: String, val bytes: Int)

    private fun sizeOrNull(): Long? = try {
        Files.size(file)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun readHead(): ByteArray {
        val buffer = ByteBuffer.allocate(HEAD_BYTES)
        FileChannel.open(file, READ).use { channel -> while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit }
        return buffer.array().copyOf(buffer.position())
    }

    private fun ByteArray.sharesPrefixWith(other: ByteArray): Boolean {
        val common = minOf(size, other.size)
        return (0 until common).all { this[it] == other[it] }
    }

    private fun readCompleteLines(position: Long, size: Long): Chunk? {
        val length = minOf(size - position, MAX_CHUNK_BYTES.toLong()).toInt()
        val buffer = ByteBuffer.allocate(length)
        FileChannel.open(file, READ).use { channel ->
            channel.position(position)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
        }
        val bytes = buffer.array()
        val end = (buffer.position() - 1 downTo 0).firstOrNull { bytes[it] == NEWLINE }
            ?: return if (length == MAX_CHUNK_BYTES) Chunk("", length) else null
        return Chunk(String(bytes, 0, end + 1, Charsets.UTF_8), end + 1)
    }

    private companion object {
        const val MAX_CHUNK_BYTES = 4 * 1024 * 1024
        const val HEAD_BYTES = 64
        const val MAX_PENDING_CHARS = 1_000_000
        const val NEWLINE = '\n'.code.toByte()
    }
}
