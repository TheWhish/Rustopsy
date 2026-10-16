package xyz.thewhish.rustopsy.core.fight

import xyz.thewhish.rustopsy.core.log.CombatRow
import xyz.thewhish.rustopsy.core.log.Party
import xyz.thewhish.rustopsy.core.session.CombatEvent
import kotlin.math.abs
import kotlin.math.floor

object FightAnalyzer {
    const val FIGHT_GAP_SEC = 45.0
    const val PAUSE_SEC = 10.0
    const val DEATH_GRACE_SEC = 2.0
    const val HP_NOISE = 1.0
    const val FULL_HP = 100.0
    const val CRAWL_MIN_HP = 7.0
    const val CRAWL_MAX_HP = 11.0

    private const val PLAYER = "player"
    private const val HP_EPSILON = 0.05
    private val YOU = Party.parse("you")

    private class Contact(val enemy: Party, val event: CombatEvent) {
        val outgoing: Boolean = event.row.attacker?.isYou == true
        val target: Party get() = if (outgoing) enemy else YOU
    }

    private class Engagement(val contact: Contact, val hit: Hit, val startsLife: Boolean) {
        val timeSec: Double get() = hit.event.timeSec
        val youDie: Boolean get() = !contact.outgoing && hit.condition == Condition.DEAD
    }

    fun analyze(events: List<CombatEvent>): List<Fight> {
        val contacts = events.mapNotNull { event -> enemyOf(event.row)?.let { Contact(it, event) } }
        return fights(classify(contacts)).map(::fight)
    }

    private fun enemyOf(row: CombatRow): Party? {
        val attacker = row.attacker ?: return null
        val target = row.target ?: return null
        return when {
            attacker.isYou && target.name == PLAYER && target.id != null -> target
            target.isYou && attacker.name == PLAYER && attacker.id != null -> attacker
            else -> null
        }
    }

    private fun classify(contacts: List<Contact>): List<Engagement> {
        val reference = referenceDamage(contacts)
        val nextValid = IntArray(contacts.size) { -1 }
        val following = HashMap<Party, Int>()
        for (i in contacts.indices.reversed()) {
            if (!contacts[i].isUsable()) continue
            nextValid[i] = following[contacts[i].target] ?: -1
            following[contacts[i].target] = i
        }

        val states = HashMap<Party, Condition>()
        val targetReference = HashMap<Pair<Party, String>, Double>()
        return contacts.mapIndexed { i, contact ->
            if (!contact.isUsable()) return@mapIndexed Engagement(contact, Hit(contact.event, Outcome.REJECTED, 0.0), startsLife = false)
            val row = contact.event.row
            val old = row.oldHp!!
            val new = row.newHp!!
            val previous = states[contact.target] ?: Condition.ALIVE
            val startsLife = previous == Condition.DEAD || (previous == Condition.KNOCKED && old.isFull())
            val before = if (startsLife || (previous == Condition.KNOCKED && old > CRAWL_MAX_HP)) Condition.ALIVE else previous
            val finishedNext = nextValid[i] >= 0 && contacts[nextValid[i]].event.row.finishes(new)
            val inferredKnock = contact.outgoing && new.isCrawlHp() &&
                (finishedNext || old <= (targetReference[contact.target to row.weapon] ?: reference[row.weapon] ?: 0.0))
            val outcome = outcome(row, old, new, before, inferredKnock)
            val hit = Hit(contact.event, outcome, damage(outcome, old, new))
            states[contact.target] = hit.condition!!
            if (contact.outgoing && outcome == Outcome.HIT && row.isReferenceSample()) {
                targetReference.merge(contact.target to row.weapon, old - new, ::minOf)
            }
            Engagement(contact, hit, startsLife)
        }
    }

    private fun outcome(row: CombatRow, old: Double, new: Double, before: Condition, inferredKnock: Boolean): Outcome {
        val lethal = row.info == KILLED || (new > old && new > CRAWL_MAX_HP)
        return when {
            lethal -> if (before == Condition.KNOCKED || (row.info == KILLED && new > 0)) Outcome.FINISH else Outcome.KILL
            before == Condition.KNOCKED -> Outcome.DOWNED
            row.info == WOUNDED || new > old || inferredKnock -> Outcome.KNOCK
            else -> Outcome.HIT
        }
    }

    private fun damage(outcome: Outcome, old: Double, new: Double): Double = when (outcome) {
        Outcome.HIT -> (old - new).coerceAtLeast(0.0)
        Outcome.KNOCK, Outcome.KILL -> old
        Outcome.DOWNED, Outcome.FINISH, Outcome.REJECTED -> 0.0
    }

    private fun referenceDamage(contacts: List<Contact>): Map<String, Double> = contacts.asSequence()
        .filter { it.outgoing && it.isUsable() }
        .map { it.event.row }
        .filter { it.isReferenceSample() }
        .groupBy({ it.weapon }, { it.oldHp!! - it.newHp!! })
        .mapValues { (_, damages) -> damages.min() }

    private fun CombatRow.isReferenceSample(): Boolean =
        info.isEmpty() && oldHp!! > CRAWL_MAX_HP && newHp!! < oldHp && !newHp.isCrawlHp()

    private fun Contact.isUsable(): Boolean = !event.row.isRejected && event.row.oldHp != null && event.row.newHp != null

    private fun CombatRow.finishes(knockedHp: Double): Boolean =
        info == KILLED && (newHp ?: 0.0) > 0 && (oldHp ?: Double.MAX_VALUE) <= knockedHp + HP_EPSILON

