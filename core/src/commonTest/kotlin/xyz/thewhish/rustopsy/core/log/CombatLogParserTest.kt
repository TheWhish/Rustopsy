package xyz.thewhish.rustopsy.core.log

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CombatLogParserTest {
    private val header =
        "time   attacker target       weapon                                           ammo        area    distance old_hp new_hp info                     hits integrity travel mismatch desync"
    private val rows = listOf(
        "85.68s you      player_15374 assets/prefabs/weapons/ak47u/ak47u.entity.prefab riflebullet chest   23.5m    0.0    100.0  projectile_network_range 0    1.00      0.06s  0.00m    59",
        "76.43s you      player_15352 assets/prefabs/weapons/ak47u/ak47u.entity.prefab riflebullet stomach 11.9m    0.0    100.0  projectile_network_range 0    1.00      0.03s  0.00m    27",
        "73.87s you      player_15334 assets/prefabs/weapons/ak47u/ak47u.entity.prefab riflebullet hand    13.6m    0.0    100.0  projectile_network_range 0    1.00      0.04s  0.00m    32",
        "14.23s you      player_15420 assets/prefabs/weapons/ak47u/ak47u.entity.prefab riflebullet arm     17.7m    0.0    100.0  projectile_network_range 0    1.00      0.05s  0.00m    43",
    )
    private val footer = "+ 1 event in the last 10 seconds"
    private val sample = (listOf(header) + rows + footer).joinToString("\n")

    @Test
    fun parsesAimServerSample() {
        val snapshot = assertNotNull(CombatLogParser.parse(sample))

        assertEquals(15, snapshot.columns.size)
        assertEquals(emptyList(), snapshot.issues)
        assertEquals(HiddenEvents(count = 1, windowSec = 10), snapshot.hidden)
        assertEquals(listOf(85.68, 76.43, 73.87, 14.23), snapshot.rows.map { it.ageSec })
        assertEquals(listOf(HitArea.CHEST, HitArea.STOMACH, HitArea.HAND, HitArea.ARM), snapshot.rows.map { it.area })
        assertEquals(
            CombatRow(
                ageSec = 85.68,
                attacker = Party("you", null),
                target = Party("player", 15374),
                weapon = "assets/prefabs/weapons/ak47u/ak47u.entity.prefab",
                ammo = "riflebullet",
                area = HitArea.CHEST,
                distanceM = 23.5,
                oldHp = 0.0,
                newHp = 100.0,
                info = "projectile_network_range",
                hits = 0,
                integrity = 1.0,
                travelSec = 0.06,
                mismatchM = 0.0,
                desync = 59,
                raw = rows[0],
            ),
            snapshot.rows[0],
        )
        assertTrue(snapshot.rows[0].attacker!!.isYou)
    }

    @Test
    fun collapsedWhitespaceFallsBackToTokens() {
        val collapsed = sample.lines().joinToString("\n") { it.trim().replace(Regex("""\s+"""), " ") }
        val snapshot = assertNotNull(CombatLogParser.parse(collapsed))

        assertEquals(emptyList(), snapshot.issues)
        assertEquals(CombatLogParser.parse(sample)!!.rows.map { it.copy(raw = "") }, snapshot.rows.map { it.copy(raw = "") })
    }

    @Test
    fun tabSeparatedFallsBackToTokens() {
        val tabbed = sample.lines().joinToString("\n") { it.trim().replace(Regex("""\s+"""), "\t") }
        val snapshot = assertNotNull(CombatLogParser.parse(tabbed))

        assertEquals(4, snapshot.rows.size)
        assertEquals(emptyList(), snapshot.issues)
    }

    @Test
    fun emptyInfoCellKeepsColumnsInPlace() {
        val emptyInfo = rows[0].replace("projectile_network_range", " ".repeat("projectile_network_range".length))
        val snapshot = assertNotNull(CombatLogParser.parse("$header\n$emptyInfo"))

        val row = snapshot.rows.single()
        assertEquals("", row.info)
        assertEquals(0, row.hits)
        assertEquals(1.0, row.integrity)
        assertEquals(59, row.desync)
    }

    @Test
    fun takesLastTableAndIgnoresSurroundingConsoleOutput() {
        val older = "$header\n${rows[0]}\n$footer"
        val text = "Connecting to 1.2.3.4\n$older\n> combatlog\n$sample\nSaved config\n"
        val snapshot = assertNotNull(CombatLogParser.parse(text))

        assertEquals(4, snapshot.rows.size)
        assertEquals(HiddenEvents(1, 10), snapshot.hidden)
    }

    @Test
    fun handlesCrlfBomAndTruncatedLastLine() {
        val truncated = "\uFEFF" + (listOf(header) + rows.dropLast(1) + rows.last().take(60)).joinToString("\r\n")
        val snapshot = assertNotNull(CombatLogParser.parse(truncated))

        assertEquals(4, snapshot.rows.size)
        val last = snapshot.rows.last()
        assertEquals(Party("player", 15420), last.target)
        assertNull(last.distanceM)
        assertNull(last.desync)
    }

    @Test
    fun headerWithoutRowsGivesEmptySnapshot() {
        val snapshot = assertNotNull(CombatLogParser.parse("$header\n+ 3 events in the last 10 seconds"))

        assertEquals(emptyList(), snapshot.rows)
        assertEquals(HiddenEvents(3, 10), snapshot.hidden)
    }

    @Test
    fun badValuesBecomeNullWithIssues() {
        val broken = rows[0].replace("23.5m", "far!m").replace("chest", "tails")
        val snapshot = assertNotNull(CombatLogParser.parse("$header\n$broken"))

        val row = snapshot.rows.single()
        assertNull(row.distanceM)
        assertNull(row.area)
        assertEquals(2, snapshot.issues.size)
    }

    @Test
    fun emptyLogMessageAfterTableGivesEmptySnapshot() {
        val text = "$sample\n10/15/2026 13:23:13 Combat log empty.\n10/15/2026 13:23:16 graphics.fov: \"90\""
        val snapshot = assertNotNull(CombatLogParser.parse(text))

        assertEquals(PrintTime.Local(ConsoleTime(2026, 10, 15, 13, 23, 13)), snapshot.printedAt)
        assertEquals(emptyList(), snapshot.rows)
        assertEquals(emptyList(), snapshot.columns)
    }

    @Test
    fun stampedHeaderWithoutRowsKeepsPrintTimeAndHiddenCount() {
        val text = """
            10/15/2026 13:23:17 graphics.vm_horizontal_flip: "True"
            10/15/2026 13:23:18 time attacker target weapon ammo area distance old_hp new_hp info hits integrity travel mismatch desync
            10/15/2026 13:23:18 + 1 event in the last 10 seconds
            10/15/2026 13:23:24 graphics.fov: "90"
        """.trimIndent()
        val snapshot = assertNotNull(CombatLogParser.parse(text))

        assertEquals(PrintTime.Local(ConsoleTime(2026, 10, 15, 13, 23, 18)), snapshot.printedAt)
        assertEquals(15, snapshot.columns.size)
        assertEquals(emptyList(), snapshot.rows)
        assertEquals(HiddenEvents(1, 10), snapshot.hidden)
    }

    @Test
    fun stampedRowsKeepAlignment() {
        val stamp = "10/15/2026 13:57:24 "
        val text = (listOf(header) + rows).joinToString("\n") { stamp + it } + "\n${stamp}> bind f2 combatlog"
        val snapshot = assertNotNull(CombatLogParser.parse(text))

        assertEquals(PrintTime.Local(ConsoleTime(2026, 10, 15, 13, 57, 24)), snapshot.printedAt)
        assertEquals(emptyList(), snapshot.issues)
        assertEquals(CombatLogParser.parse(sample)!!.rows, snapshot.rows)
    }

    @Test
    fun consoleTimeValidation() {
        assertEquals(ConsoleTime(2026, 2, 1, 0, 0, 59), ConsoleTime.parse("02/01/2026 00:00:59"))
        assertNull(ConsoleTime.parse("13/01/2026 00:00:00"))
        assertNull(ConsoleTime.parse("15.10.2026 13:00:00"))
        assertNull(ConsoleTime.parse(""))
    }

    @Test
    fun largeConsoleStaysFast() {
        val noise = List(20_000) { "10/15/2026 13:26:41  copypaste.copybox(  ) (Generated) Copies all entities within the selection box $it" }
        val text = (noise + sample.lines() + noise.take(200)).joinToString("\n")
        CombatLogParser.parse(text)

        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val snapshot = assertNotNull(CombatLogParser.parse(text))
        val elapsed = started.elapsedNow()

        assertEquals(4, snapshot.rows.size)
        assertTrue(elapsed.inWholeMilliseconds < 200, "Parsing took $elapsed")
    }

    @Test
    fun partyParsing() {
        assertEquals(Party("you", null), Party.parse("you"))
        assertEquals(Party("player", 15374), Party.parse("player_15374"))
        assertEquals(Party("scientistnpc_heavy", null), Party.parse("scientistnpc_heavy"))
        assertEquals(Party("x_99999999999999999999", null), Party.parse("x_99999999999999999999"))
    }

    @Test
    fun nonCombatLogTextGivesNull() {
        assertNull(CombatLogParser.parse(""))
        assertNull(CombatLogParser.parse("https://example.com"))
        assertNull(CombatLogParser.parse("the time attacker target"))
    }

    @Test
    fun arbitraryInputNeverThrows() {
        val random = Random(42)
        val alphabet = " \t\r\n+_.-0123456789smtimeattackertargetyouplayer|"
        repeat(2_000) {
            val noise = String(CharArray(random.nextInt(0, 400)) { alphabet[random.nextInt(alphabet.length)] })
            val cut = random.nextInt(0, sample.length)
            CombatLogParser.parse(noise)
            CombatLogParser.parse(sample.take(cut) + noise + sample.drop(cut))
        }
    }
}
