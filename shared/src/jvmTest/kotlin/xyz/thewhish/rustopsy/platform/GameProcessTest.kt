package xyz.thewhish.rustopsy.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameProcessTest {
    @Test
    fun findsProcessByImageNameWithoutHandles() {
        val own = java.nio.file.Path.of(ProcessHandle.current().info().command().get()).fileName.toString()

        assertTrue(GameProcess.isRunning(own))
        assertFalse(GameProcess.isRunning("rustopsy-no-such-process.exe"))
    }
}
