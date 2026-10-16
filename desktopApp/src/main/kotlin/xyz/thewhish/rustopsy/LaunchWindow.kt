package xyz.thewhish.rustopsy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshTheme
import xyz.thewhish.rustopsy.ui.update.LAUNCH_SCREEN_SIZE
import xyz.thewhish.rustopsy.ui.update.LaunchScreen
import xyz.thewhish.rustopsy.ui.update.LaunchState
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val REVEAL_DELAY = 400.milliseconds
private val UPDATED_HOLD = 1500.milliseconds
private val RESTART_HOLD = 700.milliseconds
private const val ENTER_SCALE = 0.98f

@Composable
fun ApplicationScope.LaunchWindow(selfUpdate: SelfUpdate, attempted: String?, onFinish: (Installation?) -> Unit) {
    val updatedTo = attempted?.takeIf { it == selfUpdate.version }
    var state by remember { mutableStateOf(updatedTo?.let(LaunchState::Updated) ?: LaunchState.Checking) }
    var revealed by remember { mutableStateOf(updatedTo != null) }
    var closing by remember { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = !closing && (revealed || state !is LaunchState.Checking)

    LaunchedEffect(Unit) {
        delay(REVEAL_DELAY)
        revealed = true
    }
    LaunchedEffect(Unit) {
        val started = TimeSource.Monotonic.markNow()
        val installation = selfUpdate.prepare(attempted) { state = it }
        delay(if (installation != null) RESTART_HOLD else if (updatedTo != null) UPDATED_HOLD - started.elapsedNow() else 0.milliseconds)
        closing = true
        visibility.targetState = false
        snapshotFlow { visibility.isIdle }.first { it }
        onFinish(installation)
    }

    Window(
        onCloseRequest = ::exitApplication,
        visible = visibility.currentState || visibility.targetState,
        title = "Rustopsy",
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        state = rememberWindowState(position = WindowPosition(Alignment.Center), size = LAUNCH_SCREEN_SIZE),
    ) {
        AshTheme {
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(tween(AshMotion.MEDIUM, easing = AshMotion.enter)) +
                    scaleIn(tween(AshMotion.MEDIUM, easing = AshMotion.enter), initialScale = ENTER_SCALE),
                exit = fadeOut(tween(AshMotion.MEDIUM, easing = AshMotion.exit)),
            ) {
                LaunchScreen(state, selfUpdate.version)
            }
        }
    }
}
