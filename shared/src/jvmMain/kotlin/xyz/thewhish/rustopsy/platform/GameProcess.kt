package xyz.thewhish.rustopsy.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.io.IOException

object GameProcess {
    const val RUST_CLIENT = "RustClient.exe"

    private const val TH32CS_SNAPPROCESS = 0x2
    private const val INVALID_HANDLE = -1L

    fun isRunning(image: String = RUST_CLIENT): Boolean {
        val snapshot = Kernel32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
        if (snapshot == null || Pointer.nativeValue(snapshot) == INVALID_HANDLE) throw IOException("Process list unavailable")
        try {
            val entry = ProcessEntry()
            var found = Kernel32.Process32FirstW(snapshot, entry)
            while (found) {
                if (Native.toString(entry.szExeFile).equals(image, ignoreCase = true)) return true
                found = Kernel32.Process32NextW(snapshot, entry)
            }
            return false
        } finally {
            Kernel32.CloseHandle(snapshot)
        }
    }

    @Structure.FieldOrder(
        "dwSize", "cntUsage", "th32ProcessID", "th32DefaultHeapID", "th32ModuleID",
        "cntThreads", "th32ParentProcessID", "pcPriClassBase", "dwFlags", "szExeFile",
    )
    class ProcessEntry : Structure() {
        @JvmField var dwSize = 0
        @JvmField var cntUsage = 0
        @JvmField var th32ProcessID = 0
        @JvmField var th32DefaultHeapID: Pointer? = null
        @JvmField var th32ModuleID = 0
        @JvmField var cntThreads = 0
        @JvmField var th32ParentProcessID = 0
        @JvmField var pcPriClassBase = 0
        @JvmField var dwFlags = 0
        @JvmField var szExeFile = CharArray(260)

        init {
            dwSize = size()
        }
    }

    private object Kernel32 {
        init {
            Native.register(Kernel32::class.java, "kernel32")
        }

        @JvmStatic external fun CreateToolhelp32Snapshot(flags: Int, processId: Int): Pointer?
        @JvmStatic external fun Process32FirstW(snapshot: Pointer, entry: ProcessEntry): Boolean
        @JvmStatic external fun Process32NextW(snapshot: Pointer, entry: ProcessEntry): Boolean
        @JvmStatic external fun CloseHandle(handle: Pointer): Boolean
    }
}
