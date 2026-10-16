package xyz.thewhish.rustopsy.server.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.thewhish.rustopsy.core.sync.FriendActivity
import xyz.thewhish.rustopsy.server.team.InviteTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SteamParsingTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun parsesPlayersWithActivity() {
        val players = parsePlayers(
            json(
                """{"response":{"players":[
                    {"steamid":"76561198000000001","personaname":" Whish ","avatarfull":"https://avatars.steamstatic.com/a_full.jpg","personastate":1,"gameid":"252490"},
                    {"steamid":"76561198000000002","personaname":"Online","personastate":3},
                    {"steamid":"76561198000000003","personaname":"Away","personastate":0,"avatarfull":"http://insecure"},
                    {"steamid":"76561198000000004","personaname":"  "},
                    {"personaname":"NoId"}
                ]}}""",
            ),
        ).toMap()
        assertEquals(setOf(76561198000000001, 76561198000000002, 76561198000000003), players.keys)
        assertEquals("Whish", players.getValue(76561198000000001).name)
        assertEquals(FriendActivity.PLAYING_RUST, players.getValue(76561198000000001).activity)
        assertEquals(FriendActivity.ONLINE, players.getValue(76561198000000002).activity)
        assertEquals(FriendActivity.OFFLINE, players.getValue(76561198000000003).activity)
        assertNull(players.getValue(76561198000000003).avatarUrl)
    }

    @Test
    fun parsesFriendsAndVanity() {
        assertEquals(
            listOf(76561198000000001, 76561198000000002),
            parseFriends(json("""{"friendslist":{"friends":[{"steamid":"76561198000000001"},{"steamid":"76561198000000002"},{"steamid":"x"}]}}""")),
        )
        assertEquals(emptyList(), parseFriends(json("{}")))
        assertEquals(76561198000000009, parseVanity(json("""{"response":{"steamid":"76561198000000009","success":1}}""")))
        assertNull(parseVanity(json("""{"response":{"success":42,"message":"No match"}}""")))
    }

    @Test
    fun readsInviteTargets() {
        assertEquals(InviteTarget.Id(76561198000000001), InviteTarget.parse(" 76561198000000001 "))
        assertEquals(InviteTarget.Id(76561198000000001), InviteTarget.parse("https://steamcommunity.com/profiles/76561198000000001/"))
        assertEquals(InviteTarget.Id(76561198000000001), InviteTarget.parse("steamcommunity.com/profiles/76561198000000001"))
        assertEquals(InviteTarget.Vanity("petya_99"), InviteTarget.parse("https://steamcommunity.com/id/petya_99/"))
        assertEquals(InviteTarget.Vanity("petya"), InviteTarget.parse("petya"))
        assertNull(InviteTarget.parse("https://evil.test/profiles/76561198000000001"))
        assertNull(InviteTarget.parse("a b"))
        assertNull(InviteTarget.parse(""))
    }
}
