package xyz.thewhish.rustopsy.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.thewhish.rustopsy.server.auth.AuthStore
import xyz.thewhish.rustopsy.server.auth.Steam
import xyz.thewhish.rustopsy.server.auth.loginRoutes
import xyz.thewhish.rustopsy.server.auth.SESSION_AUTH
import xyz.thewhish.rustopsy.server.auth.accountRoutes
import xyz.thewhish.rustopsy.server.auth.tokenRoute
import xyz.thewhish.rustopsy.server.team.TeamHub
import xyz.thewhish.rustopsy.server.team.TeamStore
import xyz.thewhish.rustopsy.server.team.teamRoutes
import xyz.thewhish.rustopsy.server.auth.sessions
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val HEALTH_TIMEOUT_SEC = 2
private const val REQUESTS_PER_MINUTE = 60
private const val MAX_FRAME_BYTES = 64 * 1024L
private val API_LIMIT = RateLimitName("api")

fun Application.module(dataSource: DataSource, publicUrl: String, steam: Steam) {
    val store = AuthStore(dataSource)
    val teams = TeamStore(dataSource)
    val hub = TeamHub(teams)
    install(XForwardedHeaders)
    install(AutoHeadResponse)
    install(ContentNegotiation) { json() }
    install(RateLimit) {
        register(API_LIMIT) {
            rateLimiter(limit = REQUESTS_PER_MINUTE, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
    install(Authentication) { sessions(store) }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 30.seconds
        maxFrameSize = MAX_FRAME_BYTES
    }

    routing {
        get("/health") {
            val reachable = withContext(Dispatchers.IO) {
                try {
                    dataSource.connection.use { it.isValid(HEALTH_TIMEOUT_SEC) }
                } catch (e: SQLException) {
                    log.warn("Database check failed: {}", e.message)
                    false
                }
            }
            if (reachable) call.respondText("ok") else call.respondText("database unavailable", status = HttpStatusCode.ServiceUnavailable)
        }
        rateLimit(API_LIMIT) {
            loginRoutes(store, steam, publicUrl)
            route("/auth/token") {
                install(RequireProtocol)
                tokenRoute(store)
            }
            authenticate(SESSION_AUTH) {
                install(RequireProtocol)
                accountRoutes(store) { steamId -> teams.leave(steamId)?.let { hub.teamsChanged(listOf(it), listOf(steamId)) } }
                teamRoutes(teams, hub, store, steam)
            }
        }
    }
}
