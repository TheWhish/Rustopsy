package xyz.thewhish.rustopsy.server.team

sealed interface InviteTarget {
    data class Id(val steamId: Long) : InviteTarget
    data class Vanity(val name: String) : InviteTarget

    companion object {
        private const val MAX_INPUT = 200
        private val STEAM_ID = Regex("\\d{17}")
        private val PROFILE_URL = Regex("(?:https?://)?(?:www\\.)?steamcommunity\\.com/profiles/(\\d{17})(?:/.*)?", RegexOption.IGNORE_CASE)
        private val VANITY_URL = Regex("(?:https?://)?(?:www\\.)?steamcommunity\\.com/id/([A-Za-z0-9_-]{2,32})(?:/.*)?", RegexOption.IGNORE_CASE)
        private val VANITY = Regex("[A-Za-z0-9_-]{2,32}")

        fun parse(input: String): InviteTarget? {
            val text = input.trim()
            if (text.length > MAX_INPUT) return null
            STEAM_ID.matchEntire(text)?.let { return Id(text.toLong()) }
            PROFILE_URL.matchEntire(text)?.let { return Id(it.groupValues[1].toLong()) }
            VANITY_URL.matchEntire(text)?.let { return Vanity(it.groupValues[1]) }
            return if (VANITY.matches(text)) Vanity(text) else null
        }
    }
}
