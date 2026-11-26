package io.github.loic_fejoz.dtn_android_messenger.util

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object PayloadUtils {
    private val htmlBodyMarkers = listOf("<head", "<body", "<title", "</html>", "</head>", "</body>")

    fun getPayloadFileExtension(filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) return "bin"

        detectMagicExtension(file)?.let { return it }

        val ext = file.extension.lowercase()
        if (ext.isNotEmpty() && ext != "bin") {
            return ext
        }

        return detectTextExtension(file) ?: "bin"
    }

    private fun ByteArray.matchesAt(
        offset: Int,
        vararg expected: Int,
    ): Boolean {
        if (offset + expected.size > size) return false
        for (i in expected.indices) {
            if (this[offset + i] != expected[i].toByte()) return false
        }
        return true
    }

    private fun detectMagicExtension(file: File): String? {
        val header = ByteArray(12)
        val bytesRead =
            try {
                file.inputStream().use { it.read(header) }
            } catch (_: IOException) {
                return null
            }
        if (bytesRead < 4) return null
        val slice = header.copyOf(bytesRead)
        return when {
            slice.matchesAt(0, 0x89, 0x50, 0x4E, 0x47) -> "png"
            slice.matchesAt(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            slice.matchesAt(0, 0x47, 0x49, 0x46) -> "gif"
            slice.matchesAt(0, 0x52, 0x49, 0x46, 0x46) && slice.matchesAt(8, 0x57, 0x45, 0x42, 0x50) -> "webp"
            slice.matchesAt(0, 0x42, 0x4D) -> "bmp"
            slice.matchesAt(0, 0x4F, 0x67, 0x67, 0x53) -> "ogg"
            slice.matchesAt(0, 0x49, 0x44, 0x33) ||
                (slice[0] == 0xFF.toByte() && (slice[1].toInt() and 0xE0) == 0xE0) -> "mp3"
            slice.matchesAt(4, 0x66, 0x74, 0x79, 0x70) -> "m4a"
            else -> null
        }
    }

    private fun detectTextExtension(file: File): String? {
        // Stream only first 4KB to avoid large memory allocations
        val headerBytes = ByteArray(4096)
        val bytesRead =
            try {
                file.inputStream().use { it.read(headerBytes) }
            } catch (_: IOException) {
                return null
            }
        if (bytesRead <= 0) return null

        val textLen = trimIncompleteUtf8Tail(headerBytes, bytesRead)
        if (containsControlBytes(headerBytes, textLen)) return "bin"

        val text =
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(headerBytes, 0, textLen))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }

        val cleanText = text.replace("\uFEFF", "").trim()
        return when {
            isHtmlDocument(cleanText) -> "html"
            text.startsWith("#") || text.contains("**") || text.contains("* ") -> "md"
            else -> "txt"
        }
    }

    private fun trimIncompleteUtf8Tail(
        bytes: ByteArray,
        bytesRead: Int,
    ): Int {
        if (bytesRead < bytes.size) return bytesRead
        var i = bytesRead - 1
        while (i >= 0 && (bytes[i].toInt() and 0xC0) == 0x80) {
            i--
        }
        return if (i >= 0 && (bytes[i].toInt() and 0x80) != 0) i else bytesRead
    }

    private fun containsControlBytes(
        bytes: ByteArray,
        length: Int,
    ): Boolean {
        for (i in 0 until length) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0 || b < 0x09 || b in 0x0E..0x1F) return true
        }
        return false
    }

    private fun isHtmlDocument(cleanText: String): Boolean {
        if (cleanText.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            cleanText.startsWith("<html", ignoreCase = true)
        ) {
            return true
        }
        return cleanText.contains("<html", ignoreCase = true) &&
            htmlBodyMarkers.any { cleanText.contains(it, ignoreCase = true) }
    }

    fun isImagePayload(filePath: String): Boolean {
        val ext = getPayloadFileExtension(filePath)
        return ext in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }

    fun isAudioPayload(filePath: String): Boolean {
        val ext = getPayloadFileExtension(filePath)
        return ext in listOf("ogg", "opus", "mp3", "m4a", "mp4", "wav", "amr")
    }

    fun isValidEid(eid: String): Boolean {
        val trimmed = eid.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        if (trimmed.equals("dtn:none", ignoreCase = true)) return true
        if (trimmed.startsWith("dtn://", ignoreCase = true)) {
            val rest = trimmed.substring(6).trim('/')
            return rest.isNotEmpty()
        }
        if (trimmed.startsWith("ipn:", ignoreCase = true)) {
            val ssp = trimmed.substring(4)
            val parts = ssp.split(".")
            if (parts.size != 2) return false
            val node = parts[0].toLongOrNull()
            val service = parts[1].toLongOrNull()
            return node != null && node >= 0L && service != null && service >= 0L
        }
        return false
    }

    fun isPrefixMatch(
        parent: String,
        child: String,
    ): Boolean {
        val p = parent.trim().lowercase()
        val c = child.trim().lowercase()
        if (p.isEmpty() || c.isEmpty()) return false
        if (p == c) return true
        val pNoSlash = p.trimEnd('/')
        val cNoSlash = c.trimEnd('/')
        if (pNoSlash.isNotEmpty() && pNoSlash == cNoSlash) return true
        val normalizedParent = if (p.endsWith("/")) p else "$p/"
        return c.startsWith(normalizedParent)
    }

    fun base64Encode(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1

            sb.append(table[b0 ushr 2])
            if (b1 != -1) {
                sb.append(table[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (b2 != -1) {
                    sb.append(table[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                    sb.append(table[b2 and 0x3F])
                } else {
                    sb.append(table[(b1 and 0x0F) shl 2])
                    sb.append('=')
                }
            } else {
                sb.append(table[(b0 and 0x03) shl 4])
                sb.append("==")
            }
            i += 3
        }
        return sb.toString()
    }
}
