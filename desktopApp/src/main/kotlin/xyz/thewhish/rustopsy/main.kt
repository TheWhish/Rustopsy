package xyz.thewhish.rustopsy

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import xyz.thewhish.rustopsy.platform.AppDirs
import xyz.thewhish.rustopsy.platform.SettingsStore
import xyz.thewhish.rustopsy.platform.SingleInstanceLock
import xyz.thewhish.rustopsy.platform.appLogger
import xyz.thewhish.rustopsy.platform.installFileLogging
import java.util.logging.Level
import javax.swing.JOptionPane
import kotlin.system.exitProcess

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

        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Rustopsy",
            ) {
                App()
            }
        }
    }
}

private fun showMessage(text: String, type: Int) {
    JOptionPane.showMessageDialog(null, text, "Rustopsy", type)
}
