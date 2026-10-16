package xyz.thewhish.rustopsy.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshShapes
import xyz.thewhish.rustopsy.ui.theme.AshText

class DeckKey(val name: String, val label: String)

private val W = DeckKey("w", "W")
private val A = DeckKey("a", "A")
private val S = DeckKey("s", "S")
private val D = DeckKey("d", "D")
private val SHIFT = DeckKey("leftshift", "Shift")
private val CTRL = DeckKey("leftctrl", "Ctrl")
private val SPACE = DeckKey("space", "Пробел")
private val LEFT_BUTTON = DeckKey("mouse0", "ЛКМ")
private val RIGHT_BUTTON = DeckKey("mouse1", "ПКМ")

val DECK_KEYS: List<DeckKey> = listOf(W, A, S, D, SHIFT, CTRL, SPACE, LEFT_BUTTON, RIGHT_BUTTON)
val MANAGED_KEYS: Set<String> = DECK_KEYS.map { it.name }.toSet()

class KeyPicking(val selected: Set<String>, val enabled: Boolean, val onToggle: (String) -> Unit, val onHover: (String, Boolean) -> Unit)

private val KEY = 64.dp
private val GAP = 8.dp
private val BLOCK_HEIGHT = KEY * 2 + GAP
private val MOUSE_WIDTH = 136.dp
private val MOUSE_TOP = 66.dp
private val MOUSE_GAP = 4.dp
private val MOUSE_CORNER = 44.dp
private val MOUSE_PALM_CORNER = 52.dp
private val KEY_LIP = 2.dp
private val KEY_SHADOW = Color.Black.copy(alpha = 0.25f)

@Composable
fun KeyDeck(picking: KeyPicking, modifier: Modifier = Modifier) {
    Row(
        modifier.background(AshColors.ash0, AshShapes.panel).padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
    ) {
        DeckGroup("Движение") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GAP)) {
                KeyToggle(W, picking, Modifier.size(KEY))
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    listOf(A, S, D).forEach { KeyToggle(it, picking, Modifier.size(KEY)) }
                }
            }
        }
        DeckGroup("Действия") {
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    KeyToggle(SHIFT, picking, Modifier.size(KEY * 1.5f, KEY))
                    KeyToggle(CTRL, picking, Modifier.size(KEY * 1.5f, KEY))
                }
                KeyToggle(SPACE, picking, Modifier.size(KEY * 3 + GAP, KEY))
            }
        }
        DeckGroup("Мышь") { Mouse(picking) }
    }
}

@Composable
private fun DeckGroup(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.height(BLOCK_HEIGHT), contentAlignment = Alignment.BottomCenter) { content() }
        AshText(label, Ash.type.label, color = AshColors.text3)
    }
}

@Composable
private fun Mouse(picking: KeyPicking) {
    Column(Modifier.width(MOUSE_WIDTH).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(MOUSE_GAP)) {
        Row(Modifier.fillMaxWidth().height(MOUSE_TOP), horizontalArrangement = Arrangement.spacedBy(MOUSE_GAP)) {
            val inner = 6.dp
            KeyToggle(LEFT_BUTTON, picking, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(MOUSE_CORNER, inner, inner, inner))
            KeyToggle(RIGHT_BUTTON, picking, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(inner, MOUSE_CORNER, inner, inner))
        }
        val palm = RoundedCornerShape(6.dp, 6.dp, MOUSE_PALM_CORNER, MOUSE_PALM_CORNER)
        Box(Modifier.fillMaxWidth().weight(1f).background(AshColors.ash2, palm))
    }
}

@Composable
private fun KeyToggle(key: DeckKey, picking: KeyPicking, modifier: Modifier, shape: Shape = AshShapes.control) {
    val selected = key.name in picking.selected
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { picking.onHover(key.name, hovered) }

    val background by animateColorAsState(
        when {
            selected -> AshColors.ash3
            hovered && picking.enabled -> AshColors.ash3
            else -> AshColors.ash2
        },
        tween(AshMotion.MICRO, easing = AshMotion.standard),
    )
    val outline by animateColorAsState(if (selected) AshColors.ember else Color.Transparent, tween(AshMotion.SHORT, easing = AshMotion.standard))
    val base = modifier
        .drawBehind {
            val lip = KEY_LIP.toPx()
            drawOutline(shape.createOutline(size, layoutDirection, this), KEY_SHADOW)
            drawOutline(shape.createOutline(Size(size.width, size.height - lip), layoutDirection, this), background)
        }
        .border(2.dp, outline, shape)
        .hoverable(interaction)
    Box(
        if (picking.enabled) base.clickable(interaction, indication = null) { picking.onToggle(key.name) } else base,
        contentAlignment = Alignment.Center,
    ) {
        AshText(key.label, Ash.type.bodyStrong, Modifier.padding(horizontal = 6.dp), if (selected) AshColors.text1 else AshColors.text2)
    }
}