package xyz.thewhish.rustopsy.capture

import kotlin.test.Test
import kotlin.test.assertTrue

class SystemClipboardTest {
    @Test
    fun sequenceNumberIsReadFromUser32() {
        val first = clipboardSequenceNumber()
        val second = clipboardSequenceNumber()

        assertTrue(second >= first)
    }
}
