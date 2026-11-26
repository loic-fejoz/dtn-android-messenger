package io.github.loic_fejoz.dtn_android_messenger.util

object Base64Utils {
    private const val BASE64_TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1

            sb.append(BASE64_TABLE[b0 ushr 2])
            if (b1 != -1) {
                sb.append(BASE64_TABLE[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (b2 != -1) {
                    sb.append(BASE64_TABLE[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                    sb.append(BASE64_TABLE[b2 and 0x3F])
                } else {
                    sb.append(BASE64_TABLE[(b1 and 0x0F) shl 2])
                    sb.append('=')
                }
            } else {
                sb.append(BASE64_TABLE[(b0 and 0x03) shl 4])
                sb.append("==")
            }
            i += 3
        }
        return sb.toString()
    }

    @Suppress("TooGenericExceptionCaught")
    fun decode(encoded: String): ByteArray {
        return try {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        } catch (_: Throwable) {
            val getDecoder = Class.forName("java.util.Base64").getMethod("getDecoder")
            val decoder = getDecoder.invoke(null)
            val decode = decoder.javaClass.getMethod("decode", String::class.java)
            decode.invoke(decoder, encoded) as ByteArray
        }
    }
}
