package xyz.thewhish.rustopsy.ui.radar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.thewhish.rustopsy.core.fight.Actor
import xyz.thewhish.rustopsy.core.fight.Condition
import xyz.thewhish.rustopsy.core.fight.Enemy
import xyz.thewhish.rustopsy.core.fight.Fight
import xyz.thewhish.rustopsy.core.fight.Outcome
import xyz.thewhish.rustopsy.core.fight.isRejected
import xyz.thewhish.rustopsy.core.log.HitArea
import xyz.thewhish.rustopsy.ui.Format
import xyz.thewhish.rustopsy.ui.components.AnimatedHp
import xyz.thewhish.rustopsy.ui.components.HpBar
import xyz.thewhish.rustopsy.ui.components.Marker
import xyz.thewhish.rustopsy.ui.components.MarkerShape
import xyz.thewhish.rustopsy.ui.components.Panel
import xyz.thewhish.rustopsy.ui.components.PanelHeader
import xyz.thewhish.rustopsy.ui.components.rememberCooling
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshShapes
import xyz.thewhish.rustopsy.ui.theme.AshSpace
import xyz.thewhish.rustopsy.ui.theme.AshText
import kotlin.time.Duration.Companion.milliseconds

private const val MAX_HP = 100.0
private const val ARRIVAL_STAGGER_MS = 40L
private val ARRIVAL_SHIFT = 4.dp

@Composable
fun EnemiesPanel(enemies: List<Enemy>, selected: Int, onSelect: (Int) -> Unit) {
    val initial = remember { enemies.mapTo(HashSet()) { it.number } }
    val firstNew = enemies.indexOfFirst { it.number !in initial }
    Column(
        Modifier.fillMaxWidth().clip(AshShapes.panel).background(AshColors.ash0).animateContentSize(tween(AshMotion.LONG, easing = AshMotion.enter)),
        verticalArrangement = Arrangement.spacedBy(AshSpace.hair),
    ) {
        enemies.forEachIndexed { index, enemy ->
            key(enemy.number) {
                val arrival = if (enemy.number in initial) null else index - firstNew
                EnemyRow(enemy.summary(), enemy.number == selected, arrival) { onSelect(enemy.number) }
            }
        }
    }
}

@Composable
private fun EnemyRow(summary: EnemySummary, selected: Boolean, arrival: Int?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val appear = remember { Animatable(if (arrival == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (arrival == null) return@LaunchedEffect
        delay((ARRIVAL_STAGGER_MS * arrival).milliseconds)
        appear.animateTo(1f, tween(AshMotion.LONG, easing = AshMotion.enter))
    }
    val background by animateColorAsState(
        when {
            selected -> AshColors.ash2
            hovered -> AshColors.ash2.copy(alpha = 0.6f).compositeOver(AshColors.ash1)
            else -> AshColors.ash1
        },
        tween(AshMotion.MICRO, easing = AshMotion.standard),
    )
    val cooled = rememberCooling(summary.number, arrival != null, background)
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * ARRIVAL_SHIFT.toPx()
            }
            .background(cooled)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(start = AshSpace.xl, end = AshSpace.m, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AshSpace.xl),
    ) {
        Column(Modifier.width(190.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AshText("Противник ${summary.number}", Ash.type.bodyStrong)
                if (summary.deaths > 1 || (summary.deaths == 1 && summary.condition != Condition.DEAD)) DeathsChip(summary.deaths)
            }
            AshText(enemyMeta(summary), Ash.type.secondary, color = AshColors.text2)
        }
        HpCell(summary, Modifier.weight(1f))
        StatCell("Твой урон", Format.oneDecimal(summary.dealt), AshColors.text1)
        StatCell("Чужой урон", Format.oneDecimal(summary.others), if (summary.others > 0) AshColors.text2 else AshColors.text3)
        StatCell("Отхил", if (summary.healed > 0) "+${Format.oneDecimal(summary.healed)}" else "0", if (summary.healed > 0) AshColors.warn else AshColors.text1)
        StatCell("Invalid", summary.invalid.toString(), if (summary.invalid > 0) AshColors.bad else AshColors.text1, Modifier.width(84.dp))
    }
}

private fun enemyMeta(summary: EnemySummary): String {
    val parts = listOfNotNull(
        summary.weapon ?: "оружие неизвестно",
        summary.distanceM?.let(Format::meters),
    )
    return parts.joinToString(", ")
}

