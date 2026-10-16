package xyz.thewhish.rustopsy.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.thewhish.rustopsy.core.binds.KeyBinds
import xyz.thewhish.rustopsy.ui.components.AshButton
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshSpace
import xyz.thewhish.rustopsy.ui.theme.AshText
import kotlin.time.Duration.Companion.milliseconds

class BindsAccess(
    val location: String,
    val load: suspend () -> Map<String, String>,
    val save: suspend (selected: Set<String>, managed: Set<String>) -> String?,
    val gameRunning: suspend () -> Boolean,
)

private const val GAME_POLL_MS = 2_000L

private sealed interface SaveStatus {
    data object Idle : SaveStatus
    data object Saving : SaveStatus
    data class Saved(val backup: String?) : SaveStatus
    data class Failed(val message: String) : SaveStatus
}

@Composable
fun ColumnScope.BindsSection(binds: BindsAccess?) {
    if (binds == null) {
        SettingRow("keys.cfg не найден", "Запусти Rust один раз, чтобы игра создала файл с биндами") {
            StatusChip("Нет файла", AshColors.warn, AshColors.warnSoft)
        }
        return
    }

    val scope = rememberCoroutineScope()
    var commands by remember { mutableStateOf<Map<String, String>?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var status by remember { mutableStateOf<SaveStatus>(SaveStatus.Idle) }
    var running by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val loaded = binds.load()
        commands = loaded
        selected = loaded.withCombatlog()
    }

    LaunchedEffect(binds) {
        try {
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = SaveStatus.Failed("Не удалось прочитать keys.cfg: ${e.message}")
        }
    }
    LaunchedEffect(binds) {
        while (true) {
            running = try {
                binds.gameRunning()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            delay(GAME_POLL_MS.milliseconds)
        }
    }

    val loaded = commands
    val dirty = loaded != null && loaded.withCombatlog() != selected
    val editable = loaded != null && status != SaveStatus.Saving
    val picking = KeyPicking(
        selected = selected,
        enabled = editable,
        onToggle = { key ->
            selected = if (key in selected) selected - key else selected + key
            status = SaveStatus.Idle
        },
        onHover = { key, isHovered -> if (isHovered) hovered = key else if (hovered == key) hovered = null },
    )

    KeyDeck(picking, Modifier.fillMaxWidth())
    Spacer(Modifier.height(AshSpace.m))
    AshText(hoverText(hovered, loaded), Ash.type.label, color = AshColors.text3)
    Spacer(Modifier.height(AshSpace.xxl))
    SettingsGroup("Как это работает") {
        SettingRow("Файл", binds.location)
        SettingRow("Когда сохранять", "Только при закрытом Rust: при выходе игра перезаписывает keys.cfg своими биндами")
        SettingRow("Резервная копия", "Перед записью прежний keys.cfg копируется в папку backups программы")
    }
    Spacer(Modifier.weight(1f))

    AnimatedVisibility(running, enter = fadeIn(tween(AshMotion.SHORT)), exit = fadeOut(tween(AshMotion.SHORT))) {
        Notice("Rust запущен. Закрой игру, чтобы сохранить: при выходе она перезапишет keys.cfg", AshColors.warn, AshColors.warnSoft)
    }
    when (val current = status) {
        is SaveStatus.Saved -> Notice(current.backup?.let { "Сохранено. Копия прежнего файла: $it" } ?: "Изменений нет", AshColors.ok, AshColors.okSoft)
        is SaveStatus.Failed -> Notice(current.message, AshColors.bad, AshColors.badSoft)
        SaveStatus.Idle, SaveStatus.Saving -> Unit
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AshSpace.s)) {
        AshText("Выбрано ${selected.size} из ${MANAGED_KEYS.size}", Ash.type.bodyMedium, color = AshColors.text2)
        Spacer(Modifier.weight(1f))
        AshButton("Выбрать все", enabled = editable && selected != MANAGED_KEYS) {
            selected = MANAGED_KEYS
            status = SaveStatus.Idle
        }
        AshButton("Снять все", enabled = editable && selected.isNotEmpty()) {
            selected = emptySet()
            status = SaveStatus.Idle
        }
        AshButton(if (status == SaveStatus.Saving) "Сохраняю…" else "Сохранить", primary = true, enabled = dirty && !running && editable) {
            scope.launch {
                status = SaveStatus.Saving
                status = try {
                    if (binds.gameRunning()) {
                        running = true
                        SaveStatus.Failed("Rust запущен, сохранение отменено")
                    } else {
                        val backup = binds.save(selected, MANAGED_KEYS)
                        reload()
                        SaveStatus.Saved(backup)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SaveStatus.Failed("Ошибка keys.cfg: ${e.message}")
                }
            }
        }
    }
}

private fun Map<String, String>.withCombatlog(): Set<String> = filterValues(KeyBinds::hasCombatlog).keys.intersect(MANAGED_KEYS)

private fun hoverText(key: String?, commands: Map<String, String>?): String {
    if (key == null || commands == null) return "Наведи на клавишу, чтобы увидеть её бинд в keys.cfg"
    val label = DECK_KEYS.first { it.name == key }.label
    return "$label: ${commands[key]?.ifEmpty { null } ?: "не назначена"}"
}
