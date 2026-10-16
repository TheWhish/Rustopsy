package xyz.thewhish.rustopsy.core.fight

import xyz.thewhish.rustopsy.core.log.CombatRow
import xyz.thewhish.rustopsy.core.log.Party
import xyz.thewhish.rustopsy.core.session.CombatEvent

enum class Outcome { HIT, KNOCK, DOWNED, KILL, FINISH, REJECTED }

enum class Condition { ALIVE, KNOCKED, DEAD }

enum class Actor { YOU, OTHERS }

data class Hit(val event: CombatEvent, val outcome: Outcome, val damage: Double) {
    val condition: Condition?
        get() = when (outcome) {
            Outcome.HIT -> Condition.ALIVE
            Outcome.KNOCK, Outcome.DOWNED -> Condition.KNOCKED
            Outcome.KILL, Outcome.FINISH -> Condition.DEAD
            Outcome.REJECTED -> null
        }

    val hpAfter: Double?
        get() = when (outcome) {
            Outcome.KILL, Outcome.FINISH -> 0.0
            Outcome.REJECTED -> null
            else -> event.row.newHp
        }
}

data class Fight(
    val startSec: Double,
    val endSec: Double,
    val enemies: List<Enemy>,
    val damageTaken: Double,
    val you: Condition,
)

data class Enemy(
    val number: Int,
    val party: Party,
    val lives: List<Life>,
    val incoming: List<Hit>,
    val weapons: List<String>,
    val medianDistanceM: Double?,
)

data class Life(
    val hits: List<Hit>,
    val dealtDamage: Double,
    val othersDamage: Double,
    val healed: Double,
    val hp: Double?,
    val condition: Condition,
    val knockedBy: Actor?,
    val killedBy: Actor?,
    val hpChanges: List<HpChange>,
    val rejected: Map<String, Int>,
)

data class HpChange(val timeSec: Double, val delta: Double, val knocked: Boolean = false)

const val KILLED = "killed"
const val WOUNDED = "wounded"

val CombatRow.isRejected: Boolean get() = info.isNotEmpty() && info != KILLED && info != WOUNDED

fun weaponName(prefab: String): String =
    prefab.substringAfterLast('/').removeSuffix(".prefab").removeSuffix(".entity")
