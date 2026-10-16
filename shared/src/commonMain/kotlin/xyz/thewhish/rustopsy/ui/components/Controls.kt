package xyz.thewhish.rustopsy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import kotlin.math.roundToInt

private val SWITCH_WIDTH = 36.dp
private val SWITCH_HEIGHT = 20.dp
private val SWITCH_INSET = 2.dp
private val SWITCH_THUMB = 16.dp
private val SLIDER_HEIGHT = 20.dp
private val SLIDER_TRACK = 4.dp
private val SLIDER_THUMB = 14.dp

@Composable
fun AshSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val track by animateColorAsState(
        when {
            checked -> AshColors.ok
            hovered -> AshColors.ash4
            else -> AshColors.ash3
        },
        tween(AshMotion.SHORT),
    )
    val shift by animateDpAsState(if (checked) SWITCH_WIDTH - SWITCH_THUMB - SWITCH_INSET * 2 else 0.dp, AshMotion.glide())
    Box(
        modifier.size(SWITCH_WIDTH, SWITCH_HEIGHT).clip(CircleShape).background(track)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Switch) { onChange(!checked) }
            .padding(SWITCH_INSET),
    ) {
        Box(Modifier.offset(x = shift).size(SWITCH_THUMB).clip(CircleShape).background(AshColors.text1))
    }
}

@Composable
fun AshSlider(value: Int, range: IntRange, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(onChange)
    fun PointerInputScope.pick(x: Float) {
        val thumb = SLIDER_THUMB.toPx() / 2
        val fraction = ((x - thumb) / (size.width - thumb * 2)).coerceIn(0f, 1f)
        current(range.first + (fraction * (range.last - range.first)).roundToInt())
    }
    Canvas(
        modifier.fillMaxWidth().height(SLIDER_HEIGHT)
            .pointerInput(range) { detectTapGestures { pick(it.x) } }
            .pointerInput(range) { detectHorizontalDragGestures { change, _ -> pick(change.position.x) } },
    ) {
        val fraction = (value - range.first).toFloat() / (range.last - range.first)
        val thumb = SLIDER_THUMB.toPx() / 2
        val track = SLIDER_TRACK.toPx()
        val top = (size.height - track) / 2
        val x = thumb + (size.width - thumb * 2) * fraction.coerceIn(0f, 1f)
        drawRoundRect(AshColors.ash3, Offset(0f, top), Size(size.width, track), CornerRadius(track / 2))
        drawRoundRect(AshColors.text2, Offset(0f, top), Size(x, track), CornerRadius(track / 2))
        drawCircle(AshColors.text1, thumb, Offset(x, size.height / 2))
    }
}
