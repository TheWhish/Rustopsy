package xyz.thewhish.rustopsy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.thewhish.rustopsy.ui.Format
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshShapes
import xyz.thewhish.rustopsy.ui.theme.AshSpace
import xyz.thewhish.rustopsy.ui.theme.AshText
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(AshShapes.panel).background(AshColors.ash1), content = content)
}

@Composable
fun PanelHeader(title: String, meta: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = AshSpace.xl, end = AshSpace.xl, top = AshSpace.l, bottom = AshSpace.m),
        verticalAlignment = Alignment.Bottom,
    ) {
        AshText(title, Ash.type.section)
        Spacer(Modifier.weight(1f))
        if (meta != null) AshText(meta, Ash.type.label, color = AshColors.text3)
    }
}

@Composable
fun HpBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val target = fraction.coerceIn(0f, 1f)
    val fill = remember { Animatable(target) }
    val ghost = remember { Animatable(target) }
    val fillColor by animateColorAsState(color, tween(AshMotion.HP_COUNTER, easing = AshMotion.standard))
    LaunchedEffect(target) {
        if (target < fill.value) {
            fill.snapTo(target)
            delay(AshMotion.GHOST_DELAY.toLong().milliseconds)
            ghost.animateTo(target, tween(AshMotion.GHOST_BURN, easing = AshMotion.standard))
        } else {
            ghost.snapTo(target)
            fill.animateTo(target, tween(AshMotion.MEDIUM, easing = AshMotion.standard))
        }
    }
    Canvas(modifier.height(height)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(AshColors.ash3, cornerRadius = radius)
        drawRoundRect(AshColors.damageGhost, size = Size(size.width * ghost.value, size.height), cornerRadius = radius)
        drawRoundRect(fillColor, size = Size(size.width * fill.value, size.height), cornerRadius = radius)
    }
}

@Composable
fun AnimatedHp(value: Double, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(value.toFloat(), tween(AshMotion.HP_COUNTER, easing = AshMotion.standard))
    AshText(Format.oneDecimal(shown.toDouble()), style, modifier, color)
}

enum class MarkerShape { Dot, Cross, Square, Ring }

@Composable
fun Marker(shape: MarkerShape, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(12.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = 4.dp.toPx()
        when (shape) {
            MarkerShape.Dot -> drawCircle(color, r, c)
            MarkerShape.Ring -> drawCircle(color, r, c, style = Stroke(1.6.dp.toPx()))
            MarkerShape.Square -> drawRoundRect(color, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), CornerRadius(1.dp.toPx()))
            MarkerShape.Cross -> {
                val w = 2.2.dp.toPx()
                drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), w, StrokeCap.Round)
                drawLine(color, Offset(c.x + r, c.y - r), Offset(c.x - r, c.y + r), w, StrokeCap.Round)
            }
        }
    }
}

@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    fill: Boolean = false,
) {
    val container = if (raised) AshColors.ash2 else AshColors.ash1
    val hover = if (raised) AshColors.ash3 else AshColors.ash2
    val active = if (raised) AshColors.ash4 else AshColors.ash3
    Row(
        modifier.clip(AshShapes.control).background(container).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(AshSpace.hair),
    ) {
        options.forEachIndexed { index, option ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val background by animateColorAsState(
                when {
                    index == selected -> active
                    hovered -> hover
                    else -> container
                },
                tween(AshMotion.MICRO, easing = AshMotion.standard),
            )
            Box(
                (if (fill) Modifier.weight(1f) else Modifier).height(30.dp)
                    .clip(AshShapes.chip)
                    .background(background)
                    .hoverable(interaction)
                    .clickable(interaction, indication = null) { onSelect(index) }
                    .padding(horizontal = AshSpace.m),
                contentAlignment = Alignment.Center,
            ) {
                AshText(option, Ash.type.secondary, color = if (index == selected) AshColors.text1 else AshColors.text2)
            }
        }
    }
}

@Composable
fun KeyCap(key: String, modifier: Modifier = Modifier) {
    Box(
        modifier.sizeIn(minWidth = 52.dp, minHeight = 40.dp)
            .clip(AshShapes.control)
            .background(AshColors.ash2)
            .drawBehind {
                drawRect(Color.Black.copy(alpha = 0.25f), Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
            }
            .padding(horizontal = AshSpace.m),
        contentAlignment = Alignment.Center,
    ) {
        AshText(key, Ash.type.bodyStrong)
    }
}

@Composable
fun rememberCooling(key: Any, enabled: Boolean, base: Color): Color {
    val heat = remember(key) { Animatable(if (enabled) 1f else 0f) }
    LaunchedEffect(key) { heat.animateTo(0f, tween(AshMotion.COOL, easing = AshMotion.standard)) }
    return lerp(base, lerp(base, AshColors.ember, 0.22f), heat.value)
}

@Composable
fun AshButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            !enabled -> AshColors.ash2
            primary && hovered -> AshColors.emberStrong
            primary -> AshColors.ember
            hovered -> AshColors.ash4
            else -> AshColors.ash3
        },
        tween(AshMotion.MICRO, easing = AshMotion.standard),
    )
    val base = modifier.sizeIn(minHeight = 40.dp).clip(AshShapes.control).background(background)
    Box(
        if (enabled) base.hoverable(interaction).clickable(interaction, indication = null, onClick = onClick) else base,
        contentAlignment = Alignment.Center,
    ) {
        AshText(
            text,
            Ash.type.bodyStrong,
            Modifier.padding(horizontal = AshSpace.l),
            when {
                !enabled -> AshColors.text4
                primary -> AshColors.onEmber
                else -> AshColors.text1
            },
        )
    }
}
