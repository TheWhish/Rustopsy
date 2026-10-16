package xyz.thewhish.rustopsy

import com.sun.jna.Callback
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import xyz.thewhish.rustopsy.platform.appLogger
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import kotlin.math.roundToInt

object WindowChrome {
    private const val RATIO_W = 16
    private const val RATIO_H = 9
    private const val MIN_CLIENT_WIDTH = 1120
    private const val GWLP_WNDPROC = -4
    private const val WM_SIZING = 0x0214
    private const val WMSZ_TOP = 3
    private const val WMSZ_TOPLEFT = 4
    private const val WMSZ_TOPRIGHT = 5
    private const val WMSZ_BOTTOM = 6
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val CAPTION_COLOR_ASH0 = 0x001A1C1E

    private val procedures = mutableListOf<WindowProc>()

    fun preferredClientWidth(): Int {
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val byHeight = (screen.height * 0.9 - 40) * RATIO_W / RATIO_H
        return minOf(1440.0, screen.width * 0.9, byHeight).roundToInt().coerceAtLeast(MIN_CLIENT_WIDTH)
    }

    fun install(window: JFrame) {
        if (window.isShowing) {
            apply(window)
            return
        }
        window.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) {
                window.removeWindowListener(this)
                apply(window)
            }
        })
    }

    private fun apply(window: JFrame) {
        fitClientToRatio(window)
        try {
            val hwnd = Native.getWindowPointer(window)
            darkTitleBar(hwnd)
            lockAspectRatio(hwnd)
        } catch (e: LinkageError) {
            appLogger.warning("Native window chrome unavailable: $e")
        }
    }

    private fun fitClientToRatio(window: JFrame) {
        val client = window.contentPane.size
        val frameWidth = window.width - client.width
        val frameHeight = window.height - client.height
        window.minimumSize = Dimension(MIN_CLIENT_WIDTH + frameWidth, MIN_CLIENT_WIDTH * RATIO_H / RATIO_W + frameHeight)
        window.setSize(window.width, client.width * RATIO_H / RATIO_W + frameHeight)
    }

    private fun darkTitleBar(hwnd: Pointer) {
        Dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(1), Int.SIZE_BYTES)
        Dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(CAPTION_COLOR_ASH0), Int.SIZE_BYTES)
    }

    private fun lockAspectRatio(hwnd: Pointer) {
        val previous = User32.GetWindowLongPtrW(hwnd, GWLP_WNDPROC)
        val procedure = WindowProc { handle, message, wParam, lParam ->
            if (message == WM_SIZING && lParam != 0L) constrain(handle, wParam.toInt(), Pointer(lParam))
            User32.CallWindowProcW(previous, handle, message, wParam, lParam)
        }
        procedures += procedure
        User32.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, procedure)
    }

    private fun constrain(hwnd: Pointer, edge: Int, rect: Pointer) {
        val window = Rect()
        val client = Rect()
        if (!User32.GetWindowRect(hwnd, window) || !User32.GetClientRect(hwnd, client)) return
        val frameWidth = (window.right - window.left) - (client.right - client.left)
        val frameHeight = (window.bottom - window.top) - (client.bottom - client.top)
        val left = rect.getInt(0)
        val top = rect.getInt(4)
        val right = rect.getInt(8)
        val bottom = rect.getInt(12)

        if (edge == WMSZ_TOP || edge == WMSZ_BOTTOM) {
            val clientHeight = bottom - top - frameHeight
            rect.setInt(8, left + (clientHeight.toDouble() * RATIO_W / RATIO_H).roundToInt() + frameWidth)
        } else {
            val clientWidth = right - left - frameWidth
            val height = (clientWidth.toDouble() * RATIO_H / RATIO_W).roundToInt() + frameHeight
            if (edge == WMSZ_TOPLEFT || edge == WMSZ_TOPRIGHT) rect.setInt(4, bottom - height) else rect.setInt(12, top + height)
        }
    }

    fun interface WindowProc : Callback {
        fun callback(hwnd: Pointer, message: Int, wParam: Long, lParam: Long): Long
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class Rect : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0
    }

    private object User32 {
        init {
            Native.register(User32::class.java, "user32")
        }

        @JvmStatic external fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Pointer
        @JvmStatic external fun SetWindowLongPtrW(hwnd: Pointer, index: Int, procedure: WindowProc): Pointer
        @JvmStatic external fun CallWindowProcW(previous: Pointer, hwnd: Pointer, message: Int, wParam: Long, lParam: Long): Long
        @JvmStatic external fun GetWindowRect(hwnd: Pointer, rect: Rect): Boolean
        @JvmStatic external fun GetClientRect(hwnd: Pointer, rect: Rect): Boolean
    }

    private object Dwmapi {
        init {
            Native.register(Dwmapi::class.java, "dwmapi")
        }

        @JvmStatic external fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }
}
