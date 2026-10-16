package xyz.thewhish.rustopsy.core.fight

import xyz.thewhish.rustopsy.core.log.CombatRow
import xyz.thewhish.rustopsy.core.log.Party
import xyz.thewhish.rustopsy.core.session.CombatEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class FightAnalyzerTest {
    private val you = Party("you", null)

    private fun event(timeSec: Double, oldHp: Double, newHp: Double, info: String = "", incoming: Boolean = false, enemy: Long = 7) = CombatEvent(
        timeSec,
        CombatRow(
            ageSec = 0.0, attacker = if (incoming) Party("player", enemy) else you, target = if (incoming) you else Party("player", enemy),
            weapon = "assets/prefabs/weapons/lr300/lr300.entity.prefab", ammo = "riflebullet", area = null,
            distanceM = 20.0, oldHp = oldHp, newHp = newHp, info = info, hits = 0, integrity = 1.0,
            travelSec = 0.05, mismatchM = 0.0, desync = 10, raw = "",
        ),
    )

    private fun life(vararg events: CombatEvent): Life = FightAnalyzer.analyze(events.toList()).single().enemies.single().lives.single()

    @Test
    fun healingBetweenHitsIsDetected() {
        val life = life(event(0.0, 100.0, 18.0), event(6.0, 53.0, 20.0))

        assertEquals(listOf(HpChange(6.0, 35.0)), life.hpChanges)
        assertEquals(35.0, life.healed)
        assertEquals(115.0, life.dealtDamage)
        assertEquals(20.0, life.hp)
    }

    @Test
    fun thirdPartyDamageIsCountedAsOthers() {
        val life = life(event(0.0, 100.0, 70.0), event(2.0, 40.0, 10.0))

        assertEquals(listOf(HpChange(2.0, -30.0)), life.hpChanges)
        assertEquals(30.0, life.othersDamage)
        assertEquals(Condition.ALIVE, life.condition)
        assertEquals(10.0, life.hp)
    }

    @Test
    fun killedInfoIsKillNotRejected() {
        val life = life(event(0.0, 100.0, 60.0), event(0.2, 60.0, 0.0, info = KILLED))

        assertEquals(Condition.DEAD, life.condition)
        assertEquals(Actor.You, life.killedBy)
        assertEquals(100.0, life.dealtDamage)
        assertEquals(emptyMap(), life.rejected)
        assertEquals(listOf(Outcome.HIT, Outcome.KILL), life.hits.map { it.outcome })
    }

    @Test
    fun hpRisingToCrawlRangeIsKnock() {
        val life = life(event(0.0, 100.0, 54.1), event(0.3, 54.1, 8.2), event(0.4, 8.2, 9.0))

        assertEquals(Condition.KNOCKED, life.condition)
        assertEquals(Actor.You, life.knockedBy)
        assertEquals(9.0, life.hp)
        assertEquals(100.0, life.dealtDamage, 1e-9)
    }

    @Test
    fun finishingKnockedEnemyAddsNoDamage() {
        val life = life(event(0.0, 100.0, 54.0), event(0.3, 54.0, 4.9), event(0.4, 4.9, 10.0), event(0.7, 10.0, 4.9, info = KILLED))

        assertEquals(listOf(Outcome.HIT, Outcome.HIT, Outcome.KNOCK, Outcome.FINISH), life.hits.map { it.outcome })
        assertEquals(Condition.DEAD, life.condition)
        assertEquals(100.0, life.dealtDamage, 1e-9)
    }

    @Test
    fun crawlValueAfterLethalDamageIsKnock() {
        val life = life(event(0.0, 100.0, 78.0), event(0.2, 78.0, 56.0), event(0.4, 56.0, 34.0), event(0.6, 34.0, 12.0), event(0.8, 12.0, 8.0))

        assertEquals(Outcome.KNOCK, life.hits.last().outcome)
        assertEquals(Condition.KNOCKED, life.condition)
    }

    @Test
    fun crawlValueAfterOrdinaryDamageIsHit() {
        val life = life(event(0.0, 100.0, 77.5), event(0.2, 77.5, 55.0), event(0.4, 55.0, 32.5), event(0.6, 32.5, 10.0))

        assertEquals(Outcome.HIT, life.hits.last().outcome)
        assertEquals(Condition.ALIVE, life.condition)
        assertEquals(10.0, life.hp)
    }

    @Test
    fun finishWithoutYourKnockMeansSomeoneElseKnocked() {
        val life = life(event(0.0, 100.0, 70.0), event(4.0, 9.0, 4.0, info = KILLED))

        assertEquals(Actor.Others, life.knockedBy)
        assertEquals(Actor.You, life.killedBy)
        assertEquals(70.0, life.othersDamage)
        assertEquals(listOf(HpChange(4.0, -70.0, knocked = true)), life.hpChanges)
    }

    @Test
    fun rejectedReadingDoesNotChangeHp() {
        val life = life(event(0.0, 100.0, 45.0), event(1.0, 0.0, 8.0, info = "projectile_network_range"))

        assertEquals(45.0, life.hp)
        assertEquals(Condition.ALIVE, life.condition)
        assertEquals(mapOf("projectile_network_range" to 1), life.rejected)
    }

    @Test
    fun rejectedOnlyEnemyHasNoDamageAndUnknownHp() {
        val life = life(event(0.0, 0.0, 100.0, info = "projectile_los"))

        assertEquals(0.0, life.dealtDamage)
        assertNull(life.hp)
        assertEquals(Condition.ALIVE, life.condition)
    }

    @Test
    fun respawnConventionWithoutInfoIsKill() {
        val life = life(event(0.0, 100.0, 33.8), event(0.2, 33.8, 100.0))

        assertEquals(Condition.DEAD, life.condition)
        assertEquals(100.0, life.dealtDamage)
    }

    @Test
    fun incomingWoundedKnocksYouAndYourShotMeansRevived() {
        val knocked = FightAnalyzer.analyze(listOf(event(0.0, 30.0, 8.0, info = WOUNDED, incoming = true))).single()
        val revived = FightAnalyzer.analyze(listOf(event(0.0, 30.0, 8.0, info = WOUNDED, incoming = true), event(5.0, 100.0, 80.0))).single()

        assertEquals(Condition.KNOCKED, knocked.you)
        assertEquals(30.0, knocked.damageTaken)
        assertEquals(Condition.ALIVE, revived.you)
    }

    @Test
    fun yourDeathEndsFightAndNumbersRestart() {
        val fights = FightAnalyzer.analyze(
            listOf(
                event(0.0, 100.0, 60.0, enemy = 1),
                event(0.5, 10.0, 8.0, info = WOUNDED, incoming = true, enemy = 2),
                event(1.0, 8.0, 3.0, info = KILLED, incoming = true, enemy = 2),
                event(1.2, 0.0, 60.0, info = "you died first (projectile_invalid)", enemy = 1),
                event(5.0, 100.0, 70.0, enemy = 2),
            ),
        )

        assertEquals(2, fights.size)
        assertEquals(Condition.DEAD, fights[0].you)
        assertEquals(listOf(1L, 2L), fights[0].enemies.map { it.party.id })
        assertEquals(listOf(2L), fights[1].enemies.map { it.party.id })
        assertEquals(1, fights[1].enemies.single().number)
    }

    @Test
    fun fullHpAfterPauseStartsNewRound() {
        val round = FightAnalyzer.analyze(listOf(event(0.0, 100.0, 40.0), event(20.0, 100.0, 80.0)))
        val sameFight = FightAnalyzer.analyze(listOf(event(0.0, 100.0, 40.0), event(20.0, 40.0, 20.0)))
        val newEnemy = FightAnalyzer.analyze(listOf(event(0.0, 100.0, 40.0), event(20.0, 100.0, 80.0, enemy = 8)))

        assertEquals(2, round.size)
        assertEquals(1, sameFight.size)
        assertEquals(1, newEnemy.size)
    }

    @Test
    fun deadEnemyReturningAfterPauseStartsNewFight() {
        val fights = FightAnalyzer.analyze(listOf(event(0.0, 30.0, 0.0, info = KILLED), event(12.0, 77.7, 55.5)))

        assertEquals(2, fights.size)
    }

    @Test
    fun longPauseStartsNewFight() {
        val fights = FightAnalyzer.analyze(listOf(event(0.0, 100.0, 80.0), event(46.0, 80.0, 60.0)))

        assertEquals(2, fights.size)
        assertEquals(listOf(1, 1), fights.map { it.enemies.single().number })
    }

    @Test
    fun incomingHitsGiveEnemyWeaponAndYourDamage() {
        val fight = FightAnalyzer.analyze(listOf(event(0.0, 100.0, 60.0, incoming = true), event(0.5, 60.0, 0.0, info = KILLED, incoming = true))).single()

        assertEquals(listOf("lr300"), fight.enemies.single().weapons)
        assertEquals(100.0, fight.damageTaken)
        assertEquals(Condition.DEAD, fight.you)
        assertEquals(emptyList(), fight.enemies.single().lives)
    }

    @Test
    fun twoThousandEventsAreAnalyzedQuickly() {
        val events = List(2_000) { i ->
            val hp = 100.0 - (i % 5) * 20
            when {
                i % 7 == 0 -> event(i * 0.5, 100.0, 90.0, incoming = true, enemy = i % 5L)
                i % 5 == 4 -> event(i * 0.5, hp, 0.0, info = KILLED, enemy = i % 5L)
                else -> event(i * 0.5, hp, hp - 20, enemy = i % 5L)
            }
        }
        FightAnalyzer.analyze(events)

        val started = TimeSource.Monotonic.markNow()
        val fights = FightAnalyzer.analyze(events)
        val elapsed = started.elapsedNow()

        assertEquals(1, fights.size)
        assertTrue(elapsed < 1.seconds, "Analysis took $elapsed")
    }

    @Test
    fun missesAndNonPlayerTargetsAreIgnored() {
        val miss = event(0.0, 0.0, 0.0, info = "projectile_invalid").let { it.copy(row = it.row.copy(target = null)) }
        val barricade = event(1.0, 80.0, 78.5).let { it.copy(row = it.row.copy(target = Party.parse("barricade.cover.wood_double"))) }
        val corpse = event(2.0, 100.0, 100.0).let { it.copy(row = it.row.copy(target = Party.parse("player_corpse"))) }

        assertEquals(emptyList(), FightAnalyzer.analyze(listOf(miss, barricade, corpse)))
    }
}
