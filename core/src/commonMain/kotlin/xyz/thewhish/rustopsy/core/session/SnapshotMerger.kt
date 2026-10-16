package xyz.thewhish.rustopsy.core.session

import xyz.thewhish.rustopsy.core.log.CombatRow
import xyz.thewhish.rustopsy.core.log.Snapshot
import kotlin.math.abs

data class CombatEvent(val timeSec: Double, val row: CombatRow, val teammate: Long? = null)

data class HiddenWindow(val count: Int, val untilSec: Double, val windowSec: Int)

data class MergeResult(
    val events: List<CombatEvent>,
    val added: List<CombatEvent>,
    val continuous: Boolean,
)

fun Snapshot.hiddenWindow(printedAtSec: Double): HiddenWindow? =
    hidden?.let { HiddenWindow(it.count, printedAtSec + it.windowSec, it.windowSec) }

object SnapshotMerger {
    const val MATCH_TOLERANCE_SEC = 1.5

    fun merge(events: List<CombatEvent>, snapshot: Snapshot, printedAtSec: Double): MergeResult {
        val unmatched = events.groupBy({ it.row.identity() }, { it.timeSec }).mapValues { it.value.toMutableList() }
        val added = mutableListOf<CombatEvent>()
        var overlaps = false

        for (row in snapshot.rows) {
            val timeSec = printedAtSec - row.ageSec
            val candidates = unmatched[row.identity()]
            val match = candidates
                ?.filter { abs(it - timeSec) <= MATCH_TOLERANCE_SEC }
                ?.minByOrNull { abs(it - timeSec) }
            if (match == null) {
                added += CombatEvent(timeSec, row)
            } else {
                candidates.remove(match)
                overlaps = true
            }
        }

        return MergeResult(
            events = if (added.isEmpty()) events else (events + added).sortedBy { it.timeSec },
            added = added,
            continuous = overlaps || events.isEmpty() || snapshot.rows.isEmpty(),
        )
    }

    private fun CombatRow.identity(): CombatRow = copy(ageSec = 0.0, raw = "")
}
