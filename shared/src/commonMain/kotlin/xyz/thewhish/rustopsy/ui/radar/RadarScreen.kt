package xyz.thewhish.rustopsy.ui.radar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.thewhish.rustopsy.account.AccountAccess
import xyz.thewhish.rustopsy.core.fight.Fight
import xyz.thewhish.rustopsy.core.fight.FightAnalyzer
import xyz.thewhish.rustopsy.core.session.HiddenWindow
import xyz.thewhish.rustopsy.session.SessionState
import xyz.thewhish.rustopsy.team.TeamAccess
import xyz.thewhish.rustopsy.team.TeamStatus
import xyz.thewhish.rustopsy.ui.Format
import xyz.thewhish.rustopsy.ui.components.Panel
import xyz.thewhish.rustopsy.ui.components.PanelHeader
import xyz.thewhish.rustopsy.ui.components.SegmentedControl
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshShapes
import xyz.thewhish.rustopsy.ui.theme.AshSpace
import xyz.thewhish.rustopsy.ui.theme.AshText
import kotlin.math.ceil

private const val MAX_FIGHT_TABS = 4
private val TOP_BAR_HEIGHT = 56.dp

@Composable
fun RadarScreen(session: SessionState, logFound: Boolean, nowSec: Double, utcOffsetSec: Int, team: TeamAccess, account: AccountAccess) {
    val teamStatus by team.status.collectAsState()
    val teamEvents by team.events.collectAsState()
    val members = (teamStatus as? TeamStatus.Ready)?.state?.team?.members.orEmpty()
    val teammates = remember(members) { members.mapTo(HashSet()) { it.steamId } }
    val names = remember(members) { members.associate { it.steamId to it.name } }
    val fights = remember(session.events, teamEvents, teammates) {
        val events = if (teamEvents.isEmpty()) session.events else (session.events + teamEvents).sortedBy { it.timeSec }
        FightAnalyzer.analyze(events, teammates).asReversed()
    }
    var teamOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AshSpace.m)) {
            TopBar(logFound, session.hidden, nowSec) { TeamButton(teamStatus, teamOpen) { teamOpen = !teamOpen } }
            if (fights.isEmpty()) {
                EmptyFightView()
            } else {
                FightView(fights, session, nowSec, utcOffsetSec, names)
            }
        }
        TeamOverlay(teamOpen, { teamOpen = false }, team, account, top = TOP_BAR_HEIGHT + AshSpace.s)
    }
}

@Composable
private fun FightView(fights: List<Fight>, session: SessionState, nowSec: Double, utcOffsetSec: Int, names: Map<Long, String>) {
    var selectedFight by rememberSaveable { mutableStateOf(0) }
    val fightIndex = selectedFight.coerceIn(0, minOf(fights.size, MAX_FIGHT_TABS) - 1)
    val fight = fights[fightIndex]
    val current = fightIndex == 0 && nowSec - fight.endSec < FightAnalyzer.FIGHT_GAP_SEC

    Row(Modifier.fillMaxWidth().padding(horizontal = AshSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        AshText(if (current) "Текущий бой" else "Бой ${Format.ago(nowSec - fight.endSec)}", Ash.type.title)
        Spacer(Modifier.width(AshSpace.m))
        AshText("${Format.enemies(fight.enemies.size)}, бой длился ${Format.duration(fight.endSec - fight.startSec)}", Ash.type.secondary, color = AshColors.text3)
        Spacer(Modifier.weight(1f))
        if (fights.size > 1) {
            val labels = fights.take(MAX_FIGHT_TABS).mapIndexed { i, f ->
                if (i == 0 && nowSec - f.endSec < FightAnalyzer.FIGHT_GAP_SEC) "Текущий" else Format.clock(f.startSec, utcOffsetSec)
            }
            SegmentedControl(labels, fightIndex, { selectedFight = it })
        }
    }

    AnimatedContent(
        fight.startSec,
        transitionSpec = {
            fadeIn(tween(AshMotion.SHORT, delayMillis = AshMotion.MICRO, easing = AshMotion.enter)) togetherWith
                fadeOut(tween(AshMotion.MICRO, easing = AshMotion.exit))
        },
        label = "fight",
    ) { startSec ->
        val shown = fights.firstOrNull { it.startSec == startSec } ?: fight
        val live = shown === fights.first() && nowSec - shown.endSec < FightAnalyzer.FIGHT_GAP_SEC
        FightBody(shown, live, session, nowSec, names)
    }
}

@Composable
private fun FightBody(fight: Fight, current: Boolean, session: SessionState, nowSec: Double, names: Map<Long, String>) {
    var selectedEnemy by remember { mutableStateOf(fight.enemies.defaultSelection()) }
    val enemy = fight.enemies.firstOrNull { it.number == selectedEnemy } ?: fight.enemies.first()

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(AshSpace.m)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(AshSpace.m)) {
            EnemiesPanel(fight.enemies, enemy.number, names, { selectedEnemy = it })
            EventsPanel(enemy, if (current) nowSec else fight.endSec, names, Modifier.weight(1f))
        }
        Column(Modifier.width(352.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AshSpace.m)) {
            ZonesPanel(enemy)
            ContributionPanel(enemy, names)
            YouPanel(fight, session.gaps.any { it.overlaps(fight.startSec, fight.endSec) })
        }
    }
}

