package xyz.thewhish.rustopsy.update

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    companion object {
        private val PATTERN = Regex("""(\d{1,5})\.(\d{1,5})\.(\d{1,5})(-[0-9A-Za-z.]{1,32})?""")

        fun parse(text: String): AppVersion? {
            val (major, minor, patch) = PATTERN.matchEntire(text)?.destructured ?: return null
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
