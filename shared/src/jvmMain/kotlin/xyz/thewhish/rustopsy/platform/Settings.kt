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
    val overlay: OverlaySettings = OverlaySettings(),
)

@Serializable
enum class OverlayCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

@Serializable
data class OverlaySettings(
    val enabled: Boolean = false,
    val corner: OverlayCorner = OverlayCorner.TOP_RIGHT,
    val offsetX: Int = 24,
    val offsetY: Int = 24,
    val scalePercent: Int = 100,
    val opacityPercent: Int = 85,
) {
    val x: Int get() = offsetX.coerceIn(0, MAX_OFFSET)
    val y: Int get() = offsetY.coerceIn(0, MAX_OFFSET)
    val scale: Float get() = scalePercent.coerceIn(90, 130) / 100f
    val opacity: Float get() = opacityPercent.coerceIn(70, 95) / 100f

    private companion object {
        const val MAX_OFFSET = 400
    }
}

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
