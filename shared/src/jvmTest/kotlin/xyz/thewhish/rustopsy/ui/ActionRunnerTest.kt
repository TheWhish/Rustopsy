package xyz.thewhish.rustopsy.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.thewhish.rustopsy.ui.components.ActionRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ActionRunnerTest {
    @Test
    fun busyOnlyWhileActionRunsAndFreesForTheNextOne() = runTest(UnconfinedTestDispatcher()) {
        val runner = ActionRunner(this)
        val gate = CompletableDeferred<Unit>()
        var runs = 0

        runner.run { runs++; gate.await() }
        assertTrue(runner.busy)
        runner.run { runs++ }
        assertEquals(1, runs)

        gate.complete(Unit)
        assertFalse(runner.busy)
        runner.run { runs++ }
        assertEquals(2, runs)
        assertFalse(runner.busy)
    }

    @Test
    fun failedActionDoesNotLockRunner() = runTest(UnconfinedTestDispatcher()) {
        var failure: Throwable? = null
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> failure = e })
        val runner = ActionRunner(scope)
        runner.run { error("boom") }
        assertFalse(runner.busy)
        assertEquals("boom", failure?.message)
    }
}
