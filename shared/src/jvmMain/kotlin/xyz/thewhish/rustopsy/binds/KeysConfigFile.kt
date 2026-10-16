package xyz.thewhish.rustopsy.binds

import xyz.thewhish.rustopsy.core.binds.KeyBinds
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class KeysConfigFile(val file: Path, private val backupDir: Path, private val now: () -> LocalDateTime = LocalDateTime::now) {
    fun commands(): Map<String, String> = KeyBinds.commands(read())

    fun save(selected: Set<String>, managed: Set<String>): Path? {
        val original = read()
        val updated = KeyBinds.withCombatlog(original, selected, managed)
        if (updated == original) return null

        Files.createDirectories(backupDir)
        val backup = backupDir.resolve("keys-${now().format(STAMP)}.cfg")
        Files.writeString(backup, original)
        val temp = file.resolveSibling("${file.fileName}.rustopsy-tmp")
        try {
            Files.writeString(temp, updated)
            Files.move(temp, file, REPLACE_EXISTING, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
        return backup
    }

    private fun read(): String {
        if (!Files.isRegularFile(file)) throw IOException("$file not found")
        val size = Files.size(file)
        if (size > MAX_BYTES) throw IOException("$file is too large: $size bytes")
        return Files.readString(file)
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024L
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun besideGameLog(gameLog: Path, backupDir: Path): KeysConfigFile? =
            gameLog.parent?.resolve("cfg")?.resolve("keys.cfg")
                ?.takeIf { Files.isRegularFile(it) }
                ?.let { KeysConfigFile(it, backupDir) }
    }
}
