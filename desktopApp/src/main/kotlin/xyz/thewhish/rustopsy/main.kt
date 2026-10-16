package xyz.thewhish.rustopsy

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import xyz.thewhish.rustopsy.account.Account
import xyz.thewhish.rustopsy.account.ServerApi
import xyz.thewhish.rustopsy.account.SessionVault
import xyz.thewhish.rustopsy.account.SteamLogin
import xyz.thewhish.rustopsy.binds.KeysConfigFile
import xyz.thewhish.rustopsy.capture.ClipboardWatcher
import xyz.thewhish.rustopsy.capture.LogFileTailer
import xyz.thewhish.rustopsy.capture.RustLogLocator
import xyz.thewhish.rustopsy.platform.AppDirs
import xyz.thewhish.rustopsy.platform.Avatars
import xyz.thewhish.rustopsy.platform.GameProcess
import xyz.thewhish.rustopsy.platform.SettingsStore
import xyz.thewhish.rustopsy.platform.SingleInstanceLock
import xyz.thewhish.rustopsy.platform.appLogger
import xyz.thewhish.rustopsy.platform.installFileLogging
import xyz.thewhish.rustopsy.session.CombatSession
import xyz.thewhish.rustopsy.team.TeamSync
import xyz.thewhish.rustopsy.ui.settings.BindsAccess
import xyz.thewhish.rustopsy.ui.settings.SettingsContext
import java.awt.Desktop
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.logging.Level
import javax.swing.JOptionPane
import kotlin.system.exitProcess

private const val DEFAULT_SERVER_URL = "https://rustopsy.thewhish.xyz"

fun main() {
    val dirs = try {
        AppDirs.default().create()
    } catch (e: Exception) {
        showMessage("Не удалось создать папку данных: ${e.message}", JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }

    val lock = SingleInstanceLock.tryAcquire(dirs.lock) ?: run {
        showMessage("Rustopsy уже запущен.", JOptionPane.INFORMATION_MESSAGE)
        return
    }

    lock.use {
        installFileLogging(dirs.logs)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            appLogger.log(Level.SEVERE, "Uncaught exception in ${thread.name}", error)
        }
        val settings = SettingsStore(dirs.config).load()
        appLogger.level = if (settings.debugLogging) Level.FINE else Level.INFO
        appLogger.info("Started")

        val gameLog = RustLogLocator.find(settings.gameLogPath)
        if (gameLog == null) appLogger.warning("Rust output_log.txt not found, clipboard only") else appLogger.info("Reading game log $gameLog")
        val captures = listOfNotNull(gameLog?.let { LogFileTailer(it).captures() }, ClipboardWatcher().captures()).merge()
        val session = CombatSession()
        val binds = gameLog?.let { KeysConfigFile.besideGameLog(it, dirs.backups) }?.let(::bindsAccess)
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val serverUrl = System.getProperty("rustopsy.server") ?: DEFAULT_SERVER_URL
        val api = ServerApi(serverUrl, http)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val account = Account(api, SteamLogin(serverUrl) { Desktop.getDesktop().browse(it) }, SessionVault(dirs.session), appScope)
        val avatars = Avatars(http)
        val team = TeamSync(api, account)
        val settingsContext = SettingsContext(
            gameLog = gameLog?.toString(),
            dataDir = dirs.root.toString(),
            version = System.getProperty("jpackage.app-version") ?: "dev",
            binds = binds,
            account = account.access(),
        )
        val teamAccess = team.access()

        application {
            LaunchedEffect(Unit) { account.restore() }
            LaunchedEffect(Unit) { team.run() }
            LaunchedEffect(Unit) {
                captures
                    .catch { appLogger.log(Level.SEVERE, "Combat log capture stopped", it) }
                    .collect { capture ->
                        team.share(session.accept(capture))
                        val state = session.state.value
                        appLogger.fine("Combat log captured: ${capture.snapshot.rows.size} rows, session ${state.events.size} events, hidden ${state.hidden?.count ?: 0}")
                    }
            }
            val clientWidth = remember { WindowChrome.preferredClientWidth() }
            Window(
                onCloseRequest = ::exitApplication,
                title = "Rustopsy",
                state = rememberWindowState(
                    position = WindowPosition(Alignment.Center),
                    size = DpSize(clientWidth.dp, (clientWidth * 9 / 16).dp),
                ),
            ) {
                LaunchedEffect(Unit) { WindowChrome.install(window) }
                App(session.state, ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds, settingsContext, teamAccess, avatars::load)
            }
        }
    }
}

private fun bindsAccess(keys: KeysConfigFile) = BindsAccess(
    location = keys.file.toString(),
    load = { withContext(Dispatchers.IO) { keys.commands() } },
    save = { selected, managed ->
        withContext(Dispatchers.IO) {
            keys.save(selected, managed)?.also { appLogger.info("keys.cfg updated, backup $it") }?.fileName?.toString()
        }
    },
    gameRunning = { withContext(Dispatchers.IO) { GameProcess.isRunning() } },
)

private fun showMessage(text: String, type: Int) {
    JOptionPane.showMessageDialog(null, text, "Rustopsy", type)
}
