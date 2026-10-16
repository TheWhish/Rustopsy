package xyz.thewhish.rustopsy.ui.overlay

import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class OverlayCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

val OverlayCorner.alignment: Alignment
    get() = when (this) {
        OverlayCorner.TOP_LEFT -> Alignment.TopStart
        OverlayCorner.TOP_RIGHT -> Alignment.TopEnd
        OverlayCorner.BOTTOM_LEFT -> Alignment.BottomStart
        OverlayCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
    }

val OverlayCorner.right: Boolean get() = this == OverlayCorner.TOP_RIGHT || this == OverlayCorner.BOTTOM_RIGHT

@Serializable
data class OverlaySettings(
    val enabled: Boolean = false,
    val consented: Boolean = false,
    val corner: OverlayCorner = OverlayCorner.TOP_RIGHT,
    val offsetX: Int = 24,
    val offsetY: Int = 24,
    val scalePercent: Int = 100,
    val backgroundPercent: Int = 60,
    val rowsPercent: Int = 90,
) {
    val x: Int get() = offsetX.coerceIn(OFFSETS)
    val y: Int get() = offsetY.coerceIn(OFFSETS)
    val scale: Float get() = scalePercent.coerceIn(SCALES.first(), SCALES.last()) / 100f
    val background: Int get() = backgroundPercent.coerceIn(BACKGROUNDS)
    val rows: Int get() = rowsPercent.coerceIn(ROWS)

    companion object {
        val OFFSETS = 0..400
        val BACKGROUNDS = 0..100
        val ROWS = 30..100
        val SCALES = listOf(90, 100, 115, 130)
    }
}

class OverlayAccess(
    val settings: StateFlow<OverlaySettings>,
    val update: (OverlaySettings) -> Unit,
    val preview: (Boolean) -> Unit,
)
