package xyz.thewhish.rustopsy.capture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RustLogLocatorTest {
    private val root = Files.createTempDirectory("rustopsy-steam")

    @Test
    fun readsLibraryFoldersFromSteam() {
        val steam = Files.createDirectories(root.resolve("Steam").resolve("steamapps")).parent
        val library = root.resolve("SteamLibrary")
        val escaped = library.toString().replace("\\", "\\\\")
        Files.writeString(
            steam.resolve("steamapps").resolve("libraryfolders.vdf"),
            "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"$escaped\"\n\t}\n}\n",
        )

        assertEquals(listOf(steam, library), RustLogLocator.steamLibraries(steam))
    }

    @Test
    fun missingLibraryFileGivesSteamFolderOnly() {
        assertEquals(listOf(root), RustLogLocator.steamLibraries(root))
    }

    @Test
    fun overrideMustBeExistingOutputLog() {
        val log = Files.writeString(root.resolve("output_log.txt"), "")
        val other = Files.writeString(root.resolve("notes.txt"), "")

        assertEquals(log, RustLogLocator.find(log.toString()))
        assertNull(RustLogLocator.find(other.toString()))
        assertNull(RustLogLocator.find(root.resolve("missing").resolve("output_log.txt").toString()))
    }
}
