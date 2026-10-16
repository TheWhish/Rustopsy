package xyz.thewhish.rustopsy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.thewhish.rustopsy.account.AccountAccess
import xyz.thewhish.rustopsy.account.AccountStatus
import xyz.thewhish.rustopsy.ui.components.AshButton
import xyz.thewhish.rustopsy.ui.components.Avatar
import xyz.thewhish.rustopsy.ui.components.rememberActionRunner
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshSpace

@Composable
fun AccountSection(account: AccountAccess) {
    val status by account.status.collectAsState()
    val runner = rememberActionRunner()
    var confirmDelete by remember { mutableStateOf(false) }

    when (val current = status) {
        AccountStatus.Loading -> SettingsGroup("Steam") {
            SettingRow("Проверяем вход", "Связываемся с сервером Rustopsy")
        }
        is AccountStatus.SignedOut -> {
            SettingsGroup("Steam") {
                SettingRow("Вход не выполнен", "Пароль вводится только на сайте Steam, Rustopsy его не получает") {
                    AshButton("Войти через Steam", primary = true, onClick = account.signIn)
                }
            }
            current.problem?.let { Notice(it, AshColors.bad, AshColors.badSoft) }
        }
        AccountStatus.SigningIn -> SettingsGroup("Steam") {
            SettingRow("Подтверди вход в браузере", "Открылась страница Steam. После подтверждения вернись в Rustopsy") {
                AshButton("Отмена", onClick = account.cancelSignIn)
            }
        }
        is AccountStatus.SignedIn -> {
            SettingsGroup("Профиль") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(current.profile.avatarUrl, current.profile.name, 40.dp)
                    Spacer(Modifier.width(AshSpace.m))
                    Box(Modifier.weight(1f)) {
                        SettingRow(current.profile.name, "SteamID ${current.profile.steamId}") {
                            StatusChip("Вход выполнен", AshColors.ok, AshColors.okSoft)
                        }
                    }
                }
            }
            SettingsGroup("Сессии") {
                SettingRow("Выйти", "Только на этом компьютере") {
                    AshButton("Выйти", enabled = !runner.busy) { runner.run { account.signOut(false) } }
                }
                SettingRow("Выйти на всех устройствах", "Вход будет отозван везде, где он выполнен") {
                    AshButton("Выйти везде", enabled = !runner.busy) { runner.run { account.signOut(true) } }
                }
            }
            SettingsGroup("Данные") {
                SettingRow("Удалить аккаунт", "Профиль, сессии и место в команде удаляются с сервера без возможности восстановления") {
                    Row(horizontalArrangement = Arrangement.spacedBy(AshSpace.s)) {
                        if (confirmDelete) AshButton("Не удалять") { confirmDelete = false }
                        AshButton(if (confirmDelete) "Удалить навсегда" else "Удалить", primary = confirmDelete, enabled = !runner.busy) {
                            if (confirmDelete) runner.run(account.deleteAccount) else confirmDelete = true
                        }
                    }
                }
            }
            current.problem?.let { Notice(it, AshColors.bad, AshColors.badSoft) }
        }
        is AccountStatus.Unavailable -> SettingsGroup("Steam") {
            SettingRow("Нет связи с сервером", current.problem) {
                AshButton("Повторить", enabled = !runner.busy) { runner.run(account.retry) }
            }
        }
    }
}
