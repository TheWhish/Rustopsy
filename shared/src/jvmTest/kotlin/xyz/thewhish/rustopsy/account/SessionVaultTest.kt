package xyz.thewhish.rustopsy.account

import xyz.thewhish.rustopsy.core.sync.Tokens
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SessionVaultTest {
    private val file = createTempDirectory("rustopsy-session").resolve("session.bin")
    private val vault = SessionVault(file)

    @Test
    fun savedTokenIsEncryptedAndReadBack() {
        val token = Tokens.random()
        vault.save(token)
        assertFalse(token in file.readBytes().decodeToString())
        assertEquals(token, vault.load())
    }

    @Test
    fun missingFileMeansNoSession() {
        assertNull(vault.load())
    }

    @Test
    fun corruptedFileIsReported() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertFailsWith<IOException> { vault.load() }
        file.writeBytes(ByteArray(0))
        assertFailsWith<IOException> { vault.load() }
    }

    @Test
    fun clearRemovesFile() {
        vault.save(Tokens.random())
        vault.clear()
        assertFalse(Files.exists(file))
        assertNull(vault.load())
    }
}
