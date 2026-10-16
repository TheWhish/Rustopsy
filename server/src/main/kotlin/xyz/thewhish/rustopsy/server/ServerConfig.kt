package xyz.thewhish.rustopsy.server

class DbConfig(val url: String, val user: String, val password: String) {
    override fun toString() = "DbConfig(url=$url, user=$user)"
}

class ServerConfig(
    val host: String,
    val port: Int,
    val publicUrl: String,
    val steamApiKey: String?,
    val db: DbConfig,
) {
    override fun toString() =
        "ServerConfig(host=$host, port=$port, publicUrl=$publicUrl, steamApiKey=${if (steamApiKey == null) "none" else "set"}, db=$db)"

    companion object {
        private val STEAM_API_KEY = Regex("[0-9A-F]{32}")
        private val LOCAL_URL = Regex("http://(127\\.0\\.0\\.1|localhost)(:\\d{1,5})?")
        private val PUBLIC_URL = Regex("https://[a-z0-9.-]+(:\\d{1,5})?")

        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            fun optional(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun required(name: String): String = requireNotNull(optional(name)) { "Environment variable $name is not set" }

            val port = required("PORT").toIntOrNull()
            require(port != null && port in 1..65535) { "PORT must be a number from 1 to 65535" }
            val publicUrl = required("PUBLIC_URL").removeSuffix("/")
            require(PUBLIC_URL.matches(publicUrl) || LOCAL_URL.matches(publicUrl)) { "PUBLIC_URL must be an https:// origin without a path" }
            val steamApiKey = optional("STEAM_API_KEY")
            require(steamApiKey == null || STEAM_API_KEY.matches(steamApiKey)) { "STEAM_API_KEY must be 32 hexadecimal characters" }
            val url = required("DB_URL")
            require(url.startsWith("jdbc:postgresql://")) { "DB_URL must start with jdbc:postgresql://" }
            return ServerConfig(
                host = required("HOST"),
                port = port,
                publicUrl = publicUrl,
                steamApiKey = steamApiKey,
                db = DbConfig(url, required("DB_USER"), required("DB_PASSWORD")),
            )
        }
    }
}