@Composable
private fun DeathsChip(deaths: Int) {
    Box(Modifier.clip(AshShapes.chip).background(AshColors.ash3).padding(horizontal = 6.dp, vertical = 1.dp)) {
        AshText("убит ${Format.times(deaths)}", Ash.type.caption, color = AshColors.text2)
    }
}

@Composable
private fun HpCell(summary: EnemySummary, modifier: Modifier) {
    val hp = summary.hp
    val color = when {
        summary.condition == Condition.DEAD -> AshColors.text3
        summary.condition == Condition.KNOCKED -> AshColors.ok
        hp == null -> AshColors.text3
        hp <= 25 -> AshColors.ok
        hp <= 60 -> AshColors.warn
        else -> AshColors.text1
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AshSpace.l)) {
        Column(Modifier.widthIn(min = 108.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                when {
                    summary.condition == Condition.DEAD -> AshText("Убит", Ash.type.numXL, color = color)
                    summary.condition == Condition.KNOCKED -> AshText("Нокнут", Ash.type.numXL, color = color)
                    hp == null -> AshText("−", Ash.type.numXL, color = color)
                    else -> {
                        AnimatedHp(hp, Ash.type.numXL, color)
                        AshText("HP", Ash.type.label.copy(fontWeight = Ash.type.caption.fontWeight), Modifier.padding(start = AshSpace.xs, bottom = 4.dp), AshColors.text3)
                    }
                }
            }
            conditionNote(summary)?.let { AshText(it, Ash.type.caption, color = AshColors.text3) }
        }
        val barHp = if (summary.condition == Condition.DEAD) 0.0 else hp ?: 0.0
        HpBar((barHp / MAX_HP).toFloat(), color, Modifier.weight(1f))
    }
}

private fun conditionNote(summary: EnemySummary): String? = when (summary.condition) {
    Condition.DEAD -> when {
        summary.killedBy == Actor.YOU && summary.knockedBy == Actor.OTHERS -> "добит тобой"
        summary.killedBy == Actor.YOU -> "тобой"
        else -> null
    }
    Condition.KNOCKED -> listOfNotNull(
        summary.hp?.let { "${Format.oneDecimal(it)} HP" },
        when (summary.knockedBy) {
            Actor.YOU -> "тобой"
            Actor.OTHERS -> "не тобой"
            null -> null
        },
    ).joinToString(", ").ifEmpty { null }
    Condition.ALIVE -> null
}

@Composable
private fun StatCell(label: String, value: String, color: Color, modifier: Modifier = Modifier.width(96.dp)) {
    Column(modifier) {
        AshText(label, Ash.type.label, color = AshColors.text3)
        AshText(value, Ash.type.numM, color = color)
    }
}

@Composable
fun EventsPanel(enemy: Enemy, referenceSec: Double, modifier: Modifier = Modifier) {
    val lines = remember(enemy) { enemy.eventLines() }
    val firstShownAt = remember(enemy.number) { lines.firstOrNull()?.timeSec ?: Double.MAX_VALUE }
    val listState = remember(enemy.number) { LazyListState() }
    remember(lines.firstOrNull()?.key) {
        Snapshot.withoutReadObservation {
            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) listState.requestScrollToItem(0)
        }
    }
    Panel(modifier.fillMaxWidth()) {
        PanelHeader("События с противником ${enemy.number}", Format.events(lines.size))
        LazyColumn(Modifier.fillMaxSize().padding(start = AshSpace.s, end = AshSpace.s, bottom = AshSpace.s), listState) {
            itemsIndexed(lines, key = { _, line -> line.key }) { index, line ->
                EventRow(
                    line,
                    referenceSec,
                    (lines.size - index) % 2 == 0,
                    isNew = line.timeSec > firstShownAt,
                    Modifier.animateItem(
                        fadeInSpec = tween(AshMotion.LONG, easing = AshMotion.enter),
                        placementSpec = tween(AshMotion.MEDIUM, easing = AshMotion.standard),
                        fadeOutSpec = tween(AshMotion.SHORT, easing = AshMotion.exit),
                    ),
                )
            }
        }
    }
}

