package xyz.thewhish.rustopsy.ui

import xyz.thewhish.rustopsy.core.log.HitArea
import kotlin.math.abs
import kotlin.math.roundToLong

private val NBSP = Char(0x00A0)
private const val MINUS = '−'

object Format {
    fun oneDecimal(value: Double): String {
        val tenths = (abs(value) * 10).roundToLong()
        val sign = if (value < 0 && tenths != 0L) MINUS.toString() else ""
        return "$sign${tenths / 10}.${tenths % 10}"
    }

    fun signedDamage(value: Double): String = if (value > 0) "$MINUS${oneDecimal(value)}" else oneDecimal(-value)

    fun meters(value: Double): String = "${value.roundToLong()}${NBSP}м"

    fun seconds(value: Long): String = "$value${NBSP}с"

    fun relativeSeconds(secondsAgo: Double): String = "$MINUS${oneDecimal(secondsAgo)}${NBSP}с"

    fun ago(seconds: Double): String = when {
        seconds < 10 -> "только что"
        seconds < 60 -> "${seconds.toLong()}${NBSP}с назад"
        seconds < 3_600 -> "${(seconds / 60).toLong()}${NBSP}мин назад"
        else -> "${(seconds / 3_600).toLong()}${NBSP}ч назад"
    }

    fun clock(epochSec: Double, utcOffsetSec: Int): String {
        val daySec = ((epochSec.toLong() + utcOffsetSec) % 86_400 + 86_400) % 86_400
        return "${two(daySec / 3_600)}:${two(daySec % 3_600 / 60)}:${two(daySec % 60)}"
    }

    fun duration(seconds: Double): String = if (seconds < 60) seconds(seconds.roundToLong()) else "${(seconds / 60).toLong()}${NBSP}мин ${(seconds % 60).toLong()}${NBSP}с"

    fun percent(part: Int, total: Int): String = "${part * 100 / total}${NBSP}%"

    fun times(count: Int): String = "$count ${plural(count, "раз", "раза", "раз")}"

    fun enemies(count: Int): String = "$count ${plural(count, "противник", "противника", "противников")}"

    fun events(count: Int): String = "$count ${plural(count, "событие", "события", "событий")}"

    fun areaName(area: HitArea?): String = when (area) {
        HitArea.HEAD -> "голова"
        HitArea.CHEST -> "грудь"
        HitArea.STOMACH -> "живот"
        HitArea.ARM -> "рука"
        HitArea.HAND -> "кисть"
        HitArea.LEG -> "нога"
        HitArea.FOOT -> "стопа"
        HitArea.GENERIC, null -> "без зоны"
    }

    fun areaTarget(area: HitArea?): String = when (area) {
        HitArea.HEAD -> "в голову"
        HitArea.CHEST -> "в грудь"
        HitArea.STOMACH -> "в живот"
        HitArea.ARM -> "в руку"
        HitArea.HAND -> "в кисть"
        HitArea.LEG -> "в ногу"
        HitArea.FOOT -> "в стопу"
        HitArea.GENERIC, null -> ""
    }

    private fun two(value: Long): String = value.toString().padStart(2, '0')

    private fun plural(count: Int, one: String, few: String, many: String): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
