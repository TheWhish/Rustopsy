package xyz.thewhish.rustopsy.server.auth

import xyz.thewhish.rustopsy.core.sync.UserProfile
import xyz.thewhish.rustopsy.server.queryOne
import xyz.thewhish.rustopsy.server.sql
import xyz.thewhish.rustopsy.server.update
import javax.sql.DataSource

class PendingLogin(val state: String, val port: Int)

class RedeemedLogin(val steamId: Long, val codeChallenge: String)

class AuthStore(private val dataSource: DataSource) {
    suspend fun createLogin(id: String, state: String, codeChallenge: String, port: Int) = dataSource.sql { connection ->
        connection.update("DELETE FROM pending_logins WHERE expires_at < now()")
        connection.update("DELETE FROM sessions WHERE expires_at < now()")
        connection.update(
            "INSERT INTO pending_logins (id, state, code_challenge, port, expires_at) VALUES (?, ?, ?, ?, now() + interval '10 minutes')",
        ) {
            setString(1, id)
            setString(2, state)
            setString(3, codeChallenge)
            setInt(4, port)
        }
    }

    suspend fun pendingLogin(id: String): PendingLogin? = dataSource.sql { connection ->
        connection.queryOne("SELECT state, port FROM pending_logins WHERE id = ? AND steam_id IS NULL AND expires_at > now()", { setString(1, id) }) {
            PendingLogin(getString(1), getInt(2))
        }
    }

    suspend fun deleteLogin(id: String) = dataSource.sql { connection ->
        connection.update("DELETE FROM pending_logins WHERE id = ?") { setString(1, id) }
    }

    suspend fun completeLogin(id: String, steamId: Long, codeHash: ByteArray): Boolean = dataSource.sql { connection ->
        connection.update(
            "UPDATE pending_logins SET steam_id = ?, code_hash = ?, expires_at = now() + interval '1 minute' " +
                "WHERE id = ? AND steam_id IS NULL AND expires_at > now()",
        ) {
            setLong(1, steamId)
            setBytes(2, codeHash)
            setString(3, id)
        } == 1
    }

    suspend fun redeemCode(codeHash: ByteArray): RedeemedLogin? = dataSource.sql { connection ->
        connection.queryOne(
            "DELETE FROM pending_logins WHERE code_hash = ? RETURNING steam_id, code_challenge, expires_at > now()",
            { setBytes(1, codeHash) },
        ) {
            RedeemedLogin(getLong(1), getString(2)).takeIf { getBoolean(3) }
        }
    }

    suspend fun saveUser(steamId: Long, profile: SteamProfile?) = dataSource.sql { connection ->
        if (profile == null) {
            connection.update(
                "INSERT INTO users (steam_id, name) VALUES (?, ?) ON CONFLICT (steam_id) DO UPDATE SET last_seen_at = now()",
            ) {
                setLong(1, steamId)
                setString(2, steamId.toString())
            }
        } else {
            connection.update(
                "INSERT INTO users (steam_id, name, avatar_url) VALUES (?, ?, ?) ON CONFLICT (steam_id) " +
                    "DO UPDATE SET name = excluded.name, avatar_url = excluded.avatar_url, last_seen_at = now()",
            ) {
                setLong(1, steamId)
                setString(2, profile.name)
                setString(3, profile.avatarUrl)
            }
        }
    }

    suspend fun user(steamId: Long): UserProfile? = dataSource.sql { connection ->
        connection.queryOne("SELECT name, avatar_url FROM users WHERE steam_id = ?", { setLong(1, steamId) }) {
            UserProfile(steamId, getString(1), getString(2))
        }
    }

    suspend fun deleteUser(steamId: Long) = dataSource.sql { connection ->
        connection.update("DELETE FROM users WHERE steam_id = ?") { setLong(1, steamId) }
    }

    suspend fun createSession(tokenHash: ByteArray, steamId: Long) = dataSource.sql { connection ->
        connection.update("INSERT INTO sessions (token_hash, steam_id, expires_at) VALUES (?, ?, now() + interval '90 days')") {
            setBytes(1, tokenHash)
            setLong(2, steamId)
        }
    }

    suspend fun sessionOwner(tokenHash: ByteArray): Long? = dataSource.sql { connection ->
        connection.queryOne(
            "UPDATE sessions SET last_used_at = now(), expires_at = now() + interval '90 days' " +
                "WHERE token_hash = ? AND expires_at > now() RETURNING steam_id",
            { setBytes(1, tokenHash) },
        ) { getLong(1) }
    }

    suspend fun deleteSession(tokenHash: ByteArray) = dataSource.sql { connection ->
        connection.update("DELETE FROM sessions WHERE token_hash = ?") { setBytes(1, tokenHash) }
    }

    suspend fun deleteSessions(steamId: Long) = dataSource.sql { connection ->
        connection.update("DELETE FROM sessions WHERE steam_id = ?") { setLong(1, steamId) }
    }
}

