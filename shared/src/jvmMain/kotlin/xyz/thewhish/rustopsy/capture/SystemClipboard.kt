package xyz.thewhish.rustopsy.capture

import com.sun.jna.Native
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

private object User32 {
    init {
        Native.register(User32::class.java, "user32")
    }

    @JvmStatic
    external fun GetClipboardSequenceNumber(): Int
}

fun clipboardSequenceNumber(): Long = User32.GetClipboardSequenceNumber().toLong() and 0xFFFF_FFFFL

fun readClipboardText(): String? =
    try {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    } catch (_: UnsupportedFlavorException) {
        null
    } catch (_: IOException) {
        null
    }
