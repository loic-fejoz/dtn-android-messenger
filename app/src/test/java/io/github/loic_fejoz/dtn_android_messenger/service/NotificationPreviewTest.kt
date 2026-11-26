package io.github.loic_fejoz.dtn_android_messenger.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationPreviewTest {
    @Test
    fun truncatesLargeUtf8Payloads() {
        val largeText = "A".repeat(5000)
        val preview = DtnEngineService.formatNotificationPreview(largeText.toByteArray(Charsets.UTF_8), null)
        assertTrue(preview.length <= 201)
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun detectsBinaryPayloadsSafely() {
        val binary = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte())
        val preview = DtnEngineService.formatNotificationPreview(binary, null)
        assertEquals("[Binary Payload]", preview)
    }

    @Test
    fun handlesEmptyPayload() {
        assertEquals("", DtnEngineService.formatNotificationPreview(ByteArray(0), null))
    }
}
