package com.dtn.messenger.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PayloadUtilsTest {
    @Test
    fun testGetPayloadFileExtensionNonExistent() {
        val ext = PayloadUtils.getPayloadFileExtension("non_existent_file_path_123.bin")
        assertEquals("bin", ext)
    }

    @Test
    fun testGetPayloadFileExtensionPng(
        @TempDir tempDir: Path,
    ) {
        val file = File(tempDir.toFile(), "test.png")
        file.writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()))
        val ext = PayloadUtils.getPayloadFileExtension(file.absolutePath)
        assertEquals("png", ext)
    }

    @Test
    fun testGetPayloadFileExtensionJpg(
        @TempDir tempDir: Path,
    ) {
        val file = File(tempDir.toFile(), "test.jpg")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00.toByte()))
        val ext = PayloadUtils.getPayloadFileExtension(file.absolutePath)
        assertEquals("jpg", ext)
    }

    @Test
    fun testGetPayloadFileExtensionGif(
        @TempDir tempDir: Path,
    ) {
        val file = File(tempDir.toFile(), "test.gif")
        file.writeBytes(byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x00.toByte()))
        val ext = PayloadUtils.getPayloadFileExtension(file.absolutePath)
        assertEquals("gif", ext)
    }

    @Test
    fun testGetPayloadFileExtensionWebp(
        @TempDir tempDir: Path,
    ) {
        val file = File(tempDir.toFile(), "test.webp")
        // "RIFF" prefix, followed by 4 arbitrary size bytes, followed by "WEBP"
        val header = ByteArray(12)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'E'.code.toByte()
        header[10] = 'B'.code.toByte()
        header[11] = 'P'.code.toByte()
        file.writeBytes(header)
        val ext = PayloadUtils.getPayloadFileExtension(file.absolutePath)
        assertEquals("webp", ext)
    }

    @Test
    fun testGetPayloadFileExtensionTextAndMarkdown(
        @TempDir tempDir: Path,
    ) {
        val mdFile = File(tempDir.toFile(), "test_doc.bin")
        mdFile.writeText("# Title\nThis is a **markdown** document.")
        assertEquals("md", PayloadUtils.getPayloadFileExtension(mdFile.absolutePath))

        val txtFile = File(tempDir.toFile(), "plain.bin")
        txtFile.writeText("Simple plain text message.")
        assertEquals("txt", PayloadUtils.getPayloadFileExtension(txtFile.absolutePath))
    }

    @Test
    fun testIsValidEid() {
        assertTrue(PayloadUtils.isValidEid("dtn://node-1/chat"))
        assertTrue(PayloadUtils.isValidEid("ipn:1.2"))
        assertFalse(PayloadUtils.isValidEid(""))
        assertFalse(PayloadUtils.isValidEid("http://example.com"))
    }

    @Test
    fun testIsPrefixMatch() {
        assertTrue(PayloadUtils.isPrefixMatch("dtn://node-1", "dtn://node-1/chat"))
        assertTrue(PayloadUtils.isPrefixMatch("dtn://node-1/chat", "dtn://node-1/chat"))
        assertFalse(PayloadUtils.isPrefixMatch("dtn://node-1/chat", "dtn://node-2/chat"))
    }
}
