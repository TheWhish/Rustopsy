package xyz.thewhish.rustopsy.binds

import java.io.IOException
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeysConfigFileTest {
    private val dir = createTempDirectory("rustopsy-keys")
    private val keys = dir.resolve("cfg").also { Files.createDirectories(it) }.resolve("keys.cfg")
    private val config = KeysConfigFile(keys, dir.resolve("backups")) { LocalDateTime.of(2026, 10, 15, 21, 17, 0) }

    @Test
    fun saveWritesFileAndBacksUpOriginal() {
        keys.writeText("bind w +forward\r\nbind e +use\r\n")

        val backup = assertNotNull(config.save(setOf("w"), setOf("w")))

        assertEquals("bind w +forward;combatlog\r\nbind e +use\r\n", keys.readText())
        assertEquals("bind w +forward\r\nbind e +use\r\n", backup.readText())
        assertEquals("keys-20261015-211700.cfg", backup.fileName.toString())
        assertFalse(Files.exists(keys.resolveSibling("keys.cfg.rustopsy-tmp")))
    }

    @Test
    fun unchangedSelectionWritesNothing() {
        keys.writeText("bind w +forward;combatlog\n")

        assertNull(config.save(setOf("w"), setOf("w")))
        assertFalse(Files.exists(dir.resolve("backups")))
    }

    @Test
    fun missingOrHugeFileIsRejected() {
        assertFailsWith<IOException> { config.commands() }
        keys.writeText("x".repeat(300 * 1024))
        assertFailsWith<IOException> { config.commands() }
    }

    @Test
    fun locatesKeysBesideGameLog() {
        keys.writeText("bind w +forward\n")

        assertEquals(keys, KeysConfigFile.besideGameLog(dir.resolve("output_log.txt"), dir)?.file)
        assertNull(KeysConfigFile.besideGameLog(dir.resolve("cfg").resolve("output_log.txt"), dir))
    }
}
