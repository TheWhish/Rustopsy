package xyz.thewhish.rustopsy.ui.update

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.thewhish.rustopsy.ui.Format
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshShapes
import xyz.thewhish.rustopsy.ui.theme.AshSpace
import xyz.thewhish.rustopsy.ui.theme.AshText

private val BAR_HEIGHT = 4.dp
private const val BORDER_ALPHA = 0.06f

val LAUNCH_SCREEN_SIZE = DpSize(360.dp, 148.dp)

sealed interface LaunchState {
    data object Checking : LaunchState
    data class Updated(val version: String) : LaunchState
    data class Downloading(val version: String, val received: Long, val total: Long) : LaunchState
    data class Restarting(val total: Long) : LaunchState
}

@Composable
fun LaunchScreen(state: LaunchState, version: String, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(progress(state), spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow))
    val fill by animateColorAsState(if (state is LaunchState.Updated) AshColors.ok else AshColors.ember, tween(AshMotion.MEDIUM, easing = AshMotion.standard))
    Column(
        modifier.size(LAUNCH_SCREEN_SIZE)
            .clip(AshShapes.panel)
            .background(AshColors.ash0)
            .border(1.dp, AshColors.text1.copy(alpha = BORDER_ALPHA), AshShapes.panel)
            .padding(AshSpace.xxl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AshText("Rustopsy", Ash.type.section.copy(fontSize = 16.sp), Modifier.weight(1f))
            AshText(version, Ash.type.label, color = AshColors.text4)
        }
        Spacer(Modifier.height(AshSpace.xl))
        Crossfade(status(state), animationSpec = tween(AshMotion.MEDIUM, easing = AshMotion.standard)) {
            AshText(it, Ash.type.body, color = AshColors.text2)
        }
        Spacer(Modifier.height(AshSpace.m))
        ProgressBar(progress, fill)
        Spacer(Modifier.height(AshSpace.s))
        AshText(meta(state), Ash.type.label, color = AshColors.text3)
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(BAR_HEIGHT)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(AshColors.ash3, cornerRadius = radius)
        drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
    }
}

private fun status(state: LaunchState): String = when (state) {
    LaunchState.Checking -> "Запуск"
    is LaunchState.Updated -> "Обновлено до ${state.version}"
    is LaunchState.Downloading -> "Обновление до ${state.version}"
    is LaunchState.Restarting -> "Перезапуск…"
}

private fun meta(state: LaunchState): String = when (state) {
    is LaunchState.Downloading -> Format.downloaded(state.received, state.total)
    is LaunchState.Restarting -> Format.downloaded(state.total, state.total)
    LaunchState.Checking -> "Проверка обновлений"
    is LaunchState.Updated -> "Готово"
}

private fun progress(state: LaunchState): Float = when (state) {
    is LaunchState.Downloading -> state.received.toFloat() / state.total
    is LaunchState.Restarting, is LaunchState.Updated -> 1f
    LaunchState.Checking -> 0f
}
