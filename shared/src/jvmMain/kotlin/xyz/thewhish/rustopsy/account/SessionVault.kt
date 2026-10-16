package xyz.thewhish.rustopsy.account

import xyz.thewhish.rustopsy.core.sync.Tokens
import xyz.thewhish.rustopsy.platform.Dpapi
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class SessionVault(private val file: Path) {
    fun load(): String? {
        if (!Files.exists(file)) return null
        if (Files.size(file) !in 1..MAX_SIZE) throw IOException("Session file is corrupted")
        val token = Dpapi.unprotect(Files.readAllBytes(file)).decodeToString()
        if (!Tokens.isValid(token)) throw IOException("Session file is corrupted")
        return token
    }

    fun save(token: String) {
        require(Tokens.isValid(token)) { "Invalid session token" }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.write(temp, Dpapi.protect(token.encodeToByteArray()))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun clear() {
        Files.deleteIfExists(file)
    }

    private companion object {
        const val MAX_SIZE = 4096L
    }
}
