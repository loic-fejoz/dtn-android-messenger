package io.github.loic_fejoz.dtn_android_messenger.util

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object PayloadFileHelper {
    const val DEFAULT_MAX_TEXT_PREVIEW_BYTES = 256 * 1024

    /**
     * Copies [input] to [destFile] up to [maxBytes].
     * If the stream exceeds [maxBytes], aborts immediately, deletes [destFile], and returns -1L.
     */
    fun copyStreamBounded(
        input: InputStream,
        destFile: File,
        maxBytes: Long,
    ): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        FileOutputStream(destFile).use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    runCatching { output.close() }
                    safeDelete(destFile.absolutePath)
                    return -1L
                }
                output.write(buffer, 0, read)
            }
            output.flush()
        }
        return total
    }

    fun readTextPreviewBounded(
        file: File,
        maxBytes: Int = DEFAULT_MAX_TEXT_PREVIEW_BYTES,
    ): String {
        val fileSize = file.length()
        val toRead = fileSize.coerceAtMost(maxBytes.toLong()).toInt()
        val buffer = ByteArray(toRead)
        var offset = 0
        file.inputStream().use { input ->
            while (offset < toRead) {
                val r = input.read(buffer, offset, toRead - offset)
                if (r == -1) break
                offset += r
            }
        }
        val textContent = String(buffer, 0, offset, Charsets.UTF_8)
        return if (fileSize > maxBytes) {
            "$textContent\n\n[Truncated at ${maxBytes / 1024} KB — download file to view full content]"
        } else {
            textContent
        }
    }

    fun safeDelete(filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: SecurityException) {
        }
    }
}
