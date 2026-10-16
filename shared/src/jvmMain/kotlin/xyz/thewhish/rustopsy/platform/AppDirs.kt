package xyz.thewhish.rustopsy.platform

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class AppDirs(val root: Path) {
    val config: Path = root.resolve("config.json")
    val logs: Path = root.resolve("logs")
    val lock: Path = root.resolve(".lock")
    val backups: Path = root.resolve("backups")
    val session: Path = root.resolve("session.bin")

    fun create(): AppDirs {
        Files.createDirectories(logs)
        if (!Files.isWritable(root) || !Files.isWritable(logs)) {
            throw IOException("No write access to $root")
        }
        return this
    }

    companion object {
        fun default(): AppDirs {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            val base = appData?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.home"))
            return AppDirs(base.resolve("Rustopsy"))
        }
    }
}
