package xyz.thewhish.rustopsy.server

import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory
import xyz.thewhish.rustopsy.server.auth.steamWeb
import java.net.http.HttpClient
import java.time.Duration
import kotlin.system.exitProcess

private const val CONFIG_ERROR_EXIT = 2

fun main() {
    val config = try {
        ServerConfig.fromEnv()
    } catch (e: IllegalArgumentException) {
        System.err.println("Invalid configuration: ${e.message}")
        exitProcess(CONFIG_ERROR_EXIT)
    }
    if (config.steamApiKey == null) LoggerFactory.getLogger("Main").warn("STEAM_API_KEY is not set, Steam names and avatars are unavailable")
    val dataSource = connectDatabase(config.db)
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    val server = embeddedServer(CIO, host = config.host, port = config.port) {
        module(dataSource, config.publicUrl, steamWeb(config.steamApiKey, http))
    }
    server.monitor.subscribe(ApplicationStopped) {
        dataSource.close()
        http.close()
    }
    server.start(wait = true)
}
