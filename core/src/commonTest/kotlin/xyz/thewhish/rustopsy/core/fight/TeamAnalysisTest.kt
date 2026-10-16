package xyz.thewhish.rustopsy.core.fight

import xyz.thewhish.rustopsy.core.log.CombatRow
import xyz.thewhish.rustopsy.core.log.HitArea
import xyz.thewhish.rustopsy.core.log.Party
import xyz.thewhish.rustopsy.core.session.CombatEvent
import xyz.thewhish.rustopsy.core.sync.toEvent
import xyz.thewhish.rustopsy.core.sync.toTeamHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamAnalysisTest {
    private val you: Party = Party("you", null)
    private val enemy: Long = 76561198000000007L
    private val mate: Long = 76561198000000002L

    private fun row(attacker: Party, target: Party, oldHp: Double, newHp: Double, info: String = ""): CombatRow = CombatRow(
        ageSec = 0.0, attacker = attacker, target = target,
        weapon = "assets/prefabs/weapons/ak47u/ak47u.entity.prefab", ammo = "riflebullet", area = HitArea.CHEST,
        distanceM = 20.0, oldHp = oldHp, newHp = newHp, info = info, hits = 0, integrity = 1.0,
        travelSec = 0.05, mismatchM = 0.0, desync = 10, raw = "",
    )

    private fun mine(timeSec: Double, oldHp: Double, newHp: Double, target: Long = enemy, info: String = ""): CombatEvent =
        CombatEvent(timeSec, row(you, Party("player", target), oldHp, newHp, info))

    private fun theirs(timeSec: Double, oldHp: Double, newHp: Double, target: Long = enemy, info: String = ""): CombatEvent =
        CombatEvent(timeSec, row(Party("player", mate), Party("player", target), oldHp, newHp, info), teammate = mate)

    private fun incoming(timeSec: Double, oldHp: Double, newHp: Double, from: Long): CombatEvent =
        CombatEvent(timeSec, row(Party("player", from), you, oldHp, newHp))

    private fun analyze(vararg events: CombatEvent): List<Fight> = FightAnalyzer.analyze(events.sortedBy { it.timeSec }, setOf(mate))

    @Test
    fun teammateDamageIsNotCountedAsUnknown() {
        val life = analyze(mine(0.0, 100.0, 70.0), theirs(1.0, 70.0, 40.0), mine(2.0, 40.0, 10.0)).single().enemies.single().lives.single()

        assertEquals(60.0, life.dealtDamage)
        assertEquals(mapOf(mate to 30.0), life.teamDamage)
        assertEquals(0.0, life.othersDamage)
        assertTrue(life.hpChanges.isEmpty())
    }

    @Test
    fun knockAndKillAreAttributedToTeammate() {
        val life = analyze(mine(0.0, 100.0, 40.0), theirs(1.0, 40.0, 9.0, info = WOUNDED), theirs(2.0, 9.0, 4.0, info = KILLED)).single().enemies.single().lives.single()

        assertEquals(Actor.Teammate(mate), life.knockedBy)
        assertEquals(Actor.Teammate(mate), life.killedBy)
        assertEquals(Condition.DEAD, life.condition)
    }

    @Test
    fun clockSkewWithinToleranceIsResolvedByHpChain() {
        val life = analyze(mine(1.00, 100.0, 80.0), theirs(0.95, 80.0, 60.0), mine(1.10, 60.0, 40.0)).single().enemies.single().lives.single()

        assertTrue(life.hpChanges.isEmpty())
        assertEquals(40.0, life.dealtDamage)
        assertEquals(mapOf(mate to 20.0), life.teamDamage)
    }

    @Test
    fun teamEventsExtendYourFightButDoNotStartOne() {
        val other = 76561198000000009L
        val fights = analyze(mine(100.0, 100.0, 70.0), theirs(103.0, 100.0, 50.0, target = other), theirs(130.0, 50.0, 20.0, target = other), theirs(300.0, 100.0, 60.0, target = other))
        val fight = fights.single()

        assertEquals(130.0, fight.endSec)
        val teamOnly = fight.enemies.single { it.party.id == other }
        assertEquals(false, teamOnly.seenByYou)
        assertEquals(mapOf(mate to 80.0), teamOnly.lives.single().teamDamage)
        assertEquals(true, fight.enemies.single { it.party.id == enemy }.seenByYou)
    }

    @Test
    fun teammatesAreNeverEnemies() {
        val fights = analyze(mine(0.0, 100.0, 80.0), incoming(1.0, 100.0, 90.0, from = mate), mine(2.0, 100.0, 90.0, target = mate))

        assertEquals(listOf(enemy), fights.single().enemies.map { it.party.id })
        assertEquals(0.0, fights.single().damageTaken)
    }

    @Test
    fun onlyYourAcceptedHitsOnPlayersAreShared() {
        val hit = mine(1_000.25, 100.0, 70.0)
        val shared = hit.toTeamHit(clockOffsetMs = 500)!!
        val received = shared.toEvent(from = mate, clockOffsetMs = -250)

        assertEquals(1_000_750L, shared.timeMs)
        assertEquals(1_001.0, received.timeSec)
        assertEquals(mate, received.teammate)
        assertEquals(hit.row.copy(attacker = Party("player", mate), distanceM = null, hits = null, integrity = null, travelSec = null, mismatchM = null, desync = null), received.row)
        assertNull(mine(0.0, 0.0, 100.0, info = "projectile_los").toTeamHit(0))
        assertNull(mine(0.0, 100.0, 70.0, target = 15374L).toTeamHit(0))
        assertNull(incoming(0.0, 100.0, 70.0, from = enemy).toTeamHit(0))
        assertNull(received.toTeamHit(0))
    }
}
