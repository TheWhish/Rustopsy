package xyz.thewhish.rustopsy.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.thewhish.rustopsy.platform.appLogger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection.HTTP_OK
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private const val SCHEMA = 1
private const val MAX_MANIFEST_BYTES = 16 * 1024
private const val MAX_MSI_BYTES = 300L * 1024 * 1024
private const val BUFFER_BYTES = 64 * 1024
private const val RELEASE_KEY = "MCowBQYDK2VwAyEAaypvlCQ8XLKipcRug0a8FJ8XnWiA6mGg9f5WsNFBerI="
private val CHECK_TIMEOUT = 3.seconds
private val STALL_TIMEOUT = 15.seconds
private val SHA256 = Regex("[0-9a-f]{64}")

val RELEASE_MANIFEST: URI = URI.create("https://github.com/TheWhish/Rustopsy/releases/latest/download/latest.json")
const val RELEASE_MSI_PREFIX = "https://github.com/TheWhish/Rustopsy/releases/download/"

data class Update(val version: String, val msi: URI, val size: Long, val sha256: String)

@Serializable
private class Manifest(val schema: Int, val version: String, val msi: String, val size: Long, val sha256: String, val signature: String)

class UpdateClient(
    private val http: HttpClient,
    private val manifest: URI = RELEASE_MANIFEST,
    private val msiPrefix: String = RELEASE_MSI_PREFIX,
    private val releaseKey: PublicKey = decodeKey(RELEASE_KEY),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(current: AppVersion): Update? = try {
        withTimeout(CHECK_TIMEOUT) { verified(fetchManifest(), current) }
    } catch (_: TimeoutCancellationException) {
        appLogger.info("Update check timed out after $CHECK_TIMEOUT")
        null
    } catch (e: IOException) {
        appLogger.info("Update check failed: $e")
        null
    } catch (e: IllegalArgumentException) {
        appLogger.warning("Update manifest rejected: ${e.message}")
        null
    }

    suspend fun download(update: Update, dir: Path, onProgress: (Long) -> Unit): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(dir)
        val target = dir.resolve("Rustopsy-${update.version}.msi")
        val part = dir.resolve("${target.fileName}.part")
        try {
            val request = HttpRequest.newBuilder(update.msi).timeout(STALL_TIMEOUT.toJavaDuration()).GET().build()
            val response = http.sendAsync(request, BodyHandlers.ofInputStream()).await()
            response.body().use { body ->
                if (response.statusCode() != HTTP_OK) throw IOException("MSI request failed with HTTP ${response.statusCode()}")
                val sha256 = Files.newOutputStream(part).use { body.copyMeasured(it, update.size, onProgress) }
                if (sha256 != update.sha256) throw IOException("MSI checksum mismatch")
            }
            Files.move(part, target, REPLACE_EXISTING)
            target
        } finally {
            Files.deleteIfExists(part)
        }
    }

    private suspend fun fetchManifest(): String {
        val response = http.sendAsync(HttpRequest.newBuilder(manifest).GET().build(), BodyHandlers.ofInputStream()).await()
        return response.body().use { body ->
            if (response.statusCode() != HTTP_OK) throw IOException("Manifest request failed with HTTP ${response.statusCode()}")
            val bytes = runInterruptible(Dispatchers.IO) { body.readNBytes(MAX_MANIFEST_BYTES + 1) }
            require(bytes.size <= MAX_MANIFEST_BYTES) { "manifest is larger than $MAX_MANIFEST_BYTES bytes" }
            bytes.decodeToString()
        }
    }

    private fun verified(text: String, current: AppVersion): Update? {
        val manifest = json.decodeFromString<Manifest>(text)
        require(manifest.schema == SCHEMA) { "unsupported schema ${manifest.schema}" }
        val version = requireNotNull(AppVersion.parse(manifest.version)) { "malformed version ${manifest.version}" }
        require(manifest.size in 1..MAX_MSI_BYTES) { "size ${manifest.size} is out of range" }
        require(manifest.sha256.matches(SHA256)) { "malformed sha256" }
        val msi = URI.create(manifest.msi).normalize()
        require(msi.toString().startsWith(msiPrefix) && msi.path.endsWith(".msi")) { "MSI is outside of the releases: ${manifest.msi}" }
        require(isSigned(manifest)) { "signature does not match version ${manifest.version}" }
        if (version <= current) return null
        return Update(manifest.version, msi, manifest.size, manifest.sha256)
    }

    private fun isSigned(manifest: Manifest): Boolean = try {
        Signature.getInstance("Ed25519").run {
            initVerify(releaseKey)
            update(signedText(manifest.version, manifest.size, manifest.sha256).encodeToByteArray())
            verify(Base64.getDecoder().decode(manifest.signature))
        }
    } catch (_: SignatureException) {
        false
    }
}

internal fun signedText(version: String, size: Long, sha256: String): String = "rustopsy-update:$SCHEMA:$version:$size:$sha256"

fun clearDownloads(dir: Path) {
    if (Files.isDirectory(dir)) dir.listDirectoryEntries().forEach(Files::deleteIfExists)
}

private fun decodeKey(x509: String): PublicKey =
    KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(x509)))

private suspend fun InputStream.copyMeasured(out: OutputStream, size: Long, onProgress: (Long) -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(BUFFER_BYTES)
    var received = 0L
    while (true) {
        val count = withTimeoutOrNull(STALL_TIMEOUT) { runInterruptible { read(buffer) } }
            ?: throw IOException("No MSI data for $STALL_TIMEOUT")
        if (count < 0) break
        received += count
        if (received > size) throw IOException("MSI is larger than $size bytes")
        digest.update(buffer, 0, count)
        out.write(buffer, 0, count)
        onProgress(received)
    }
    if (received != size) throw IOException("MSI is $received bytes, expected $size")
    return HexFormat.of().formatHex(digest.digest())
}
