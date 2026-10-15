package xyz.thewhish.rustopsy.platform

import java.io.File
import java.nio.file.Path
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

val appLogger: Logger = Logger.getLogger("xyz.thewhish.rustopsy")

private const val LOG_LIMIT_BYTES = 2 * 1024 * 1024
private const val LOG_FILES = 5

fun installFileLogging(dir: Path) {
    System.setProperty("java.util.logging.SimpleFormatter.format", "%1\$tF %1\$tT.%1\$tL %4\$s %3\$s: %5\$s%6\$s%n")
    val pattern = dir.toAbsolutePath().toString().replace("%", "%%") + File.separator + "rustopsy-%g.log"
    val handler = FileHandler(pattern, LOG_LIMIT_BYTES, LOG_FILES, true).apply {
        encoding = Charsets.UTF_8.name()
        formatter = SimpleFormatter()
        level = Level.ALL
    }
    Logger.getLogger("").addHandler(handler)
}
