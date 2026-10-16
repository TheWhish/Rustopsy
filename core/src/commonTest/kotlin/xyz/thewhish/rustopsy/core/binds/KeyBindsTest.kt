package xyz.thewhish.rustopsy.core.binds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeyBindsTest {
    private val cfg = listOf(
        "bind space +jump",
        "bind a +left;combatlog",
        "bind w +forward",
        "bind f2 combatlog",
        "bind [leftctrl+1] swaptoseat 0",
        "bind mouse0 +attack;+accessibility.holosightcolour 2;accessibility.holosightcolour 0",
        "",
    ).joinToString("\r\n")

    private val managed = setOf("space", "a", "w", "f2", "mouse0", "leftshift")

    @Test
    fun readsSimpleKeysAndSkipsCombos() {
        val commands = KeyBinds.commands(cfg)

        assertEquals("+forward", commands["w"])
        assertEquals("+left;combatlog", commands["a"])
        assertEquals(5, commands.size)
    }

    @Test
    fun addsAndRemovesOnlyForManagedKeys() {
        val updated = KeyBinds.withCombatlog(cfg, selected = setOf("w", "mouse0", "leftshift"), managed = managed)

        assertEquals(
            listOf(
                "bind space +jump",
                "bind a +left",
                "bind w +forward;combatlog",
                "bind [leftctrl+1] swaptoseat 0",
                "bind mouse0 +attack;+accessibility.holosightcolour 2;accessibility.holosightcolour 0;combatlog",
                "bind leftshift combatlog",
                "",
            ).joinToString("\r\n"),
            updated,
        )
    }

    @Test
    fun unchangedSelectionKeepsFileByteForByte() {
        val current = KeyBinds.commands(cfg).filterValues(KeyBinds::hasCombatlog).keys

        assertEquals(cfg, KeyBinds.withCombatlog(cfg, current, managed))
    }

    @Test
    fun keepsQuotesAndUnmanagedLines() {
        val updated = KeyBinds.withCombatlog("bind w \"+forward\"\nbind e +use\n", setOf("w"), setOf("w"))

        assertEquals("bind w \"+forward;combatlog\"\nbind e +use\n", updated)
    }

    @Test
    fun rejectsUnsafeKeyNames() {
        assertFailsWith<IllegalArgumentException> { KeyBinds.withCombatlog(cfg, setOf("w\nbind x quit"), setOf("w\nbind x quit")) }
        assertFailsWith<IllegalArgumentException> { KeyBinds.withCombatlog(cfg, setOf("q"), setOf("w")) }
    }
}
