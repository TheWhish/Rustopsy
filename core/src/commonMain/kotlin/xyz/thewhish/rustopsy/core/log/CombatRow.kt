package xyz.thewhish.rustopsy.core.log

data class Party(val name: String, val id: Long?) {
    val isYou: Boolean get() = id == null && name == YOU

    companion object {
        private const val YOU = "you"
        private val WITH_ID = Regex("""(.+)_(\d+)""")

        fun parse(raw: String): Party {
            val match = WITH_ID.matchEntire(raw) ?: return Party(raw, null)
            val id = match.groupValues[2].toLongOrNull() ?: return Party(raw, null)
            return Party(match.groupValues[1], id)
        }
    }
}

enum class HitArea {
    HEAD, CHEST, STOMACH, ARM, HAND, LEG, FOOT, GENERIC;

    companion object {
        fun parse(raw: String): HitArea? = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

data class ConsoleTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int) {

    fun toEpochSeconds(): Long {
        val y = if (month <= 2) year - 1 else year
        val era = y.floorDiv(400)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * ((month + 9) % 12) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        val days = era * 146_097L + dayOfEra - 719_468
        return days * 86_400 + hour * 3_600 + minute * 60 + second
    }

    companion object {
        private val STAMP = Regex("""(\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2}):(\d{2})""")

        fun parse(raw: String): ConsoleTime? {
            val (month, day, year, hour, minute, second) = STAMP.matchEntire(raw.trim())?.destructured ?: return null
            return of(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
        }

        fun of(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): ConsoleTime? {
            val valid = month in 1..12 && day in 1..daysInMonth(year, month) && hour in 0..23 && minute in 0..59 && second in 0..59
            return if (valid) ConsoleTime(year, month, day, hour, minute, second) else null
        }

        private fun daysInMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

data class CombatRow(
    val ageSec: Double,
    val attacker: Party?,
    val target: Party?,
    val weapon: String,
    val ammo: String,
    val area: HitArea?,
    val distanceM: Double?,
    val oldHp: Double?,
    val newHp: Double?,
    val info: String,
    val hits: Int?,
    val integrity: Double?,
    val travelSec: Double?,
    val mismatchM: Double?,
    val desync: Int?,
    val raw: String,
)

sealed interface PrintTime {
    data class Local(val time: ConsoleTime) : PrintTime

    data class Utc(val epochMillis: Long) : PrintTime
}

data class HiddenEvents(val count: Int, val windowSec: Int)

data class Snapshot(
    val printedAt: PrintTime?,
    val columns: List<String>,
    val rows: List<CombatRow>,
    val hidden: HiddenEvents?,
    val issues: List<String>,
    val complete: Boolean = true,
)
