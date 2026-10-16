package xyz.thewhish.rustopsy.server

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import xyz.thewhish.rustopsy.server.auth.FriendList
import xyz.thewhish.rustopsy.server.auth.Steam
import xyz.thewhish.rustopsy.server.auth.SteamProfile
import java.util.concurrent.atomic.AtomicLong

const val TEST_PUBLIC_URL = "https://rustopsy.test"

object TestDatabase {
    private val postgres: EmbeddedPostgres by lazy {
        EmbeddedPostgres.start().also { Runtime.getRuntime().addShutdownHook(Thread(it::close)) }
    }

    fun config() = DbConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres")
}

fun fakeSteam(
    verify: suspend (Map<String, String>) -> Boolean = { true },
    profile: SteamProfile? = SteamProfile("Whish", "https://avatars.steamstatic.com/whish_full.jpg"),
    friends: suspend (Long) -> FriendList = { FriendList.Visible(emptyList()) },
    vanity: Map<String, Long> = emptyMap(),
) = Steam(
    verifyAssertion = verify,
    profiles = { steamIds -> if (profile == null) emptyMap() else steamIds.associateWith { profile } },
    friends = friends,
    resolveVanity = { vanity[it] },
)

private val steamIds = AtomicLong(76561198100000000)

fun nextSteamId(): Long = steamIds.incrementAndGet()
