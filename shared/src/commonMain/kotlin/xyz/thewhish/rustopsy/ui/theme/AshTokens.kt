package xyz.thewhish.rustopsy.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object AshColors {
    val ash0 = Color(0xFF1E1C1A)
    val ash1 = Color(0xFF272421)
    val ash2 = Color(0xFF2F2B28)
    val ash3 = Color(0xFF3A3531)
    val ash4 = Color(0xFF46403B)
    val text1 = Color(0xFFECE9E4)
    val text2 = Color(0xFFABA69E)
    val text3 = Color(0xFF99948C)
    val text4 = Color(0xFF6F6B65)
    val ember = Color(0xFFD45A3C)
    val emberStrong = Color(0xFFB8482D)
    val onEmber = Color(0xFFFFF6F2)
    val ok = Color(0xFF8DB07A)
    val warn = Color(0xFFD7AB5E)
    val bad = Color(0xFFE87C70)
    val incoming = Color(0xFFC98A64)
    val team = Color(0xFF8FA7BF)
    val okSoft = ok.copy(alpha = 0.16f)
    val warnSoft = warn.copy(alpha = 0.14f)
    val badSoft = bad.copy(alpha = 0.14f)
    val damageGhost = ember.copy(alpha = 0.45f)
}

object AshShapes {
    val panel = RoundedCornerShape(10.dp)
    val control = RoundedCornerShape(7.dp)
    val chip = RoundedCornerShape(6.dp)
    val mark = RoundedCornerShape(2.dp)
}

object AshSpace {
    val hair = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

object AshMotion {
    const val MICRO = 90
    const val SHORT = 160
    const val MEDIUM = 240
    const val LONG = 360
    const val COOL = 1200
    const val HP_COUNTER = 400
    const val GHOST_DELAY = 200
    const val GHOST_BURN = 600
    const val SMOULDER = 1400
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}
