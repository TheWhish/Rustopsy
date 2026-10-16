package xyz.thewhish.rustopsy.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerConfigTest {
    private val env = mapOf(
        "HOST" to "127.0.0.1",
        "PORT" to "8081",
        "PUBLIC_URL" to "https://rustopsy.thewhish.xyz/",
        "STEAM_API_KEY" to "0123456789ABCDEF0123456789ABCDEF",
        "DB_URL" to "jdbc:postgresql://127.0.0.1:5432/rustopsy",
        "DB_USER" to "rustopsy",
        "DB_PASSWORD" to "secret-value",
    )

    @Test
    fun readsAllVariables() {
        val config = ServerConfig.fromEnv(env)
        assertEquals("127.0.0.1", config.host)
        assertEquals(8081, config.port)
        assertEquals("https://rustopsy.thewhish.xyz", config.publicUrl)
        assertEquals("0123456789ABCDEF0123456789ABCDEF", config.steamApiKey)
        assertEquals("rustopsy", config.db.user)
        assertEquals("secret-value", config.db.password)
    }

    @Test
    fun steamApiKeyIsOptional() {
        assertNull(ServerConfig.fromEnv(env - "STEAM_API_KEY").steamApiKey)
    }

    @Test
    fun rejectsMissingOrBlankVariable() {
        val missing = assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env - "DB_PASSWORD") }
        assertTrue("DB_PASSWORD" in missing.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env + ("HOST" to "  ")) }
    }

    @Test
    fun rejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env + ("PORT" to "70000")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env + ("PORT" to "abc")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env + ("DB_URL" to "jdbc:mysql://host/db")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env + ("PUBLIC_URL" to "http://rustopsy.thewhish.xyz")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env + ("PUBLIC_URL" to "https://rustopsy.thewhish.xyz/api")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env + ("STEAM_API_KEY" to "not-a-key")) }
    }

    @Test
    fun keepsSecretsOutOfToString() {
        val text = ServerConfig.fromEnv(env).toString()
        assertFalse("secret-value" in text)
        assertFalse("0123456789ABCDEF" in text)
    }
}
