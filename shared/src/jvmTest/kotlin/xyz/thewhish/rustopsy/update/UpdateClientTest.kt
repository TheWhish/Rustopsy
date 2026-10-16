package xyz.thewhish.rustopsy.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.Executors
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class UpdateClientTest {
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val msiBytes = Random(7).nextBytes(300_000)
    private val dir = Files.createTempDirectory("rustopsy-update")

    @Volatile private var manifest = ""
    @Volatile private var served = msiBytes
    @Volatile private var manifestDelayMs = 0L

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/latest.json") { exchange ->
            Thread.sleep(manifestDelayMs)
            val body = manifest.encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/releases/download/") { exchange ->
            val body = served
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val msiUrl = "$base/releases/download/v0.1.2-alpha/Rustopsy-0.1.2.msi"
    private val client = UpdateClient(
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
        manifest = URI.create("$base/latest.json"),
        msiPrefix = "$base/releases/download/",
        releaseKey = keys.public,
    )

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    @Test
    fun versionsCompareByNumbersOnly() {
        assertTrue(AppVersion.parse("0.1.10-alpha")!! > AppVersion.parse("0.1.9")!!)
        assertEquals(AppVersion(0, 1, 2), AppVersion.parse("0.1.2-alpha"))
        assertNull(AppVersion.parse("0.1"))
        assertNull(AppVersion.parse("0.1.2-"))
        assertNull(AppVersion.parse("dev"))
    }

    @Test
    fun newerSignedReleaseIsDownloaded() = runBlocking {
        publish()
        val update = assertNotNull(client.check(AppVersion(0, 1, 1)))
        var progress = 0L
        val msi = client.download(update, dir) { progress = it }
        assertContentEquals(msiBytes, msi.readBytes())
        assertEquals(msiBytes.size.toLong(), progress)
        assertEquals(listOf(msi), dir.listDirectoryEntries())
    }

    @Test
    fun sameVersionIsNotAnUpdate() = runBlocking {
        publish()
        assertNull(client.check(AppVersion(0, 1, 2)))
    }

    @Test
    fun fieldsChangedAfterSigningAreRejected() = runBlocking {
        publish(signedSize = msiBytes.size + 1L)
        assertNull(client.check(AppVersion(0, 1, 1)))
        publish(signedVersion = "0.1.3-alpha")
        assertNull(client.check(AppVersion(0, 1, 1)))
    }

    @Test
    fun msiOutsideOfReleasesIsRejected() = runBlocking {
        publish(msi = "https://example.com/Rustopsy-0.1.2.msi")
        assertNull(client.check(AppVersion(0, 1, 1)))
        publish(msi = "$base/releases/download/../../other/Rustopsy-0.1.2.msi")
        assertNull(client.check(AppVersion(0, 1, 1)))
    }

    @Test
    fun corruptedMsiIsDeleted() = runBlocking {
        publish()
        val update = assertNotNull(client.check(AppVersion(0, 1, 1)))
        served = msiBytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertFailsWith<IOException> { client.download(update, dir) {} }
        served = msiBytes + 0
        assertFailsWith<IOException> { client.download(update, dir) {} }
        assertEquals(emptyList(), dir.listDirectoryEntries())
    }

    @Test
    fun slowManifestDoesNotDelayLaunch() = runBlocking {
        publish()
        manifestDelayMs = 5_000
        val elapsed = measureTime { assertNull(client.check(AppVersion(0, 1, 1))) }
        assertTrue(elapsed < 4.seconds, "check took $elapsed")
    }

    @Test
    fun malformedManifestIsIgnored() = runBlocking {
        manifest = "<html>Not Found</html>"
        assertNull(client.check(AppVersion(0, 1, 1)))
    }

    private fun publish(msi: String = msiUrl, signedVersion: String = "0.1.2-alpha", signedSize: Long = msiBytes.size.toLong()) {
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(msiBytes))
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(signedText(signedVersion, signedSize, sha256).encodeToByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        manifest = """{"schema":1,"version":"0.1.2-alpha","msi":"$msi","size":${msiBytes.size},"sha256":"$sha256","signature":"$signature","notes":"ignored"}"""
    }
}