@Composable
private fun TopBar(logFound: Boolean, hidden: HiddenWindow?, nowSec: Double, trailing: @Composable () -> Unit) {
    val remaining = hidden?.let { it.untilSec - nowSec }
    Box(Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT).clip(AshShapes.panel).background(AshColors.ash1)) {
        Row(
            Modifier.fillMaxSize().padding(start = AshSpace.xl, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            AshText("Rustopsy", Ash.type.section.copy(fontSize = 16.sp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AshSpace.s)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (logFound) AshColors.ok else AshColors.warn))
                AshText(if (logFound) "Лог игры подключён" else "Лог игры не найден", Ash.type.body, color = AshColors.text2)
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = hidden != null,
                enter = expandHorizontally(tween(AshMotion.MEDIUM, easing = AshMotion.standard)) + fadeIn(tween(AshMotion.MEDIUM)),
                exit = shrinkHorizontally(tween(AshMotion.SHORT, easing = AshMotion.exit)) + fadeOut(tween(AshMotion.SHORT)),
            ) {
                if (hidden != null && remaining != null) WaitChip(hidden.count, remaining)
            }
            trailing()
        }
        if (hidden != null && remaining != null) {
            AnimatedVisibility(
                visible = remaining > 0,
                modifier = Modifier.align(Alignment.BottomStart),
                exit = fadeOut(tween(AshMotion.MEDIUM)),
            ) {
                Fuse((remaining / hidden.windowSec).toFloat())
            }
        }
    }
}

@Composable
private fun WaitChip(count: Int, remainingSec: Double) {
    val ready = remainingSec <= 0
    Row(
        Modifier.height(36.dp).clip(AshShapes.control).background(AshColors.ash2).padding(start = 5.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.widthIn(min = 42.dp).height(26.dp).clip(AshShapes.chip)
                .background(if (ready) AshColors.okSoft else AshColors.warnSoft)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            AshText(
                if (ready) "Готово" else Format.seconds(ceil(remainingSec).toLong()),
                Ash.type.bodyStrong,
                color = if (ready) AshColors.ok else AshColors.warn,
            )
        }
        AshText(
            if (ready) "Вызови combatlog, чтобы получить всё" else "Сервер покажет ещё ${Format.events(count)}",
            Ash.type.body,
            color = AshColors.text2,
        )
    }
}

@Composable
private fun Fuse(fraction: Float) {
    val width by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(100, easing = LinearEasing))
    val smoulder = rememberInfiniteTransition()
    val halo by smoulder.animateFloat(0f, 1f, infiniteRepeatable(tween(AshMotion.SMOULDER), RepeatMode.Reverse))
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val line = 3.dp.toPx()
        val x = size.width * width
        val top = size.height - line
        drawRoundRect(AshColors.warn, Offset(0f, top), Size(x, line), CornerRadius(2.dp.toPx()))
        val tip = Offset(x, top + line / 2)
        drawCircle(AshColors.ember.copy(alpha = 0.14f - 0.06f * halo), 3.dp.toPx() + (3 + halo).dp.toPx(), tip)
        drawCircle(AshColors.ember, 3.dp.toPx(), tip)
    }
}

@Composable
private fun EmptyFightView() {
    Row(Modifier.fillMaxWidth().padding(horizontal = AshSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        AshText("Текущий бой", Ash.type.title)
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(AshSpace.m)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(AshSpace.m)) {
            EmptyPanel("Противники", Modifier.fillMaxWidth())
            EmptyPanel("События", Modifier.fillMaxWidth().weight(1f))
        }
        Column(Modifier.width(352.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(AshSpace.m)) {
            EmptyPanel("Попадания по зонам", Modifier.fillMaxWidth())
            EmptyPanel("Ты в этом бою", Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun EmptyPanel(title: String, modifier: Modifier) {
    Panel(modifier) {
        PanelHeader(title)
        AshText("—", Ash.type.body, Modifier.padding(horizontal = AshSpace.xl, vertical = AshSpace.m), AshColors.text4)
    }
}
