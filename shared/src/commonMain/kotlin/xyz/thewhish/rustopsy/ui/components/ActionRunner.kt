package xyz.thewhish.rustopsy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
class ActionRunner(private val scope: CoroutineScope) {
    private var job by mutableStateOf<Job?>(null)

    val busy: Boolean get() = job != null

    fun run(action: suspend () -> Unit) {
        if (job != null) return
        val started = scope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
            } finally {
                job = null
            }
        }
        job = started
        started.start()
    }
}

@Composable
fun rememberActionRunner(): ActionRunner {
    val scope = rememberCoroutineScope()
    return remember(scope) { ActionRunner(scope) }
}
