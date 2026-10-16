package xyz.thewhish.rustopsy.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

private const val UNIQUE_VIOLATION = "23505"

suspend fun <T> DataSource.sql(block: (Connection) -> T): T = withContext(Dispatchers.IO) { connection.use(block) }

suspend fun <T> DataSource.transaction(block: (Connection) -> T): T = sql { connection ->
    connection.autoCommit = false
    try {
        block(connection).also { connection.commit() }
    } catch (e: Throwable) {
        connection.rollback()
        throw e
    } finally {
        connection.autoCommit = true
    }
}

fun Connection.update(query: String, bind: PreparedStatement.() -> Unit = {}): Int =
    prepareStatement(query).use { statement ->
        statement.bind()
        statement.executeUpdate()
    }

fun <T> Connection.queryOne(query: String, bind: PreparedStatement.() -> Unit, read: ResultSet.() -> T?): T? =
    prepareStatement(query).use { statement ->
        statement.bind()
        statement.executeQuery().use { result -> if (result.next()) result.read() else null }
    }

fun <T> Connection.queryList(query: String, bind: PreparedStatement.() -> Unit, read: ResultSet.() -> T): List<T> =
    prepareStatement(query).use { statement ->
        statement.bind()
        statement.executeQuery().use { result -> buildList { while (result.next()) add(result.read()) } }
    }

val SQLException.isUniqueViolation: Boolean get() = sqlState == UNIQUE_VIOLATION
