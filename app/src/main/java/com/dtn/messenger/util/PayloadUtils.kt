package com.dtn.messenger.util

import java.io.File
import java.nio.charset.StandardCharsets

object PayloadUtils {
    fun getPayloadFileExtension(filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) return "bin"
        try {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                val bytesRead = input.read(header)
                if (bytesRead >= 4) {
                    // Check PNG
                    if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) {
                        return "png"
                    }
                    // Check JPEG (FF D8 FF)
                    if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                        return "jpg"
                    }
                    // Check GIF (GIF)
                    if (header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte()) {
                        return "gif"
                    }
                    // Check WEBP (RIFFxxxxWEBP)
                    if (bytesRead >= 12 &&
                        header[0] == 'R'.toByte() && header[1] == 'I'.toByte() && header[2] == 'F'.toByte() && header[3] == 'F'.toByte() &&
                        header[8] == 'W'.toByte() && header[9] == 'E'.toByte() && header[10] == 'B'.toByte() && header[11] == 'P'.toByte()
                    ) {
                        return "webp"
                    }
                    // Check BMP (BM)
                    if (header[0] == 0x42.toByte() && header[1] == 0x4D.toByte()) {
                        return "bmp"
                    }
                    // Check OGG (OggS)
                    if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() && header[2] == 0x67.toByte() && header[3] == 0x53.toByte()) {
                        return "ogg"
                    }
                    // Check MP3 (ID3 or MPEG frame sync FF FB/F3/F2)
                    if ((header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) ||
                        (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0)
                    ) {
                        return "mp3"
                    }
                    // Check M4A/MP4 (ftyp at offset 4)
                    if (bytesRead >= 8 && header[4] == 0x66.toByte() && header[5] == 0x74.toByte() && header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) {
                        return "m4a"
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        val ext = file.extension.lowercase()
        if (ext.isNotEmpty() && ext != "bin") {
            return ext
        }

        // Check if valid text / Markdown
        try {
            val text = String(file.readBytes(), StandardCharsets.UTF_8)
            if (text.startsWith("#") || text.contains("**") || text.contains("* ")) {
                return "md"
            }
            return "txt"
        } catch (e: Exception) {
            // Ignore
        }

        return "bin"
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
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("dtn://") || trimmed.startsWith("ipn:")
    }

    fun isPrefixMatch(
        parent: String,
        child: String,
    ): Boolean {
        val p = parent.trim().lowercase()
        val c = child.trim().lowercase()
        if (p == c) return true
        val normalizedParent = if (p.endsWith("/")) p else "$p/"
        return c.startsWith(normalizedParent)
    }
}