@Composable
private fun EventRow(line: EventLine, referenceSec: Double, even: Boolean, isNew: Boolean, modifier: Modifier) {
    val base = if (even) AshColors.ash2.copy(alpha = 0.6f).compositeOver(AshColors.ash1) else AshColors.ash1
    val background = rememberCooling(line, isNew, base)
    Row(
        modifier.fillMaxWidth().height(40.dp).clip(AshShapes.control).background(background).padding(horizontal = AshSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AshSpace.m),
    ) {
        AshText(Format.relativeSeconds(referenceSec - line.timeSec), Ash.type.secondary, Modifier.width(70.dp), AshColors.text3)
        val (shape, color) = marker(line.kind)
        Marker(shape, color, Modifier.width(16.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AshSpace.s)) {
            AshText(eventTitle(line), Ash.type.body)
            eventDetail(line)?.let { AshText(it, Ash.type.secondary, color = AshColors.text2) }
            line.code?.let { CodeChip(it, AshColors.text2) }
            outcomeNote(line)?.let { (text, color) -> CodeChip(text, color) }
        }
        AshText(
            if (line.hpBefore != null && line.hpAfter != null) "${Format.oneDecimal(line.hpBefore)} → ${Format.oneDecimal(line.hpAfter)}" else "",
            Ash.type.secondary,
            Modifier.width(120.dp),
            AshColors.text2,
        )
        val (result, resultColor) = eventResult(line)
        AshText(result, Ash.type.bodyStrong.copy(textAlign = TextAlign.End), Modifier.width(70.dp), resultColor)
    }
}

private fun marker(kind: EventKind): Pair<MarkerShape, Color> = when (kind) {
    EventKind.Hit -> MarkerShape.Dot to AshColors.ok
    EventKind.Rejected -> MarkerShape.Cross to AshColors.bad
    EventKind.Incoming -> MarkerShape.Ring to AshColors.incoming
    EventKind.Heal -> MarkerShape.Square to AshColors.warn
    EventKind.OtherDamage, EventKind.KnockedByOthers -> MarkerShape.Square to AshColors.text3
}

private fun eventTitle(line: EventLine): String = when (line.kind) {
    EventKind.Hit, EventKind.Rejected -> listOf(shotName(line), Format.areaTarget(line.area)).filter { it.isNotEmpty() }.joinToString(" ")
    EventKind.Incoming -> listOf("По тебе:", shotName(line), Format.areaTarget(line.area)).filter { it.isNotEmpty() }.joinToString(" ")
    EventKind.Heal -> "Захилился"
    EventKind.OtherDamage -> "Урон не от тебя"
    EventKind.KnockedByOthers -> "Нокнут не тобой"
}

private fun shotName(line: EventLine): String = if (line.count > 1) "${line.weapon} ×${line.count}" else line.weapon

private fun eventDetail(line: EventLine): String? = when (line.kind) {
    EventKind.Rejected -> "не засчитано"
    EventKind.Hit, EventKind.Incoming -> line.distanceM?.let(Format::meters)
    EventKind.Heal, EventKind.OtherDamage, EventKind.KnockedByOthers -> null
}

private fun outcomeNote(line: EventLine): Pair<String, Color>? {
    val incoming = line.kind == EventKind.Incoming
    return when (line.outcome) {
        Outcome.KNOCK -> if (incoming) "нокнул тебя" to AshColors.bad else "нокаут" to AshColors.ok
        Outcome.KILL -> if (incoming) "убил тебя" to AshColors.bad else "убит" to AshColors.ok
        Outcome.FINISH -> if (incoming) "добил тебя" to AshColors.bad else "добит" to AshColors.ok
        Outcome.DOWNED -> if (incoming) "по тебе в нокауте" to AshColors.text2 else "по нокнутому" to AshColors.text2
        Outcome.HIT, Outcome.REJECTED, null -> null
    }
}

private fun eventResult(line: EventLine): Pair<String, Color> = when (line.kind) {
    EventKind.Hit -> Format.signedDamage(line.amount ?: 0.0) to AshColors.text1
    EventKind.Rejected -> "✕" to AshColors.bad
    EventKind.Incoming -> Format.signedDamage(line.amount ?: 0.0) to AshColors.incoming
    EventKind.Heal -> "+${Format.oneDecimal(line.amount ?: 0.0)}" to AshColors.warn
    EventKind.OtherDamage, EventKind.KnockedByOthers -> Format.oneDecimal(line.amount ?: 0.0) to AshColors.text2
}

