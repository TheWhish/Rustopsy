package xyz.thewhish.rustopsy.core.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Tokens {
    private const val BYTES = 32
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val pattern = Regex("[A-Za-z0-9_-]{43}")

    fun random(): String = encoder.encodeToString(ByteArray(BYTES).also(random::nextBytes))

    fun isValid(value: String): Boolean = pattern.matches(value)

    fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    fun challenge(verifier: String): String = encoder.encodeToString(sha256(verifier))

    fun matchesChallenge(verifier: String, challenge: String): Boolean =
        MessageDigest.isEqual(challenge(verifier).toByteArray(), challenge.toByteArray())
}
