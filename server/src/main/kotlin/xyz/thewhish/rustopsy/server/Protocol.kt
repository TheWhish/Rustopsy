package xyz.thewhish.rustopsy.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respond
import xyz.thewhish.rustopsy.core.sync.ApiError
import xyz.thewhish.rustopsy.core.sync.ApiErrors
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_HEADER
import xyz.thewhish.rustopsy.core.sync.PROTOCOL_VERSION

val RequireProtocol = createRouteScopedPlugin("RequireProtocol") {
    onCall { call ->
        if (call.request.headers[PROTOCOL_HEADER] != PROTOCOL_VERSION.toString()) {
            call.respond(HttpStatusCode.UpgradeRequired, ApiError(ApiErrors.UPDATE_REQUIRED))
        }
    }
}