@Composable
private fun CodeChip(text: String, color: Color) {
    Box(Modifier.clip(AshShapes.chip).background(AshColors.ash3).padding(horizontal = 6.dp, vertical = 2.dp)) {
        AshText(text, Ash.type.caption, color = color)
    }
}

@Composable
fun ZonesPanel(enemy: Enemy) {
    val hits = remember(enemy) { enemy.lives.flatMap { it.hits }.map { it.event.row } }
    val zones = listOf(HitArea.HEAD, HitArea.CHEST, HitArea.STOMACH, HitArea.ARM, HitArea.HAND, HitArea.LEG, HitArea.FOOT)
    Panel(Modifier.fillMaxWidth()) {
        PanelHeader("Попадания по зонам", "противник ${enemy.number}")
        Column(Modifier.padding(start = AshSpace.xl, end = AshSpace.xl, bottom = AshSpace.l), verticalArrangement = Arrangement.spacedBy(AshSpace.s)) {
            zones.forEach { zone ->
                val inZone = hits.filter { it.area == zone }
                val valid = inZone.count { !it.isRejected }
                val rejected = inZone.size - valid
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AshText(Format.areaName(zone).replaceFirstChar { it.uppercase() }, Ash.type.body, Modifier.weight(1f), if (inZone.isEmpty()) AshColors.text3 else AshColors.text1)
                    ZoneCount(MarkerShape.Dot, AshColors.ok, valid)
                    Spacer(Modifier.width(AshSpace.l))
                    ZoneCount(MarkerShape.Cross, AshColors.bad, rejected)
                }
            }
        }
    }
}

@Composable
private fun ZoneCount(shape: MarkerShape, color: Color, count: Int) {
    Row(Modifier.width(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AshSpace.xs)) {
        Marker(shape, if (count > 0) color else AshColors.text4)
        AshText(count.toString(), Ash.type.bodyMedium, color = if (count > 0) AshColors.text1 else AshColors.text4)
    }
}

@Composable
fun YouPanel(fight: Fight, possibleLoss: Boolean) {
    val totals = remember(fight) { fight.totals() }
    Panel(Modifier.fillMaxWidth()) {
        PanelHeader("Ты в этом бою")
        Column(Modifier.padding(start = AshSpace.xl, end = AshSpace.xl, bottom = AshSpace.l), verticalArrangement = Arrangement.spacedBy(AshSpace.s)) {
            YouLine("Нанесено урона", Format.oneDecimal(totals.dealt), AshColors.text1)
            YouLine("Получено урона", Format.oneDecimal(fight.damageTaken), AshColors.incoming)
            YouLine("Нокаутов", totals.knocks.toString(), if (totals.knocks > 0) AshColors.ok else AshColors.text1)
            YouLine("Убийств", totals.kills.toString(), if (totals.kills > 0) AshColors.ok else AshColors.text1)
            if (totals.validHits > 0) YouLine("В голову", Format.percent(totals.headshots, totals.validHits), AshColors.text1)
            val (outcome, outcomeColor) = when (fight.you) {
                Condition.DEAD -> "Ты погиб" to AshColors.bad
                Condition.KNOCKED -> "Ты в нокауте" to AshColors.warn
                Condition.ALIVE -> "Выжил" to AshColors.ok
            }
            YouLine("Исход", outcome, outcomeColor)
            val hitsOnYouByEnemy = fight.enemies.flatMap { enemy -> enemy.incoming.map { enemy.number to it } }.sortedBy { it.second.event.timeSec }
            hitsOnYouByEnemy.lastOrNull { it.second.outcome == Outcome.KNOCK }?.let { YouLine("Нокнул тебя", "Противник ${it.first}", AshColors.text1) }
            hitsOnYouByEnemy.lastOrNull { it.second.condition == Condition.DEAD }?.let { YouLine("Убил тебя", "Противник ${it.first}", AshColors.text1) }
            if (possibleLoss) {
                Spacer(Modifier.height(AshSpace.xs))
                Box(Modifier.fillMaxWidth().clip(AshShapes.control).background(AshColors.warnSoft).padding(AshSpace.m)) {
                    AshText("Часть событий могла не попасть в лог: вызывай combatlog чаще в долгих боях", Ash.type.secondary, color = AshColors.warn, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun YouLine(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AshText(label, Ash.type.body, Modifier.weight(1f), AshColors.text2)
        AshText(value, Ash.type.bodyStrong, color = color)
    }
}
