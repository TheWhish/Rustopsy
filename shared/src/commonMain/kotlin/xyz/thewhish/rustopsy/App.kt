package xyz.thewhish.rustopsy

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import xyz.thewhish.rustopsy.session.SessionState
import xyz.thewhish.rustopsy.ui.radar.RadarScreen
import xyz.thewhish.rustopsy.ui.settings.SettingsContext
import xyz.thewhish.rustopsy.ui.settings.SettingsScreen
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshShapes
import xyz.thewhish.rustopsy.ui.theme.AshSpace
import xyz.thewhish.rustopsy.ui.theme.AshText
import xyz.thewhish.rustopsy.ui.theme.AshTheme
import xyz.thewhish.rustopsy.team.TeamAccess
import xyz.thewhish.rustopsy.ui.components.LocalAvatars
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

val BASE_WIDTH = 1440.dp
val BASE_HEIGHT = 810.dp

private const val TICK_WAITING_MS = 100L
private const val TICK_IDLE_MS = 1_000L

enum class Screen(val title: String) { Radar("Радар"), History("История"), Statistics("Статистика"), Settings("Настройки") }

@Composable
fun App(
    session: StateFlow<SessionState>,
    utcOffsetSec: Int,
    settings: SettingsContext,
    team: TeamAccess,
    avatars: suspend (String) -> ImageBitmap?,
) {
    AshTheme {
        ScaledCanvas(avatars) {
            val state by session.collectAsState()
            val nowSec by produceState(epochNow(), state.hidden) {
                while (true) {
                    value = epochNow()
                    val waiting = state.hidden?.let { value < it.untilSec } == true
                    delay((if (waiting) TICK_WAITING_MS else TICK_IDLE_MS).milliseconds)
                }
            }
            var screen by rememberSaveable { mutableStateOf(Screen.Radar) }
            Row(Modifier.fillMaxSize().background(AshColors.ash0)) {
                Rail(screen) { screen = it }
                AnimatedContent(
                    targetState = screen,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(top = AshSpace.m, end = AshSpace.m, bottom = AshSpace.m),
                    transitionSpec = {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (fadeIn(tween(AshMotion.SHORT)) + slideInHorizontally(tween(AshMotion.MEDIUM, easing = AshMotion.standard)) { direction * 8 })
                            .togetherWith(fadeOut(tween(120)))
                    },
                ) { target ->
                    when (target) {
                        Screen.Radar -> RadarScreen(state, settings.gameLog != null, nowSec, utcOffsetSec, team, settings.account)
                        Screen.Settings -> SettingsScreen(settings)
                        else -> ComingLater(target)
                    }
                }
            }
        }
    }
}

private fun epochNow(): Double = Clock.System.now().let { it.epochSeconds + it.nanosecondsOfSecond / 1e9 }

@Composable
private fun ScaledCanvas(avatars: suspend (String) -> ImageBitmap?, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(AshColors.ash0), contentAlignment = Alignment.Center) {
        val scale = min(maxWidth / BASE_WIDTH, maxHeight / BASE_HEIGHT)
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale), LocalAvatars provides avatars) {
            Box(Modifier.size(BASE_WIDTH, BASE_HEIGHT)) { content() }
        }
    }
}

@Composable
private fun Rail(selected: Screen, onSelect: (Screen) -> Unit) {
    val itemStep = 50.dp
    val dotOffset by animateDpAsState(itemStep * selected.ordinal, tween(AshMotion.MEDIUM, easing = AshMotion.standard))
    Column(Modifier.width(68.dp).fillMaxHeight().padding(vertical = AshSpace.l), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(36.dp).clip(AshShapes.control).background(AshColors.emberStrong), contentAlignment = Alignment.Center) {
            AshText("R", Ash.type.section, color = AshColors.onEmber)
        }
        Spacer(Modifier.height(AshSpace.l))
        Box {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Screen.entries.filter { it != Screen.Settings }.forEach { screen ->
                    RailItem(screen, screen == selected) { onSelect(screen) }
                }
            }
            if (selected != Screen.Settings) {
                Box(Modifier.offset(x = (-8).dp, y = 19.dp + dotOffset).size(6.dp).clip(CircleShape).background(AshColors.ember))
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            RailItem(Screen.Settings, selected == Screen.Settings) { onSelect(Screen.Settings) }
            if (selected == Screen.Settings) {
                Box(Modifier.offset(x = (-8).dp, y = 19.dp).size(6.dp).clip(CircleShape).background(AshColors.ember))
            }
        }
    }
}

@Composable
private fun RailItem(screen: Screen, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (active || hovered) AshColors.ash1 else AshColors.ash0, tween(AshMotion.MICRO))
    val iconColor by animateColorAsState(
        when {
            active -> AshColors.text1
            hovered -> AshColors.text2
            else -> AshColors.text3
        },
        tween(AshMotion.MICRO),
    )
    Box(
        Modifier.size(44.dp).clip(AshShapes.control).background(background)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) { drawRailIcon(screen, iconColor) }
    }
}

private fun DrawScope.drawRailIcon(screen: Screen, color: Color) {
    val unit = size.width / 24f
    val stroke = Stroke(1.6f * unit, cap = StrokeCap.Round)
    fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)
    when (screen) {
        Screen.Radar -> {
            drawCircle(color, 9f * unit, p(12f, 12f), style = stroke)
            drawCircle(color, 4.5f * unit, p(12f, 12f), style = stroke)
            line(12f, 12f, 18.5f, 5.5f)
        }
        Screen.History -> {
            line(8f, 6f, 21f, 6f)
            line(8f, 12f, 21f, 12f)
            line(8f, 18f, 21f, 18f)
            line(3f, 6f, 3.01f, 6f)
            line(3f, 12f, 3.01f, 12f)
            line(3f, 18f, 3.01f, 18f)
        }
        Screen.Statistics -> {
            line(6f, 20f, 6f, 12f)
            line(12f, 20f, 12f, 4f)
            line(18f, 20f, 18f, 9f)
        }
        Screen.Settings -> {
            line(4f, 7f, 6.5f, 7f)
            line(11.5f, 7f, 20f, 7f)
            line(4f, 17f, 12.5f, 17f)
            line(17.5f, 17f, 20f, 17f)
            drawCircle(color, 2.5f * unit, p(9f, 7f), style = stroke)
            drawCircle(color, 2.5f * unit, p(15f, 17f), style = stroke)
        }
    }
}

@Composable
private fun ComingLater(screen: Screen) {
    Box(Modifier.fillMaxSize().clip(AshShapes.panel).background(AshColors.ash1), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AshSpace.s)) {
            AshText(screen.title, Ash.type.title)
            AshText("Раздел появится в следующих этапах. Сейчас всё на Радаре", Ash.type.secondary, color = AshColors.text2)
        }
    }
}
