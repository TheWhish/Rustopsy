package xyz.thewhish.rustopsy.platform

import xyz.thewhish.rustopsy.ui.overlay.OverlayCorner
import xyz.thewhish.rustopsy.ui.overlay.OverlaySettings
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
        val settings = Settings(debugLogging = true, overlay = OverlaySettings(enabled = true, consented = true, corner = OverlayCorner.BOTTOM_LEFT, offsetX = 40))
        store.save(settings)
        assertEquals(settings, store.load())
        assertEquals(listOf(file), dir.listDirectoryEntries())
    }

    @Test
    fun handEditedOverlayValuesAreClamped() {
        Files.writeString(file, """{"overlay": {"offsetX": 9999, "offsetY": -5, "scalePercent": 300, "backgroundPercent": 140, "rowsPercent": 10}}""")
        val overlay = store.load().overlay
        assertEquals(400, overlay.x)
        assertEquals(0, overlay.y)
        assertEquals(1.3f, overlay.scale)
        assertEquals(100, overlay.background)
        assertEquals(30, overlay.rows)
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
