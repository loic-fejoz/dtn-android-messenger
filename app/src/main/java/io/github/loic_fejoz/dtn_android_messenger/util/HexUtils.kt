package io.github.loic_fejoz.dtn_android_messenger.util

object HexUtils {
    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.isEmpty()) return ByteArray(0)
        require(clean.length % 2 == 0) { "Hex string length must be even" }
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
