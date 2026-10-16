package xyz.thewhish.rustopsy.core.binds

object KeyBinds {
    const val COMBATLOG = "combatlog"

    private val BIND = Regex("""^\s*bind\s+(\S+)\s*(.*?)\s*$""", RegexOption.IGNORE_CASE)
    private val KEY = Regex("""[a-z0-9]{1,24}""")
    private const val SEPARATOR = ';'
    private const val QUOTE = '"'

    fun commands(cfg: String): Map<String, String> = cfg.split('\n')
        .mapNotNull { parse(it.removeSuffix("\r")) }
        .associate { it.key to it.command }

    fun hasCombatlog(command: String): Boolean = command.split(SEPARATOR).any { it.trim().equals(COMBATLOG, ignoreCase = true) }

    fun withCombatlog(cfg: String, selected: Set<String>, managed: Set<String>): String {
        require(managed.all { KEY.matches(it) }) { "Invalid key name in $managed" }
        require(managed.containsAll(selected)) { "Selected keys must be managed" }
        val newline = if ("\r\n" in cfg) "\r\n" else "\n"
        val lines = cfg.split(newline).toMutableList()
        val trailing = lines.lastOrNull() == ""
        if (trailing) lines.removeAt(lines.lastIndex)

        val seen = mutableSetOf<String>()
        val result = lines.mapNotNull { line ->
            val bind = parse(line) ?: return@mapNotNull line
            if (bind.key !in managed) return@mapNotNull line
            seen += bind.key
            val wanted = bind.key in selected
            if (wanted == hasCombatlog(bind.command)) return@mapNotNull line
            val command = if (wanted) add(bind.command) else remove(bind.command)
            command.takeIf { it.isNotEmpty() }?.let { "bind ${bind.key} ${if (bind.quoted) "$QUOTE$it$QUOTE" else it}" }
        }.toMutableList()
        selected.filterNot { it in seen }.sorted().forEach { result += "bind $it $COMBATLOG" }

        return result.joinToString(newline) + if (trailing || result.isNotEmpty()) newline else ""
    }

    private class Bind(val key: String, val command: String, val quoted: Boolean)

    private fun parse(line: String): Bind? {
        val match = BIND.matchEntire(line) ?: return null
        val key = match.groupValues[1].lowercase()
        if (!KEY.matches(key)) return null
        val raw = match.groupValues[2]
        val quoted = raw.length >= 2 && raw.first() == QUOTE && raw.last() == QUOTE
        return Bind(key, if (quoted) raw.substring(1, raw.length - 1) else raw, quoted)
    }

    private fun add(command: String): String = when {
        hasCombatlog(command) -> command
        command.isBlank() -> COMBATLOG
        else -> "$command$SEPARATOR$COMBATLOG"
    }

    private fun remove(command: String): String = command.split(SEPARATOR)
        .filterNot { it.trim().equals(COMBATLOG, ignoreCase = true) || it.isBlank() }
        .joinToString(SEPARATOR.toString())
}
