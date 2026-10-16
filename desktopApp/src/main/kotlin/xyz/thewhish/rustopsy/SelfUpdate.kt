package xyz.thewhish.rustopsy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import xyz.thewhish.rustopsy.platform.AppDirs
import xyz.thewhish.rustopsy.platform.appLogger
import xyz.thewhish.rustopsy.ui.update.LaunchState
import xyz.thewhish.rustopsy.update.AppVersion
import xyz.thewhish.rustopsy.update.Update
import xyz.thewhish.rustopsy.update.UpdateClient
import xyz.thewhish.rustopsy.update.clearDownloads
import java.io.IOException
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration
import java.util.logging.Level

data class Installation(val version: String, val msi: Path)

class SelfUpdate private constructor(
    val version: String,
    private val current: AppVersion,
    private val exe: Path,
    private val installLog: Path,
    scope: CoroutineScope,
) {
    private val client = UpdateClient(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build())
    private val downloads = Path.of(System.getProperty("java.io.tmpdir"), "Rustopsy-update")
    private val found = scope.async {
        removeOldDownloads()
        client.check(current)
    }

    suspend fun prepare(failedVersion: String?, onState: (LaunchState) -> Unit): Installation? = try {
        val update = found.await()
        when {
            update == null -> null
            update.version == failedVersion -> {
                appLogger.warning("Update to ${update.version} did not install last time, see $installLog; starting $version")
                null
            }
            else -> download(update, onState)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        appLogger.log(Level.WARNING, "Update failed, starting $version", e)
        null
    }

    fun install(installation: Installation) {
        ProcessBuilder(powershell().toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", INSTALL_SCRIPT)
            .directory(downloads.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment()["RUSTOPSY_PIDS"] = ownProcesses().joinToString(",") { it.pid().toString() }
                environment()["RUSTOPSY_MSI"] = installation.msi.toString()
                environment()["RUSTOPSY_LOG"] = installLog.toString()
                environment()["RUSTOPSY_EXE"] = exe.toString()
            }
            .start()
    }

    private suspend fun removeOldDownloads() {
        try {
            withContext(Dispatchers.IO) { clearDownloads(downloads) }
        } catch (e: IOException) {
            appLogger.warning("Old update files were not removed: $e")
        }
    }

    private fun ownProcesses(): List<ProcessHandle> {
        val current = ProcessHandle.current()
        val launcher = current.parent().filter { parent -> parent.info().command().map { Path.of(it) == exe }.orElse(false) }
        return listOf(current) + launcher.stream().toList()
    }

    private suspend fun download(update: Update, onState: (LaunchState) -> Unit): Installation {
        onState(LaunchState.Downloading(update.version, 0, update.size))
        val msi = client.download(update, downloads) { onState(LaunchState.Downloading(update.version, it, update.size)) }
        onState(LaunchState.Restarting(update.size))
        return Installation(update.version, msi)
    }

    companion object {
        private val CONNECT_TIMEOUT = Duration.ofSeconds(3)
        private val INSTALL_SCRIPT = listOf(
            $$"Wait-Process -Id ($env:RUSTOPSY_PIDS -split ',') -Timeout 60 -ErrorAction SilentlyContinue",
            $$"Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\\msiexec.exe') -ArgumentList ('/i {0}{1}{0} /qn /norestart /l*v {0}{2}{0}' -f [char]34, $env:RUSTOPSY_MSI, $env:RUSTOPSY_LOG) -Wait",
            $$"Start-Process -FilePath $env:RUSTOPSY_EXE",
        ).joinToString("; ")

        fun start(dirs: AppDirs, scope: CoroutineScope): SelfUpdate? {
            val exe = System.getProperty("jpackage.app-path")?.let(Path::of) ?: return null
            val version = System.getProperty("rustopsy.version") ?: return null
            val current = AppVersion.parse(version) ?: return null
            return SelfUpdate(version, current, exe, dirs.logs.resolve("update.log"), scope)
        }

        private fun powershell(): Path =
            Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
    }
}
