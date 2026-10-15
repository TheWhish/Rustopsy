package xyz.thewhish.rustopsy.platform

import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsStoreTest {
    private val dir = Files.createTempDirectory("rustopsy-settings")
    private val file = dir.resolve("config.json")
    private val store = SettingsStore(file)

    @Test
    fun missingFileGivesDefaults() {
        assertEquals(Settings(), store.load())
    }

    @Test
    fun saveThenLoadRoundTrips() {
        store.save(Settings(debugLogging = true))
        assertEquals(Settings(debugLogging = true), store.load())
        assertEquals(listOf(file), dir.listDirectoryEntries())
    }

    @Test
    fun unknownKeysAreIgnored() {
        Files.writeString(file, """{"debugLogging": true, "future": 1}""")
        assertEquals(Settings(debugLogging = true), store.load())
    }

    @Test
    fun corruptedFileIsBackedUpAndDefaultsReturned() {
        Files.writeString(file, "{not json")
        assertEquals(Settings(), store.load())
        val entries = dir.listDirectoryEntries()
        assertEquals(1, entries.size)
        assertTrue(entries.single().fileName.toString().startsWith("config.json.broken-"))
        assertEquals("{not json", Files.readString(entries.single()))
    }
}