    private fun Double.isCrawlHp(): Boolean = this in CRAWL_MIN_HP..CRAWL_MAX_HP && this == floor(this)

    private fun Double.isFull(): Boolean = this >= FULL_HP - HP_EPSILON

    private fun fights(engagements: List<Engagement>): List<List<Engagement>> {
        val fights = mutableListOf<MutableList<Engagement>>()
        for (chunk in chunks(engagements)) {
            val current = fights.lastOrNull()
            val separate = current == null || current.any { it.youDie } ||
                chunk.first().timeSec - current.last().timeSec > FIGHT_GAP_SEC || resets(current, chunk)
            if (separate) fights += chunk.toMutableList() else current += chunk
        }
        return fights
    }

    private fun chunks(engagements: List<Engagement>): List<List<Engagement>> {
        val chunks = mutableListOf<MutableList<Engagement>>()
        var deathSec: Double? = null
        for (engagement in engagements) {
            val current = chunks.lastOrNull()
            val paused = current != null && engagement.timeSec - current.last().timeSec >= PAUSE_SEC
            val afterDeath = deathSec != null && engagement.timeSec - deathSec > DEATH_GRACE_SEC
            if (current == null || paused || afterDeath) {
                chunks += mutableListOf(engagement)
                deathSec = null
            } else {
                current += engagement
            }
            if (engagement.youDie && deathSec == null) deathSec = engagement.timeSec
        }
        return chunks
    }

    private fun resets(fight: List<Engagement>, chunk: List<Engagement>): Boolean {
        val last = HashMap<Party, Hit>()
        fight.filter { it.hit.outcome != Outcome.REJECTED }.forEach { last[it.contact.target] = it.hit }
        val first = LinkedHashMap<Party, Double>()
        chunk.filter { it.hit.outcome != Outcome.REJECTED }.forEach { first.getOrPut(it.contact.target) { it.hit.event.row.oldHp!! } }

        var reset = false
        for ((target, hp) in first) {
            val before = last[target] ?: continue
            when {
                before.condition == Condition.DEAD -> reset = true
                !hp.isFull() -> return false
                before.condition == Condition.KNOCKED || !before.hpAfter!!.isFull() -> reset = true
            }
        }
        return reset
    }

    private fun fight(engagements: List<Engagement>): Fight {
        val incoming = engagements.filterNot { it.contact.outgoing }.map { it.hit }
        return Fight(
            startSec = engagements.first().timeSec,
            endSec = engagements.last().timeSec,
            enemies = engagements.groupBy { it.contact.enemy }.entries
                .mapIndexed { i, (party, list) -> enemy(i + 1, party, list) },
            damageTaken = incoming.sumOf { it.damage },
            you = yourCondition(engagements),
        )
    }

    private fun yourCondition(engagements: List<Engagement>): Condition {
        var condition = Condition.ALIVE
        for (engagement in engagements) {
            val hitCondition = engagement.hit.condition ?: continue
            if (!engagement.contact.outgoing) condition = hitCondition
            else if (condition == Condition.KNOCKED) condition = Condition.ALIVE
        }
        return condition
    }

    private fun enemy(number: Int, party: Party, engagements: List<Engagement>): Enemy {
        val incoming = engagements.filterNot { it.contact.outgoing }.map { it.hit }
        return Enemy(
            number = number,
            party = party,
            lives = lives(engagements.filter { it.contact.outgoing }),
            incoming = incoming,
            weapons = incoming.map { weaponName(it.event.row.weapon) }.filter { it.isNotEmpty() }.distinct(),
            medianDistanceM = median(engagements.mapNotNull { it.hit.event.row.distanceM }),
        )
    }

    private fun lives(outgoing: List<Engagement>): List<Life> {
        val lives = mutableListOf<MutableList<Hit>>()
        for (engagement in outgoing) {
            if (lives.isEmpty() || engagement.startsLife) lives += mutableListOf(engagement.hit) else lives.last() += engagement.hit
        }
        return lives.map(::life)
    }

    private fun life(hits: List<Hit>): Life {
        var hp: Double? = null
        var condition = Condition.ALIVE
        var knockedBy: Actor? = null
        var killedBy: Actor? = null
        var dealt = 0.0
        var others = 0.0
        var healed = 0.0
        val changes = mutableListOf<HpChange>()

        for (hit in hits) {
            val after = hit.condition ?: continue
            val timeSec = hit.event.timeSec
            val previous = hp
            if (condition == Condition.ALIVE && previous != null && (hit.outcome == Outcome.FINISH || hit.outcome == Outcome.DOWNED)) {
                knockedBy = Actor.OTHERS
                others += previous
                changes += HpChange(timeSec, -previous, knocked = true)
            } else if (condition == Condition.ALIVE && previous != null) {
                val delta = hit.event.row.oldHp!! - previous
                if (abs(delta) >= HP_NOISE) {
                    changes += HpChange(timeSec, delta)
                    if (delta > 0) healed += delta else others -= delta
                }
            }
            dealt += hit.damage
            hp = hit.hpAfter
            condition = after
            when (hit.outcome) {
                Outcome.KNOCK -> knockedBy = Actor.YOU
                Outcome.KILL, Outcome.FINISH -> killedBy = Actor.YOU
                else -> Unit
            }
        }

        return Life(
            hits = hits,
            dealtDamage = dealt,
            othersDamage = others,
            healed = healed,
            hp = hp,
            condition = condition,
            knockedBy = knockedBy,
            killedBy = killedBy,
            hpChanges = changes,
            rejected = hits.filter { it.outcome == Outcome.REJECTED }.groupingBy { it.event.row.info }.eachCount(),
        )
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
