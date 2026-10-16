package xyz.thewhish.rustopsy.server

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {
    @Test
    fun healthyWhenDatabaseIsMigratedAndReachable() {
        connectDatabase(TestDatabase.config()).use { dataSource ->
            testApplication {
                application { module(dataSource, TEST_PUBLIC_URL, fakeSteam()) }
                assertEquals(HttpStatusCode.OK, client.get("/health").status)
                assertEquals(HttpStatusCode.OK, client.head("/health").status)
            }
            dataSource.connection.use { connection ->
                connection.createStatement().executeQuery("select count(*) from sessions").use { assertTrue(it.next()) }
            }
        }
    }

    @Test
    fun unavailableWhenDatabaseIsGone() {
        val dataSource = connectDatabase(TestDatabase.config())
        dataSource.close()
        testApplication {
            application { module(dataSource, TEST_PUBLIC_URL, fakeSteam()) }
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health").status)
        }
    }
}
