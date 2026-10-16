package xyz.thewhish.rustopsy.core.fight

import xyz.thewhish.rustopsy.core.log.CombatLogParser
import xyz.thewhish.rustopsy.core.printedSec
import xyz.thewhish.rustopsy.core.session.CombatEvent
import xyz.thewhish.rustopsy.core.session.SnapshotMerger
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeamFightTest {
    private val events = run {
        val text = assertNotNull(javaClass.getResource("/combatlogs/output-log-teamfights.txt")).readText()
        text.split("\n\n").filter { it.isNotBlank() }.fold(emptyList<CombatEvent>()) { events, table ->
            val snapshot = assertNotNull(CombatLogParser.parse(table))
            SnapshotMerger.merge(events, snapshot, snapshot.printedSec()).events
        }
    }
    private val fights = FightAnalyzer.analyze(events)

    private fun sec(time: String): Double = Instant.parse("2026-10-15T${time}Z").toEpochMilli() / 1_000.0

    private fun fightAt(time: String): Fight = fights.single { sec(time) in it.startSec - 0.01..it.endSec + 0.01 }

    private fun hitAt(time: String): Hit = fights
        .flatMap { fight -> fight.enemies.flatMap { enemy -> enemy.lives.flatMap { it.hits } + enemy.incoming } }
        .single { abs(it.event.timeSec - sec(time)) < 0.002 && it.outcome != Outcome.REJECTED }

    private fun lifeWith(time: String): Life = fightAt(time).enemies.flatMap { it.lives }.single { life -> life.hits.any { abs(it.event.timeSec - sec(time)) < 0.002 } }

    @Test
    fun everyUniqueRowBecomesOneEvent() {
        assertEquals(405, events.size)
    }

    @Test
    fun killedAndWoundedAreValidAndOtherCodesAreRejected() {
        val hits = fights.flatMap { fight -> fight.enemies.flatMap { enemy -> enemy.lives.flatMap { it.hits } + enemy.incoming } }

        assertTrue(hits.filter { it.event.row.info == KILLED || it.event.row.info == WOUNDED }.none { it.outcome == Outcome.REJECTED })
        assertTrue(hits.filter { it.event.row.info.isNotEmpty() && it.event.row.info != KILLED && it.event.row.info != WOUNDED }.all { it.outcome == Outcome.REJECTED })
    }

    @Test
    fun everyFightNumbersEnemiesFromOne() {
        fights.forEach { fight -> assertEquals((1..fight.enemies.size).toList(), fight.enemies.map { it.number }) }
    }

    @Test
    fun eachOfYourDeathsEndsItsOwnFight() {
        val deaths = fights.sumOf { fight -> fight.enemies.sumOf { enemy -> enemy.incoming.count { it.condition == Condition.DEAD } } }

        assertEquals(deaths, fights.count { it.you == Condition.DEAD })
    }

    @Test
    fun newRoundWithFullHpIsSeparateFight() {
        val killed = fightAt("12:41:54.337")
        val nextRound = fightAt("12:42:11.635")

        assertTrue(killed != nextRound)
        assertEquals(1, nextRound.enemies.single().number)
    }

    @Test
    fun pauseWithHealingStaysInSameFight() {
        assertEquals(fightAt("12:45:58.459"), fightAt("12:46:13.466"))
    }

    @Test
    fun tinyDamageOnLowHpIsKnockThenFinish() {
        val life = lifeWith("12:57:06.881")

        assertEquals(Outcome.KNOCK, hitAt("12:57:06.881").outcome)
        assertEquals(Outcome.FINISH, hitAt("12:57:25.050").outcome)
        assertEquals(Condition.DEAD, life.condition)
        assertEquals(Actor.You, life.knockedBy)
        assertEquals(Actor.You, life.killedBy)
        assertEquals(96.1, life.dealtDamage, 1e-9)
    }

    @Test
    fun crawlValueAfterLethalDamageOnSameTargetIsKnock() {
        assertEquals(Outcome.KNOCK, hitAt("12:58:41.728").outcome)
    }

    @Test
    fun ordinaryHitLandingOnCrawlValueIsNotKnock() {
        val life = lifeWith("12:58:02.321")

        assertEquals(Outcome.HIT, hitAt("12:58:02.321").outcome)
        assertEquals(Condition.ALIVE, life.condition)
        assertEquals(10.0, life.hp)
    }

    @Test
    fun teammateDamageIsAttributedToOthers() {
        val life = lifeWith("12:09:25.933")

        assertEquals(23.2, life.othersDamage, 1e-9)
        assertEquals(Condition.DEAD, life.condition)
    }

    @Test
    fun woundedByEnemyLeavesYouKnocked() {
        assertEquals(Condition.KNOCKED, fightAt("13:00:37.252").you)
        assertEquals(Outcome.KNOCK, hitAt("13:00:37.252").outcome)
    }
}
