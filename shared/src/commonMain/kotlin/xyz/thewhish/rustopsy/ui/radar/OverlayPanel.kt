package xyz.thewhish.rustopsy.ui.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.thewhish.rustopsy.ui.components.AshSlider
import xyz.thewhish.rustopsy.ui.components.AshSwitch
import xyz.thewhish.rustopsy.ui.components.Popover
import xyz.thewhish.rustopsy.ui.components.SegmentedControl
import xyz.thewhish.rustopsy.ui.overlay.OverlayAccess
import xyz.thewhish.rustopsy.ui.overlay.OverlayCorner
import xyz.thewhish.rustopsy.ui.overlay.OverlaySettings
import xyz.thewhish.rustopsy.ui.overlay.alignment
import xyz.thewhish.rustopsy.ui.components.pressScale
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshShapes
import xyz.thewhish.rustopsy.ui.theme.AshSpace
import xyz.thewhish.rustopsy.ui.theme.AshText

private val PANEL_WIDTH = 360.dp
private val SCREEN_WIDTH = 160.dp
private val SCREEN_HEIGHT = 90.dp
private val CORNER_WIDTH = 44.dp
private val CORNER_HEIGHT = 24.dp

@Composable
fun OverlayButton(enabled: Boolean, open: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (open || hovered) AshColors.ash3 else AshColors.ash2, tween(AshMotion.MICRO))
    Row(
        Modifier.scale(pressScale(interaction)).height(36.dp).clip(AshShapes.control).background(background)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CardIcon(AshColors.text2)
        AshText("Оверлей", Ash.type.bodyStrong)
        if (enabled) Box(Modifier.size(6.dp).clip(CircleShape).background(AshColors.ok))
    }
}

@Composable
fun BoxScope.OverlayPanel(open: Boolean, onClose: () -> Unit, overlay: OverlayAccess, top: Dp) {
    DisposableEffect(open) {
        overlay.preview(open)
        onDispose { overlay.preview(false) }
    }
    Popover(open, onClose, top, PANEL_WIDTH) {
        val settings by overlay.settings.collectAsState()
        var asking by remember { mutableStateOf(false) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AshText("Оверлей", Ash.type.section)
                AshText(if (settings.enabled) "Включён" else "Выключен", Ash.type.label, color = AshColors.text3)
            }
            AshSwitch(settings.enabled || asking, { on ->
                when {
                    !on -> {
                        asking = false
                        overlay.update(settings.copy(enabled = false))
                    }
                    settings.consented -> overlay.update(settings.copy(enabled = true))
                    else -> asking = true
                }
            })
        }
        AshText(
            "Карточка противников в углу экрана поверх игры: ХП, номер и расстояние. Появляется в бою и прячется через минуту после него",
            Ash.type.secondary,
            color = AshColors.text2,
            maxLines = 3,
        )
        AnimatedVisibility(asking, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Consent(
                onAccept = {
                    asking = false
                    overlay.update(settings.copy(enabled = true, consented = true))
                },
                onCancel = { asking = false },
            )
        }

        Group("Угол экрана") {
            CornerPicker(settings.corner) { overlay.update(settings.copy(corner = it)) }
        }
        Group("Отступ от края") {
            SliderRow("По горизонтали", "${settings.x} px", settings.x, OverlaySettings.OFFSETS) { overlay.update(settings.copy(offsetX = it)) }
            SliderRow("По вертикали", "${settings.y} px", settings.y, OverlaySettings.OFFSETS) { overlay.update(settings.copy(offsetY = it)) }
        }
        Group("Размер") {
            val scales = OverlaySettings.SCALES
            SegmentedControl(
                scales.map { "$it %" },
                scales.indexOf(settings.scalePercent).coerceAtLeast(0),
                { overlay.update(settings.copy(scalePercent = scales[it])) },
                Modifier.fillMaxWidth(),
                raised = true,
                fill = true,
            )
        }
        Group("Непрозрачность") {
            SliderRow("Фон карточки", "${settings.background} %", settings.background, OverlaySettings.BACKGROUNDS) { overlay.update(settings.copy(backgroundPercent = it)) }
            SliderRow("Строки противников", "${settings.rows} %", settings.rows, OverlaySettings.ROWS) { overlay.update(settings.copy(rowsPercent = it)) }
        }
        AshText(
            "Пока панель открыта, в углу экрана виден пример карточки. Если в игре карточки не видно, включи в Rust оконный режим без рамки",
            Ash.type.label,
            color = AshColors.text3,
            maxLines = 3,
        )
    }
}

@Composable
private fun Consent(onAccept: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AshShapes.control).background(AshColors.ash2).padding(AshSpace.m),
        verticalArrangement = Arrangement.spacedBy(AshSpace.s),
    ) {
        AshText("Как устроен оверлей", Ash.type.bodyStrong)
        AshText(
            "Это отдельное окно Rustopsy поверх игры, как прицел Crosshair X. В игру оно не встраивается, окно игры не трогает и показывает только данные боевого лога. Официальной гарантии от античита не даёт никто",
            Ash.type.label,
            color = AshColors.text2,
            maxLines = 5,
        )
        Row(Modifier.padding(top = AshSpace.xs), horizontalArrangement = Arrangement.spacedBy(AshSpace.s)) {
            SmallButton("Включить", primary = true, onClick = onAccept)
            SmallButton("Отмена", onClick = onCancel)
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AshSpace.s)) {
        AshText(title, Ash.type.label, color = AshColors.text3)
        content()
    }
}

@Composable
private fun SliderRow(label: String, value: String, current: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AshText(label, Ash.type.secondary, Modifier.weight(1f), AshColors.text2)
            AshText(value, Ash.type.label, color = AshColors.text2)
        }
        AshSlider(current, range, onChange)
    }
}

@Composable
private fun CornerPicker(selected: OverlayCorner, onSelect: (OverlayCorner) -> Unit) {
    Box(Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT).clip(AshShapes.control).background(AshColors.ash2).padding(6.dp)) {
        OverlayCorner.entries.forEach { corner ->
            CornerCell(corner == selected, Modifier.align(corner.alignment)) { onSelect(corner) }
        }
    }
}

@Composable
private fun CornerCell(selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (selected || hovered) AshColors.ash4 else AshColors.ash3, tween(AshMotion.MICRO))
    val border by animateColorAsState(if (selected) AshColors.ember else Color.Transparent, tween(AshMotion.MICRO))
    Box(
        modifier.scale(pressScale(interaction)).size(CORNER_WIDTH, CORNER_HEIGHT).clip(AshShapes.chip).background(background)
            .border(1.5.dp, border, AshShapes.chip)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick),
    )
}


@Composable
private fun CardIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(1.7f * unit)
        drawRoundRect(color, Offset(3f * unit, 4f * unit), Size(18f * unit, 16f * unit), CornerRadius(3f * unit), style = stroke)
        drawLine(color, Offset(7f * unit, 9.5f * unit), Offset(17f * unit, 9.5f * unit), stroke.width)
        drawLine(color, Offset(7f * unit, 14.5f * unit), Offset(13f * unit, 14.5f * unit), stroke.width)
    }
}

