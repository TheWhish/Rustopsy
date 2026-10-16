package xyz.thewhish.rustopsy.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.io.IOException

object Dpapi {
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1
    private val ENTROPY = "xyz.thewhish.rustopsy.session".toByteArray()

    fun protect(data: ByteArray): ByteArray = transform(data, "CryptProtectData") { input, entropy, output ->
        Crypt32.CryptProtectData(input, null, entropy, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
    }

    fun unprotect(data: ByteArray): ByteArray = transform(data, "CryptUnprotectData") { input, entropy, output ->
        Crypt32.CryptUnprotectData(input, null, entropy, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
    }

    private fun transform(data: ByteArray, name: String, call: (DataBlob, DataBlob, DataBlob) -> Boolean): ByteArray {
        require(data.isNotEmpty()) { "Nothing to $name" }
        val output = DataBlob()
        if (!call(DataBlob.of(data), DataBlob.of(ENTROPY), output)) throw IOException("$name failed")
        val buffer = checkNotNull(output.pbData) { "$name returned no data" }
        try {
            return buffer.getByteArray(0, output.cbData)
        } finally {
            Kernel32.LocalFree(buffer)
        }
    }

    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob : Structure() {
        @JvmField var cbData = 0
        @JvmField var pbData: Pointer? = null

        companion object {
            fun of(bytes: ByteArray) = DataBlob().apply {
                cbData = bytes.size
                pbData = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
            }
        }
    }

    private object Crypt32 {
        init {
            Native.register(Crypt32::class.java, "crypt32")
        }

        @JvmStatic external fun CryptProtectData(
            dataIn: DataBlob,
            description: Pointer?,
            entropy: DataBlob?,
            reserved: Pointer?,
            prompt: Pointer?,
            flags: Int,
            dataOut: DataBlob,
        ): Boolean

        @JvmStatic external fun CryptUnprotectData(
            dataIn: DataBlob,
            description: Pointer?,
            entropy: DataBlob?,
            reserved: Pointer?,
            prompt: Pointer?,
            flags: Int,
            dataOut: DataBlob,
        ): Boolean
    }

    private object Kernel32 {
        init {
            Native.register(Kernel32::class.java, "kernel32")
        }

        @JvmStatic external fun LocalFree(memory: Pointer): Pointer?
    }
}
