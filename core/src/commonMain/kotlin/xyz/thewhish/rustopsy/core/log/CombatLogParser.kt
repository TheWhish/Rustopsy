package xyz.thewhish.rustopsy.core.log

object CombatLogParser {
    const val MAX_INPUT_CHARS = 4_000_000
    const val MAX_LINE_CHARS = 4_096

    private const val EMPTY_LOG = "Combat log empty."
    private const val MIN_KNOWN_COLUMNS = 8
    private val BOM = Char(0xFEFF).toString()
    private val TOKEN = Regex("""\S+""")
    private val WHITESPACE = Regex("""\s+""")
    private val NUMBER = Regex("""-?\d{1,9}(?:\.\d{1,6})?""")
    private val TIME = Regex("""\d{1,9}(?:\.\d{1,6})?s""")
    private val HIDDEN = Regex("""\+\s*(\d{1,6})\s+events?\s+in\s+the\s+last\s+(\d{1,6})\s+seconds?""", RegexOption.IGNORE_CASE)
    private val CONSOLE_STAMP = Regex("""^(\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}) """)
    private val LOG_STAMP = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})\.(\d{3})Z\|[^|]{0,32}\|""")
    private val REQUIRED = listOf("time", "attacker", "target")
    private val KNOWN = setOf(
        "time", "attacker", "target", "weapon", "ammo", "area", "distance", "old_hp", "new_hp",
        "info", "hits", "integrity", "travel", "mismatch", "desync",
    )

    private class Column(val name: String, val start: Int)

    private class Line(val printedAt: PrintTime?, val body: String)

    private class Block(val header: Line, val following: Sequence<String>)

    fun parse(text: String): Snapshot? {
        val input = if (text.length > MAX_INPUT_CHARS) text.takeLast(MAX_INPUT_CHARS) else text
        val block = lastBlock(input.removePrefix(BOM)) ?: return null
        val printedAt = block.header.printedAt
        if (block.header.body.trim() == EMPTY_LOG) return Snapshot(printedAt, emptyList(), emptyList(), null, emptyList())

        val columns = TOKEN.findAll(block.header.body).map { Column(it.value, it.range.first) }.toList()
        val index = columns.withIndex().distinctBy { it.value.name }.associate { it.value.name to it.index }
        val issues = columns.filter { it.name !in KNOWN }.map { "Unknown column '${it.name}'" }.toMutableList()
        val rows = mutableListOf<CombatRow>()
        var hidden: HiddenEvents? = null
        var complete = false

        for ((offset, line) in block.following.withIndex()) {
            val rowNumber = offset + 1
            if (line.length > MAX_LINE_CHARS) {
                complete = true
                break
            }
            val body = split(line).body
            val trimmed = body.trim()
            val hiddenMatch = HIDDEN.matchEntire(trimmed)
            if (hiddenMatch != null) {
                hidden = HiddenEvents(hiddenMatch.groupValues[1].toInt(), hiddenMatch.groupValues[2].toInt())
                complete = true
                break
            }
            if (!TIME.matches(trimmed.split(WHITESPACE, limit = 2).first())) {
                complete = true
                break
            }

            val cells = cells(body, columns)
            if (cells == null) {
                issues += "Row $rowNumber: cannot split into ${columns.size} columns: $trimmed"
                continue
            }
            RowReader(cells, index, rowNumber, issues).read(trimmed)?.let(rows::add)
        }
        return Snapshot(printedAt, columns.map { it.name }, rows, hidden, issues, complete)
    }

    private fun lastBlock(text: String): Block? {
        var end = text.length
        while (true) {
            val start = text.lastIndexOf('\n', end - 1) + 1
            if (end - start <= MAX_LINE_CHARS) {
                val line = split(text.substring(start, end).trimEnd())
                if (line.body.trim() == EMPTY_LOG || isHeader(line.body)) return Block(line, following(text, end))
            }
            if (start == 0) return null
            end = start - 1
        }
    }

    private fun following(text: String, lineEnd: Int): Sequence<String> {
        if (lineEnd >= text.length) return emptySequence()
        val rest = text.substring(lineEnd + 1)
        val terminator = when {
            rest.endsWith("\r\n") -> 2
            rest.endsWith('\n') || rest.endsWith('\r') -> 1
            else -> 0
        }
        return if (rest.length == terminator) emptySequence() else rest.dropLast(terminator).lineSequence()
    }

    private fun split(line: String): Line {
        LOG_STAMP.find(line)?.let { match ->
            val (year, month, day, hour, minute, second, millis) = match.destructured
            val time = ConsoleTime.of(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
            return Line(time?.let { PrintTime.Utc(it.toEpochSeconds() * 1_000 + millis.toInt()) }, line.substring(match.range.last + 1))
        }
        CONSOLE_STAMP.find(line)?.let { match ->
            return Line(ConsoleTime.parse(match.groupValues[1])?.let(PrintTime::Local), line.substring(match.range.last + 1))
        }
        return Line(null, line)
    }

    private fun isHeader(body: String): Boolean {
        if (REQUIRED.last() !in body) return false
        val tokens = TOKEN.findAll(body).map { it.value }.toList()
        return tokens.take(REQUIRED.size) == REQUIRED && tokens.count { it in KNOWN } >= MIN_KNOWN_COLUMNS
    }

    private fun cells(body: String, columns: List<Column>): List<String>? {
        if (isAligned(body, columns)) {
            return columns.mapIndexed { i, column ->
                val end = minOf(columns.getOrNull(i + 1)?.start ?: body.length, body.length)
                if (column.start >= end) "" else body.substring(column.start, end).trim()
            }
        }
        return body.trim().split(WHITESPACE).takeIf { it.size == columns.size }
    }

    private fun isAligned(body: String, columns: List<Column>): Boolean =
        columns.drop(1).all { it.start > body.length || body[it.start - 1] == ' ' }

    private class RowReader(
        private val cells: List<String>,
        private val index: Map<String, Int>,
        private val rowNumber: Int,
        private val issues: MutableList<String>,
    ) {
        fun read(raw: String): CombatRow? {
            val age = number("time", "s") ?: return null
            return CombatRow(
                ageSec = age,
                attacker = text("attacker")?.let(Party::parse),
                target = text("target")?.let(Party::parse),
                weapon = text("weapon").orEmpty(),
                ammo = text("ammo").orEmpty(),
                area = area(),
                distanceM = number("distance", "m"),
                oldHp = number("old_hp"),
                newHp = number("new_hp"),
                info = text("info").orEmpty(),
                hits = integer("hits"),
                integrity = number("integrity"),
                travelSec = number("travel", "s"),
                mismatchM = number("mismatch", "m"),
                desync = integer("desync"),
                raw = raw,
            )
        }

        private fun text(column: String): String? =
            index[column]?.let(cells::getOrNull)?.takeIf { it.isNotEmpty() && it != "N/A" }

        private fun area(): HitArea? {
            val value = text("area") ?: return null
            return HitArea.parse(value) ?: reject("area", value)
        }

        private fun number(column: String, suffix: String = ""): Double? {
            val value = text(column) ?: return null
            val digits = value.removeSuffix(suffix)
            return if (NUMBER.matches(digits)) digits.toDouble() else reject(column, value)
        }

        private fun integer(column: String): Int? {
            val value = text(column) ?: return null
            return value.toIntOrNull() ?: reject(column, value)
        }

        private fun reject(column: String, value: String): Nothing? {
            issues += "Row $rowNumber: bad $column '$value'"
            return null
        }
    }
}
