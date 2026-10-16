package xyz.thewhish.rustopsy.platform

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Serializable
data class Settings(
    val debugLogging: Boolean = false,
    val gameLogPath: String? = null,
)

class SettingsStore(private val file: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(): Settings {
        if (Files.notExists(file)) return Settings()
        val text = Files.readString(file)
        return try {
            json.decodeFromString<Settings>(text)
        } catch (e: IllegalArgumentException) {
            val backup = file.resolveSibling("${file.fileName}.broken-${System.currentTimeMillis()}")
            Files.move(file, backup, REPLACE_EXISTING)
            appLogger.warning("Settings file is corrupted, moved to $backup, defaults used: ${e.message}")
            Settings()
        }
    }

    fun save(settings: Settings) {
        val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(tmp, json.encodeToString(settings))
            Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
