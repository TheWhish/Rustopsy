package xyz.thewhish.rustopsy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshShapes
import xyz.thewhish.rustopsy.ui.theme.AshSpace

private val POPOVER_MAX_HEIGHT = 700.dp

@Composable
fun BoxScope.Popover(open: Boolean, onClose: () -> Unit, top: Dp, width: Dp, content: @Composable ColumnScope.() -> Unit) {
    if (open) {
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose))
    }
    AnimatedVisibility(
        visible = open,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = top),
        enter = fadeIn(tween(AshMotion.SHORT)) + slideInVertically(tween(AshMotion.MEDIUM, easing = AshMotion.standard)) { -it / 24 },
        exit = fadeOut(tween(AshMotion.SHORT)) + slideOutVertically(tween(AshMotion.SHORT, easing = AshMotion.exit)) { -it / 24 },
    ) {
        Column(
            Modifier.width(width).heightIn(max = POPOVER_MAX_HEIGHT).clip(AshShapes.panel).background(AshColors.ash1)
                .border(1.dp, AshColors.ash3, AshShapes.panel)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(AshSpace.xl),
            verticalArrangement = Arrangement.spacedBy(AshSpace.l),
            content = content,
        )
    }
}
