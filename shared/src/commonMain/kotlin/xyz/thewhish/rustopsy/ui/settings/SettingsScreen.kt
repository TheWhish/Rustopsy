package xyz.thewhish.rustopsy.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import xyz.thewhish.rustopsy.account.AccountAccess
import xyz.thewhish.rustopsy.ui.components.Panel
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshShapes
import xyz.thewhish.rustopsy.ui.theme.AshSpace
import xyz.thewhish.rustopsy.ui.theme.AshText

class SettingsContext(val gameLog: String?, val dataDir: String, val version: String, val binds: BindsAccess?, val account: AccountAccess)

enum class SettingsSection(val title: String, val description: String) {
    Capture("Захват лога", "Источники боевого лога и их состояние"),
    Binds("Бинды", "Отмеченные клавиши вызывают combatlog вместе со своим обычным действием. Чем больше клавиш выбрано, тем чаще обновляется лог и тем быстрее и точнее Радар"),
    Account("Аккаунт", "Вход через Steam для команды и статистики"),
    About("О программе", "Версия, данные и безопасность аккаунта"),
}

private const val SAFETY_STATEMENT =
    "Rustopsy работает как обычное приложение Windows и не взаимодействует с процессом игры: не читает и не изменяет его память, " +
        "не внедряет код, не эмулирует нажатия и не выводит изображение поверх игры. Боевые данные берутся из текстового лога, " +
        "который клиент Rust сам записывает на диск по команде combatlog, доступной каждому игроку. Easy Anti-Cheat " +
        "контролирует вмешательство в процесс игры, а Rustopsy такого вмешательства не выполняет."

private val NAV_ITEM = 40.dp
private val NAV_GAP = 4.dp
private val SECTION_SHIFT = 8.dp

@Composable
fun SettingsScreen(context: SettingsContext) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.Capture) }
    val shift = with(LocalDensity.current) { SECTION_SHIFT.roundToPx() }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(AshSpace.m)) {
        SectionNav(section) { section = it }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            AnimatedContent(
                targetState = section,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(AshMotion.SHORT)) + slideInVertically(tween(AshMotion.MEDIUM, easing = AshMotion.standard)) { direction * shift })
                        .togetherWith(fadeOut(tween(120)))
                },
            ) { target ->
                Column(Modifier.fillMaxSize().padding(horizontal = AshSpace.xxxl, vertical = AshSpace.xxl)) {
                    SectionHeader(target)
                    when (target) {
                        SettingsSection.Capture -> CaptureSection(context.gameLog)
                        SettingsSection.Binds -> BindsSection(context.binds)
                        SettingsSection.Account -> AccountSection(context.account)
                        SettingsSection.About -> AboutSection(context)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionNav(selected: SettingsSection, onSelect: (SettingsSection) -> Unit) {
    val dotY by animateDpAsState((NAV_ITEM + NAV_GAP) * selected.ordinal + NAV_ITEM / 2 - 3.dp, tween(AshMotion.MEDIUM, easing = AshMotion.standard))
    Panel(Modifier.width(220.dp).fillMaxHeight()) {
        AshText("Настройки", Ash.type.title, Modifier.padding(start = AshSpace.xl, top = AshSpace.xxl, bottom = AshSpace.l))
        Box(Modifier.padding(horizontal = AshSpace.s)) {
            Column(verticalArrangement = Arrangement.spacedBy(NAV_GAP)) {
                SettingsSection.entries.forEach { section -> NavItem(section, section == selected) { onSelect(section) } }
            }
            Box(Modifier.offset(x = 12.dp, y = dotY).size(6.dp).clip(CircleShape).background(AshColors.ember))
        }
    }
}

@Composable
private fun NavItem(section: SettingsSection, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            active -> AshColors.ash2
            hovered -> AshColors.ash2.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        tween(AshMotion.MICRO, easing = AshMotion.standard),
    )
    Box(
        Modifier.fillMaxWidth().height(NAV_ITEM).clip(AshShapes.control).background(background)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .padding(start = 28.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        AshText(section.title, if (active) Ash.type.bodyStrong else Ash.type.body, color = if (active) AshColors.text1 else AshColors.text2)
    }
}

@Composable
private fun SectionHeader(section: SettingsSection) {
    AshText(section.title, Ash.type.title)
    Spacer(Modifier.height(AshSpace.xs))
    AshText(section.description, Ash.type.secondary, Modifier.widthIn(max = 680.dp), AshColors.text2, maxLines = 3)
    Spacer(Modifier.height(AshSpace.xxl))
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = AshSpace.xxl)) {
        AshText(title, Ash.type.section, Modifier.padding(bottom = AshSpace.xs))
        content()
    }
}

@Composable
fun SettingRow(title: String, hint: String? = null, trailing: @Composable () -> Unit = {}) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = AshSpace.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = AshSpace.xl)) {
                AshText(title, Ash.type.body)
                if (hint != null) AshText(hint, Ash.type.label, color = AshColors.text3, maxLines = 3)
            }
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AshColors.ash2))
    }
}

@Composable
fun StatusChip(text: String, color: Color, background: Color) {
    Row(
        Modifier.clip(AshShapes.chip).background(background).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        AshText(text, Ash.type.caption, color = color)
    }
}

@Composable
fun Notice(text: String, color: Color, background: Color) {
    Box(Modifier.fillMaxWidth().padding(bottom = AshSpace.s).clip(AshShapes.control).background(background).padding(AshSpace.m)) {
        AshText(text, Ash.type.secondary, color = color, maxLines = 3)
    }
}

@Composable
private fun CaptureSection(gameLog: String?) {
    SettingsGroup("Источник") {
        SettingRow("Лог игры", gameLog ?: "output_log.txt не найден. Запусти Rust один раз, чтобы игра его создала") {
            if (gameLog != null) StatusChip("Подключён", AshColors.ok, AshColors.okSoft) else StatusChip("Не найден", AshColors.warn, AshColors.warnSoft)
        }
        SettingRow("Буфер обмена", "Запасной канал. Читается только текст боевого лога, остальное не трогается") {
            StatusChip("Включён", AshColors.ok, AshColors.okSoft)
        }
    }
}

@Composable
private fun ColumnScope.AboutSection(context: SettingsContext) {
    SettingsGroup("Rustopsy") {
        SettingRow("Версия") { AshText(context.version, Ash.type.bodyStrong, color = AshColors.text2) }
        SettingRow("Папка данных", context.dataDir)
        SettingRow("Шрифт", "Geologica, SIL Open Font License 1.1")
    }
    SettingsGroup("Безопасность аккаунта") {
        AshText(SAFETY_STATEMENT, Ash.type.body, Modifier.widthIn(max = 720.dp).padding(top = AshSpace.s), AshColors.text2, maxLines = 8)
    }
    Spacer(Modifier.weight(1f))
    AshText("Rustopsy не связан с Facepunch Studios", Ash.type.label, color = AshColors.text3)
}
