package xyz.thewhish.rustopsy.ui.radar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.thewhish.rustopsy.account.AccountAccess
import xyz.thewhish.rustopsy.core.fight.Fight
import xyz.thewhish.rustopsy.core.fight.FightAnalyzer
import xyz.thewhish.rustopsy.core.session.HiddenWindow
import xyz.thewhish.rustopsy.session.SessionState
import xyz.thewhish.rustopsy.session.TeamFights
import xyz.thewhish.rustopsy.team.TeamAccess
import xyz.thewhish.rustopsy.ui.overlay.OverlayAccess
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

private const val MAX_FIGHT_TABS = 4
private val TOP_BAR_HEIGHT = 56.dp

@Composable
fun RadarScreen(
    session: SessionState,
    teamFights: TeamFights,
    logFound: Boolean,
    nowSec: Double,
    utcOffsetSec: Int,
    team: TeamAccess,
    account: AccountAccess,
    overlay: OverlayAccess,
) {
    val teamStatus by team.status.collectAsState()
    val overlaySettings by overlay.settings.collectAsState()
    val (fights, names) = teamFights
    var open by remember { mutableStateOf<TopPanel?>(null) }
    fun toggle(panel: TopPanel) {
        open = if (open == panel) null else panel
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AshSpace.m)) {
            TopBar(logFound, session.hidden) {
                OverlayButton(overlaySettings.enabled, open == TopPanel.Overlay) { toggle(TopPanel.Overlay) }
                TeamButton(teamStatus, open == TopPanel.Team) { toggle(TopPanel.Team) }
            }
            if (fights.isEmpty()) {
                EmptyFightView()
            } else {
                FightView(fights, session, nowSec, utcOffsetSec, names)
            }
        }
        val top = TOP_BAR_HEIGHT + AshSpace.s
        TeamOverlay(open == TopPanel.Team, { open = null }, team, account, top)
        OverlayPanel(open == TopPanel.Overlay, { open = null }, overlay, top)
    }
}

private enum class TopPanel { Team, Overlay }

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
private fun TopBar(logFound: Boolean, hidden: HiddenWindow?, trailing: @Composable RowScope.() -> Unit) {
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
                if (hidden != null) HiddenChip(hidden.count)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AshSpace.s), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

@Composable
private fun HiddenChip(count: Int) {
    Row(
        Modifier.height(36.dp).clip(AshShapes.control).background(AshColors.ash2).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AshSpace.s),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(AshColors.warn))
        AshText("Сервер скрывает ${Format.events(count)}", Ash.type.body, color = AshColors.text2)
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
