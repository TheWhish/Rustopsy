package xyz.thewhish.rustopsy.session

import xyz.thewhish.rustopsy.core.session.CombatEvent
import xyz.thewhish.rustopsy.core.session.HiddenWindow

data class DataGap(val fromSec: Double, val untilSec: Double) {
    fun overlaps(startSec: Double, endSec: Double): Boolean = fromSec < endSec && untilSec > startSec
}

data class SessionState(
    val events: List<CombatEvent> = emptyList(),
    val hidden: HiddenWindow? = null,
    val printedAtSec: Double? = null,
    val capturedAtSec: Double? = null,
    val gaps: List<DataGap> = emptyList(),
) {
    val lagSec: Double? get() = if (printedAtSec == null || capturedAtSec == null) null else capturedAtSec - printedAtSec
}
