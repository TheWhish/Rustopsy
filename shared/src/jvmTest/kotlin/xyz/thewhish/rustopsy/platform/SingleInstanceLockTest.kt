package xyz.thewhish.rustopsy.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SingleInstanceLockTest {
    private val file = Files.createTempDirectory("rustopsy-lock").resolve(".lock")

    @Test
    fun secondAcquireFailsUntilFirstIsClosed() {
        val first = assertNotNull(SingleInstanceLock.tryAcquire(file))
        assertNull(SingleInstanceLock.tryAcquire(file))
        first.close()
        assertNotNull(SingleInstanceLock.tryAcquire(file)).close()
    }
}
