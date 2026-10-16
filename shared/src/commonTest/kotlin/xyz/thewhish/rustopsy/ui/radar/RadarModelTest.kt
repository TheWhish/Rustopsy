package xyz.thewhish.rustopsy.ui.radar

import xyz.thewhish.rustopsy.core.fight.Actor
import xyz.thewhish.rustopsy.core.fight.Condition
import xyz.thewhish.rustopsy.core.fight.FightAnalyzer
import xyz.thewhish.rustopsy.core.fight.KILLED
import xyz.thewhish.rustopsy.core.fight.Outcome
import xyz.thewhish.rustopsy.core.log.CombatRow
import xyz.thewhish.rustopsy.core.log.HitArea
import xyz.thewhish.rustopsy.core.log.Party
import xyz.thewhish.rustopsy.core.session.CombatEvent
import xyz.thewhish.rustopsy.ui.Format
import kotlin.test.Test
import kotlin.test.assertEquals

class RadarModelTest {
    private val you = Party("you", null)
    private val enemy = Party("player", 7)

    private fun event(timeSec: Double, oldHp: Double, newHp: Double, info: String = "", incoming: Boolean = false) = CombatEvent(
        timeSec,
        CombatRow(
            ageSec = 0.0, attacker = if (incoming) enemy else you, target = if (incoming) you else enemy,
            weapon = "assets/prefabs/weapons/ak47u/ak47u.entity.prefab", ammo = "riflebullet", area = HitArea.CHEST,
            distanceM = 20.0, oldHp = oldHp, newHp = newHp, info = info, hits = 0, integrity = 1.0,
            travelSec = 0.05, mismatchM = 0.0, desync = 10, raw = "",
        ),
    )

    private val enemyModel = FightAnalyzer.analyze(
        listOf(
            event(0.0, 100.0, 60.0),
            event(1.0, 100.0, 70.0, incoming = true),
            event(4.0, 80.0, 40.0),
            event(4.2, 0.0, 100.0, info = "projectile_los"),
        ),
    ).single().enemies.single()

    @Test
    fun summaryCountsDamageHealingAndInvalid() {
        assertEquals(
            EnemySummary(
                number = 1, weapon = "ak47u", distanceM = 20.0, hp = 40.0, condition = Condition.ALIVE, knockedBy = null, killedBy = null,
                deaths = 0, dealt = 80.0, team = 0.0, others = 0.0, healed = 20.0, invalid = 1, seenByYou = true,
            ),
            enemyModel.summary(),
        )
    }

    @Test
    fun eventLinesAreNewestFirstWithHealing() {
        val lines = enemyModel.eventLines()

        assertEquals(
            listOf(EventKind.Rejected, EventKind.Hit, EventKind.Heal, EventKind.Incoming, EventKind.Hit),
            lines.map { it.kind },
        )
        assertEquals("projectile_los", lines.first().code)
        assertEquals(20.0, lines.first { it.kind == EventKind.Heal }.amount)
    }

    @Test
    fun finishAfterSomeoneElsesKnockIsShown() {
        val enemy = FightAnalyzer.analyze(listOf(event(0.0, 100.0, 70.0), event(3.0, 9.0, 4.0, info = KILLED))).single().enemies.single()
        val summary = enemy.summary()

        assertEquals(Condition.DEAD, summary.condition)
        assertEquals(Actor.Others, summary.knockedBy)
        assertEquals(70.0, summary.others)
        assertEquals(listOf(EventKind.Hit, EventKind.KnockedByOthers, EventKind.Hit), enemy.eventLines().map { it.kind })
        assertEquals(Outcome.FINISH, enemy.eventLines().first().outcome)
    }

    @Test
    fun pelletsOfOneShotBecomeOneLine() {
        val enemy = FightAnalyzer.analyze(listOf(event(0.0, 100.0, 89.5), event(0.01, 89.5, 79.0), event(0.02, 79.0, 68.5), event(0.6, 68.5, 58.0))).single().enemies.single()
        val lines = enemy.eventLines()

        assertEquals(listOf(1, 3), lines.map { it.count })
        assertEquals(31.5, lines.last().amount!!, 1e-9)
        assertEquals(68.5, lines.last().hpAfter)
    }

    @Test
    fun identicalPelletsGetUniqueStableKeys() {
        val pellets = listOf(
            event(5.0, 92.1, 81.6, incoming = true), event(5.0, 81.6, 71.1, incoming = true),
            event(6.0, 0.0, 100.0, info = "projectile_los"), event(6.0, 0.0, 100.0, info = "projectile_los"),
        )
        val before = FightAnalyzer.analyze(pellets).single().enemies.single().eventLines()
        val after = FightAnalyzer.analyze(pellets + event(9.0, 100.0, 60.0)).single().enemies.single().eventLines()

        assertEquals(before.size, before.map { it.key }.toSet().size)
        assertEquals(after.size, after.map { it.key }.toSet().size)
        assertEquals(before.map { it.key }, after.drop(1).map { it.key })
        assertEquals(2, before.single { it.kind == EventKind.Incoming }.count)
        assertEquals(21.0, before.single { it.kind == EventKind.Incoming }.amount!!, 1e-9)
    }

    @Test
    fun teammateHitsAreNamedAndCountedSeparately() {
        val mate = 76561198000000002
        val theirs = CombatEvent(2.0, event(2.0, 60.0, 30.0).row.copy(attacker = Party("player", mate)), teammate = mate)
        val fight = FightAnalyzer.analyze(listOf(event(0.0, 100.0, 60.0), theirs), setOf(mate)).single()
        val enemy = fight.enemies.single()
        val line = enemy.eventLines(mapOf(mate to "Mate")).first()

        assertEquals(EventKind.Team, line.kind)
        assertEquals("Mate", line.teammate)
        assertEquals(30.0, enemy.summary().team)
        assertEquals(40.0, fight.totals().dealt)
        assertEquals(listOf(Contribution(Actor.You, 40.0, 0, 0), Contribution(Actor.Teammate(mate), 30.0, 0, 0)), enemy.contributions())
    }

    @Test
    fun fightTotalsCountYourDamageKnocksAndKills() {
        val totals = FightAnalyzer.analyze(listOf(event(0.0, 100.0, 54.0), event(0.3, 54.0, 4.9), event(0.4, 4.9, 10.0), event(0.7, 10.0, 4.9, info = KILLED), event(1.0, 0.0, 80.0, info = "projectile_los"))).single().totals()

        assertEquals(FightTotals(dealt = 100.0, knocks = 1, kills = 1, validHits = 4, headshots = 0), totals)
    }

    @Test
    fun formatting() {
        assertEquals("12.4", Format.oneDecimal(12.36))
        assertEquals("0.0", Format.oneDecimal(-0.01))
        assertEquals("${'−'}27.6", Format.signedDamage(27.6))
        assertEquals("2 противника", Format.enemies(2))
        assertEquals("11 противников", Format.enemies(11))
        assertEquals("21 противник", Format.enemies(21))
        assertEquals("только что", Format.ago(3.0))
    }
}
