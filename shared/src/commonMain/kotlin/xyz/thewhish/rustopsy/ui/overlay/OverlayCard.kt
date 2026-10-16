package xyz.thewhish.rustopsy.ui.overlay

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import xyz.thewhish.rustopsy.core.fight.Condition
import xyz.thewhish.rustopsy.ui.Format
import xyz.thewhish.rustopsy.ui.components.AnimatedHp
import xyz.thewhish.rustopsy.ui.components.rememberCooling
import xyz.thewhish.rustopsy.ui.radar.EnemySummary
import xyz.thewhish.rustopsy.ui.radar.hpColor
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshText

private val SPACING = 6.dp
private val ROW_HEIGHT = 48.dp
private val ROW_INSET = 12.dp
private val FOOTER_HEIGHT = 28.dp
private val HP_WIDTH = 96.dp
private val TEAM_STRIPE = 2.dp
private val ROW_SHAPE = RoundedCornerShape(6.dp)
private val CARD_SHAPE = RoundedCornerShape(6.dp + SPACING)
private const val BORDER_ALPHA = 0.06f
private const val SHADOW_BELOW_ROWS = 0.75f
private const val BRIGHT_FOOTER_BELOW = 0.5f
private val CENTERED = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
private val TEXT_SHADOW = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 3f)

val OVERLAY_CARD_WIDTH = 280.dp
val OVERLAY_CARD_MAX_HEIGHT = SPACING * 2 + (ROW_HEIGHT + SPACING) * OVERLAY_MAX_ROWS + FOOTER_HEIGHT

@Composable
fun OverlayCard(model: OverlayModel, nowSec: Double, backgroundOpacity: Float, rowsOpacity: Float, modifier: Modifier = Modifier) {
    val shadow = TEXT_SHADOW.takeIf { rowsOpacity < SHADOW_BELOW_ROWS }
    val rowColor = AshColors.ash1.copy(alpha = rowsOpacity)
    Column(
        modifier.width(OVERLAY_CARD_WIDTH)
            .clip(CARD_SHAPE)
            .background(AshColors.ash0.copy(alpha = backgroundOpacity))
            .border(1.dp, AshColors.text1.copy(alpha = BORDER_ALPHA * backgroundOpacity), CARD_SHAPE)
            .padding(SPACING)
            .animateContentSize(tween(AshMotion.MEDIUM, easing = AshMotion.standard)),
        verticalArrangement = Arrangement.spacedBy(SPACING),
    ) {
        model.enemies.forEach { enemy ->
            key(enemy.number) { EnemyRow(enemy, rowColor, shadow) }
        }
        Footer(model, nowSec, shadow, if (backgroundOpacity < BRIGHT_FOOTER_BELOW) AshColors.text2 else AshColors.text3)
    }
}

@Composable
private fun EnemyRow(enemy: EnemySummary, base: Color, shadow: Shadow?) {
    val background = rememberCooling(Triple(enemy.hp, enemy.condition, enemy.distanceM), enabled = true, base)
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT)
            .clip(ROW_SHAPE)
            .background(background)
            .drawBehind { if (!enemy.seenByYou) drawRect(AshColors.team, size = Size(TEAM_STRIPE.toPx(), size.height)) }
            .padding(horizontal = ROW_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(HP_WIDTH)) { Hp(enemy, shadow) }
        AshText("Противник ${enemy.number}", Ash.type.bodyStrong.overlay(shadow), Modifier.weight(1f))
        enemy.distanceM?.let { AshText(Format.meters(it), Ash.type.secondary.overlay(shadow), Modifier.padding(start = ROW_INSET), AshColors.text2) }
    }
}

@Composable
private fun Hp(enemy: EnemySummary, shadow: Shadow?) {
    val color = hpColor(enemy)
    val style = Ash.type.numOverlay.overlay(shadow)
    val hp = enemy.hp
    when {
        enemy.condition == Condition.KNOCKED -> AshText("Нокнут", style, color = color)
        hp == null -> AshText("−", style, color = color)
        else -> Row {
            AnimatedHp(hp, style, color, Modifier.alignByBaseline())
            AshText("HP", Ash.type.caption.overlay(shadow), Modifier.alignByBaseline().padding(start = 4.dp), AshColors.text3)
        }
    }
}

@Composable
private fun Footer(model: OverlayModel, nowSec: Double, shadow: Shadow?, color: Color) {
    val style = Ash.type.label.overlay(shadow)
    Row(Modifier.fillMaxWidth().height(FOOTER_HEIGHT).padding(horizontal = ROW_INSET), verticalAlignment = Alignment.CenterVertically) {
        AshText("Последний хит: ${Format.ago(nowSec - model.lastHitSec)}", style, color = color)
        Spacer(Modifier.weight(1f))
        AshText("Убито ${model.killed} из ${model.total}", style, color = color)
    }
}

private fun TextStyle.overlay(shadow: Shadow?): TextStyle = copy(lineHeightStyle = CENTERED, shadow = shadow)
