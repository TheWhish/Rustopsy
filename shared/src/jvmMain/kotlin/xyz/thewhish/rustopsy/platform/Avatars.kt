package xyz.thewhish.rustopsy.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Image
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Level

class Avatars(private val http: HttpClient) {
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > CAPACITY
    }

    suspend fun load(url: String): ImageBitmap? {
        val uri = steamAvatar(url) ?: return null
        mutex.withLock { cache[url] }?.let { return it }
        val image = try {
            val response = http.sendAsync(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).await()
            val bytes = response.body()
            if (response.statusCode() != 200 || bytes.size > MAX_BYTES) return null
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (e: IOException) {
            appLogger.log(Level.FINE, "Avatar download failed", e)
            return null
        } catch (e: IllegalArgumentException) {
            appLogger.log(Level.FINE, "Avatar image is unreadable", e)
            return null
        }
        mutex.withLock { cache[url] = image }
        return image
    }

    private fun steamAvatar(url: String): URI? = try {
        URI(url).takeIf { it.scheme == "https" && it.host?.endsWith(".steamstatic.com") == true }
    } catch (_: URISyntaxException) {
        null
    }

    private companion object {
        const val CAPACITY = 64
        const val MAX_BYTES = 512 * 1024
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
