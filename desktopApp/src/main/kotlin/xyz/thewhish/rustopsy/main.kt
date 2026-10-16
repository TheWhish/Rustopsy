package xyz.thewhish.rustopsy

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import xyz.thewhish.rustopsy.platform.Settings
import xyz.thewhish.rustopsy.platform.SettingsStore
import xyz.thewhish.rustopsy.platform.SingleInstanceLock
import xyz.thewhish.rustopsy.platform.appLogger
import xyz.thewhish.rustopsy.platform.installFileLogging
import xyz.thewhish.rustopsy.session.CombatSession
import xyz.thewhish.rustopsy.session.TeamFights
import xyz.thewhish.rustopsy.session.teamFights
import xyz.thewhish.rustopsy.team.TeamSync
import xyz.thewhish.rustopsy.ui.overlay.OverlayAccess
import xyz.thewhish.rustopsy.ui.settings.BindsAccess
import xyz.thewhish.rustopsy.ui.settings.SettingsContext
import java.awt.Desktop
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.IOException
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.logging.Level
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private const val DEFAULT_SERVER_URL = "https://rustopsy.thewhish.xyz"
private val SETTINGS_SAVE_DELAY = 500.milliseconds

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
        val settingsStore = SettingsStore(dirs.config)
        val settings = MutableStateFlow(settingsStore.load())
        appLogger.level = if (settings.value.debugLogging) Level.FINE else Level.INFO
        val version = System.getProperty("rustopsy.version") ?: "dev"
        appLogger.info("Started $version")
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val selfUpdate = SelfUpdate.start(dirs, appScope)
        val attempted = settings.value.updateAttempt
        if (attempted != null) settings.update { it.copy(updateAttempt = null) }

        val gameLog = RustLogLocator.find(settings.value.gameLogPath)
        if (gameLog == null) appLogger.warning("Rust output_log.txt not found, clipboard only") else appLogger.info("Reading game log $gameLog")
        val captures = listOfNotNull(gameLog?.let { LogFileTailer(it).captures() }, ClipboardWatcher().captures()).merge()
        val session = CombatSession()
        val binds = gameLog?.let { KeysConfigFile.besideGameLog(it, dirs.backups) }?.let(::bindsAccess)
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val serverUrl = System.getProperty("rustopsy.server") ?: DEFAULT_SERVER_URL
        val api = ServerApi(serverUrl, http)
        val account = Account(api, SteamLogin(serverUrl) { Desktop.getDesktop().browse(it) }, SessionVault(dirs.session), appScope)
        val avatars = Avatars(http)
        val team = TeamSync(api, account)
        val settingsContext = SettingsContext(
            gameLog = gameLog?.toString(),
            dataDir = dirs.root.toString(),
            version = version,
            binds = binds,
            account = account.access(),
        )
        val teamAccess = team.access()
        val fights = teamFights(session.state, teamAccess).flowOn(Dispatchers.Default).stateIn(appScope, SharingStarted.Eagerly, TeamFights())
        appScope.launch { settings.drop(1).collectLatest { delay(SETTINGS_SAVE_DELAY); save(settingsStore, it) } }
        val previewing = MutableStateFlow(false)
        val overlayAccess = OverlayAccess(
            settings = settings.map { it.overlay }.stateIn(appScope, SharingStarted.Eagerly, settings.value.overlay),
            update = { overlay -> settings.update { it.copy(overlay = overlay) } },
            preview = { previewing.value = it },
        )

        application(exitProcessOnExit = false) {
            var launching by remember { mutableStateOf(selfUpdate != null) }
            if (launching && selfUpdate != null) {
                LaunchWindow(selfUpdate, attempted) { installation ->
                    if (installation != null && startInstall(selfUpdate, installation, settings, settingsStore)) exitApplication() else launching = false
                }
                return@application
            }
            var mainActive by remember { mutableStateOf(true) }
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
                DisposableEffect(window) {
                    val listener = object : WindowAdapter() {
                        override fun windowActivated(event: WindowEvent) {
                            mainActive = true
                        }

                        override fun windowDeactivated(event: WindowEvent) {
                            mainActive = false
                        }
                    }
                    mainActive = window.isActive
                    window.addWindowListener(listener)
                    onDispose { window.removeWindowListener(listener) }
                }
                App(session.state, fights, ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds, settingsContext, teamAccess, overlayAccess, avatars::load)
            }
            val overlaySettings by overlayAccess.settings.collectAsState()
            val preview by previewing.collectAsState()
            OverlayWindow(fights, overlaySettings, preview, suppressed = mainActive)
        }
        appScope.cancel()
        save(settingsStore, settings.value)
    }
    exitProcess(0)
}

private fun startInstall(selfUpdate: SelfUpdate, installation: Installation, settings: MutableStateFlow<Settings>, store: SettingsStore): Boolean {
    settings.update { it.copy(updateAttempt = installation.version) }
    return try {
        store.save(settings.value)
        selfUpdate.install(installation)
        appLogger.info("Installing update ${installation.version}")
        true
    } catch (e: IOException) {
        appLogger.log(Level.WARNING, "Update ${installation.version} was not started", e)
        settings.update { it.copy(updateAttempt = null) }
        false
    }
}

private fun save(store: SettingsStore, settings: Settings) {
    try {
        store.save(settings)
    } catch (e: IOException) {
        appLogger.log(Level.WARNING, "Settings were not saved", e)
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
