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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

private val CARD_PADDING = 6.dp
private val ROW_GAP = 4.dp
private val ROW_HEIGHT = 48.dp
private val ROW_INSET = 12.dp
private val FOOTER_HEIGHT = 24.dp
private val HP_WIDTH = 96.dp
private val TEAM_STRIPE = 2.dp
private val ROW_SHAPE = RoundedCornerShape(6.dp)
private val CARD_SHAPE = RoundedCornerShape(6.dp + CARD_PADDING)

val OVERLAY_CARD_WIDTH = 280.dp
val OVERLAY_CARD_MAX_HEIGHT = CARD_PADDING * 2 + (ROW_HEIGHT + ROW_GAP) * OVERLAY_MAX_ROWS + FOOTER_HEIGHT

@Composable
fun OverlayCard(model: OverlayModel, opacity: Float, modifier: Modifier = Modifier) {
    val rowColor = AshColors.ash1.copy(alpha = opacity)
    Column(
        modifier.width(OVERLAY_CARD_WIDTH)
            .clip(CARD_SHAPE)
            .background(AshColors.ash0.copy(alpha = opacity))
            .border(1.dp, AshColors.text1.copy(alpha = 0.06f), CARD_SHAPE)
            .padding(CARD_PADDING)
            .animateContentSize(tween(AshMotion.MEDIUM, easing = AshMotion.standard)),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        model.enemies.forEach { enemy ->
            key(enemy.number) { EnemyRow(enemy, rowColor) }
        }
        if (model.more > 0 || model.dead.isNotEmpty()) Footer(model)
    }
}

@Composable
private fun EnemyRow(enemy: EnemySummary, base: Color) {
    val background = rememberCooling(Triple(enemy.hp, enemy.condition, enemy.distanceM), enabled = true, base)
    Box(
        Modifier.fillMaxWidth().height(ROW_HEIGHT)
            .clip(ROW_SHAPE)
            .background(background)
            .drawBehind { if (!enemy.seenByYou) drawRect(AshColors.team, size = Size(TEAM_STRIPE.toPx(), size.height)) }
            .padding(horizontal = ROW_INSET),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(HP_WIDTH).alignByBaseline()) { Hp(enemy) }
            AshText("Противник ${enemy.number}", Ash.type.bodyStrong, Modifier.weight(1f).alignByBaseline())
            enemy.distanceM?.let { AshText(Format.meters(it), Ash.type.secondary, Modifier.alignByBaseline(), AshColors.text2) }
        }
    }
}

@Composable
private fun Hp(enemy: EnemySummary) {
    val color = hpColor(enemy)
    val hp = enemy.hp
    when {
        enemy.condition == Condition.KNOCKED -> AshText("Нокнут", Ash.type.section, color = color)
        hp == null -> AshText("−", Ash.type.numOverlay, color = color)
        else -> Row {
            AnimatedHp(hp, Ash.type.numOverlay, color, Modifier.alignByBaseline())
            AshText("HP", Ash.type.caption, Modifier.alignByBaseline().padding(start = 4.dp), AshColors.text3)
        }
    }
}

@Composable
private fun Footer(model: OverlayModel) {
    Row(Modifier.fillMaxWidth().height(FOOTER_HEIGHT).padding(horizontal = ROW_INSET), verticalAlignment = Alignment.CenterVertically) {
        if (model.more > 0) AshText("ещё ${model.more}", Ash.type.label, color = AshColors.text3)
        Spacer(Modifier.weight(1f))
        if (model.dead.isNotEmpty()) {
            AshText("${if (model.dead.size == 1) "Убит" else "Убиты"}: ${model.dead.joinToString(", ")}", Ash.type.label, color = AshColors.text3)
        }
    }
}
