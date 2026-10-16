package xyz.thewhish.rustopsy.core.sync

import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 1
const val PROTOCOL_HEADER = "X-Rustopsy-Protocol"

@Serializable
data class UserProfile(val steamId: Long, val name: String, val avatarUrl: String?)

@Serializable
data class TokenRequest(val code: String, val verifier: String)

@Serializable
data class SessionResponse(val token: String, val profile: UserProfile)

@Serializable
data class ApiError(val error: String)

object ApiErrors {
    const val UPDATE_REQUIRED = "update_required"
    const val INVALID_CODE = "invalid_code"
}

object LoginErrors {
    const val CANCELLED = "cancelled"
    const val REJECTED = "rejected"
    const val EXPIRED = "expired"
    const val UNAVAILABLE = "unavailable"
}
