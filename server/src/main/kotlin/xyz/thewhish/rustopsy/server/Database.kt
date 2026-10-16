package xyz.thewhish.rustopsy.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

private const val POOL_SIZE = 5
private const val CONNECTION_TIMEOUT_MS = 2_000L
private const val VALIDATION_TIMEOUT_MS = 1_000L

fun connectDatabase(config: DbConfig): HikariDataSource {
    val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = POOL_SIZE
            connectionTimeout = CONNECTION_TIMEOUT_MS
            validationTimeout = VALIDATION_TIMEOUT_MS
            poolName = "rustopsy"
        },
    )
    try {
        Flyway.configure().dataSource(dataSource).load().migrate()
    } catch (e: Exception) {
        dataSource.close()
        throw e
    }
    return dataSource
}
