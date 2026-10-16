package xyz.thewhish.rustopsy.capture

import xyz.thewhish.rustopsy.platform.appLogger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

object RustLogLocator {
    private const val LOG_FILE = "output_log.txt"
    private val LIBRARY_PATH = Regex(""""path"\s+"([^"]+)"""")
    private val STEAM_PATH = Regex("""SteamPath\s+REG_SZ\s+(.+)""")
    private val DEFAULT_STEAM = Paths.get("C:\\Program Files (x86)\\Steam")

    fun find(override: String?): Path? {
        if (override != null) return toPath(override)?.takeIf { Files.isRegularFile(it) && it.fileName.toString() == LOG_FILE }
        return steamLibraries()
            .map { it.resolve("steamapps").resolve("common").resolve("Rust").resolve(LOG_FILE) }
            .firstOrNull { Files.isRegularFile(it) }
    }

    fun steamLibraries(steam: Path? = steamPathFromRegistry() ?: DEFAULT_STEAM): List<Path> {
        if (steam == null) return emptyList()
        val folders = steam.resolve("steamapps").resolve("libraryfolders.vdf")
        val libraries = if (Files.isRegularFile(folders)) {
            LIBRARY_PATH.findAll(Files.readString(folders)).mapNotNull { toPath(it.groupValues[1].replace("\\\\", "\\")) }.toList()
        } else {
            emptyList()
        }
        return (listOf(steam) + libraries).distinct()
    }

    private fun steamPathFromRegistry(): Path? {
        val process = try {
            ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start()
        } catch (e: IOException) {
            appLogger.warning("Cannot query Steam path from registry: $e")
            return null
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            val output = process.inputStream.bufferedReader().readText()
            return STEAM_PATH.find(output)?.groupValues?.get(1)?.trim()?.let(::toPath)
        } finally {
            process.destroy()
        }
    }

    private fun toPath(raw: String): Path? = try {
        Paths.get(raw)
    } catch (_: InvalidPathException) {
        null
    }
}
