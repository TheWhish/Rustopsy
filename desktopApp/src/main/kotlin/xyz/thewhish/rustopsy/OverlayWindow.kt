package xyz.thewhish.rustopsy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.StateFlow
import xyz.thewhish.rustopsy.platform.OverlayCorner
import xyz.thewhish.rustopsy.platform.OverlaySettings
import xyz.thewhish.rustopsy.platform.appLogger
import xyz.thewhish.rustopsy.session.TeamFights
import xyz.thewhish.rustopsy.ui.overlay.OVERLAY_CARD_MAX_HEIGHT
import xyz.thewhish.rustopsy.ui.overlay.OVERLAY_CARD_WIDTH
import xyz.thewhish.rustopsy.ui.overlay.OverlayCard
import xyz.thewhish.rustopsy.ui.overlay.OverlayModel
import xyz.thewhish.rustopsy.ui.overlay.overlayModel
import xyz.thewhish.rustopsy.ui.overlay.sampleOverlay
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshTheme
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlin.math.roundToInt

private val SLIDE = 4.dp
private const val EXIT_MS = 170

@Composable
fun OverlayWindow(fights: StateFlow<TeamFights>, settings: OverlaySettings, sample: Boolean, suppressed: Boolean) {
    val teamFights by fights.collectAsState()
    val nowSec = rememberNowSec()
    var broken by remember { mutableStateOf(false) }
    val model = when {
        suppressed || broken -> null
        sample -> sampleOverlay()
        else -> overlayModel(teamFights.fights, nowSec)
    }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = model != null
    val retained = remember { Retained() }
    if (model != null) retained.model = model

    SwingWindow(
        visible = visibility.currentState || visibility.targetState,
        create = { createOverlayWindow { broken = true } },
        dispose = ComposeWindow::dispose,
    ) {
        LaunchedEffect(settings) { place(window, settings) }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * settings.scale, density.fontScale)) {
            val slidePx = with(LocalDensity.current) { SLIDE.roundToPx() }
            val fromRight = settings.corner == OverlayCorner.TOP_RIGHT || settings.corner == OverlayCorner.BOTTOM_RIGHT
            AshTheme {
                Box(Modifier.fillMaxSize().padding(SLIDE), contentAlignment = settings.corner.alignment) {
                    AnimatedVisibility(
                        visibleState = visibility,
                        enter = fadeIn(tween(AshMotion.MEDIUM, easing = AshMotion.enter)) +
                            slideInHorizontally(tween(AshMotion.MEDIUM, easing = AshMotion.enter)) { if (fromRight) slidePx else -slidePx },
                        exit = fadeOut(tween(EXIT_MS, easing = AshMotion.exit)),
                    ) {
                        retained.model?.let { OverlayCard(it, settings.opacity) }
                    }
                }
            }
        }
    }
}

private fun createOverlayWindow(onStyleFailure: () -> Unit): ComposeWindow = ComposeWindow().apply {
    type = Window.Type.UTILITY
    title = "Rustopsy"
    isUndecorated = true
    isTransparent = true
    isResizable = false
    isAlwaysOnTop = true
    focusableWindowState = false
    isAutoRequestFocus = false
    addComponentListener(object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) {
            if (!OverlayStyle.apply(this@apply)) onStyleFailure()
        }
    })
}

private class Retained {
    var model: OverlayModel? = null
}

private val OverlayCorner.alignment: Alignment
    get() = when (this) {
        OverlayCorner.TOP_LEFT -> Alignment.TopStart
        OverlayCorner.TOP_RIGHT -> Alignment.TopEnd
        OverlayCorner.BOTTOM_LEFT -> Alignment.BottomStart
        OverlayCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
    }

private fun place(window: ComposeWindow, settings: OverlaySettings) {
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
    val slide = (SLIDE.value * settings.scale).roundToInt()
    val width = ((OVERLAY_CARD_WIDTH + SLIDE * 2).value * settings.scale).roundToInt()
    val height = ((OVERLAY_CARD_MAX_HEIGHT + SLIDE * 2).value * settings.scale).roundToInt()
    val x = when (settings.corner) {
        OverlayCorner.TOP_LEFT, OverlayCorner.BOTTOM_LEFT -> screen.x + settings.x - slide
        OverlayCorner.TOP_RIGHT, OverlayCorner.BOTTOM_RIGHT -> screen.x + screen.width - settings.x - width + slide
    }
    val y = when (settings.corner) {
        OverlayCorner.TOP_LEFT, OverlayCorner.TOP_RIGHT -> screen.y + settings.y - slide
        OverlayCorner.BOTTOM_LEFT, OverlayCorner.BOTTOM_RIGHT -> screen.y + screen.height - settings.y - height + slide
    }
    window.setBounds(x, y, width, height)
}

private object OverlayStyle {
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TRANSPARENT = 0x20L
    private const val WS_EX_TOOLWINDOW = 0x80L
    private const val WS_EX_LAYERED = 0x80000L
    private const val WS_EX_NOACTIVATE = 0x8000000L
    private const val REQUIRED = WS_EX_TRANSPARENT or WS_EX_TOOLWINDOW or WS_EX_LAYERED or WS_EX_NOACTIVATE

    fun apply(window: ComposeWindow): Boolean = try {
        val hwnd = Native.getWindowPointer(window)
        val style = User32.GetWindowLongPtrW(hwnd, GWL_EXSTYLE)
        if (style and REQUIRED != REQUIRED) User32.SetWindowLongPtrW(hwnd, GWL_EXSTYLE, style or REQUIRED)
        val applied = User32.GetWindowLongPtrW(hwnd, GWL_EXSTYLE) and REQUIRED == REQUIRED
        if (!applied) appLogger.warning("Overlay window styles were not applied, overlay disabled")
        applied
    } catch (e: LinkageError) {
        appLogger.warning("Overlay window styles unavailable, overlay disabled: $e")
        false
    }

    private object User32 {
        init {
            Native.register(User32::class.java, "user32")
        }

        @JvmStatic external fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
        @JvmStatic external fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
    }
}
