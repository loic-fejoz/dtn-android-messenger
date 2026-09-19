package io.github.loic_fejoz.dtn_android_messenger.util

import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleRecord
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleState
import io.github.loic_fejoz.dtn_android_messenger.data.model.BpsecStatus
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import io.github.loic_fejoz.dtn_android_messenger.data.model.ViewerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AutoSaveUtilsTest {

    @Test
    fun testGenerateAutoSaveFileNameMp3() {
        val bundle = BundleRecord(
            bundleId = "bundle-123",
            destinationEid = "dtn://my-node/files",
            sourceEid = "dtn://peer/files",
            creationTimestamp = 1700000000000L, // 2023-11-14...
            sequenceNumber = 1,
            lifetimeMs = 3600000,
            payloadFilePath = "/tmp/dummy.bin",
            state = BundleState.RECEIVED,
            isRead = false,
            bpsecStatus = BpsecStatus.VALID,
            hopCount = 1
        )
        
        val fileName = AutoSaveUtils.generateAutoSaveFileName(bundle, "mp3")
        assertTrue(fileName.endsWith("_bundle-123.mp3"))
    }

    @Test
    fun testGetMimeTypeFromExtension() {
        assertEquals("audio/mpeg", AutoSaveUtils.getMimeTypeFromExtension("mp3"))
        assertEquals("audio/mp4", AutoSaveUtils.getMimeTypeFromExtension("m4a"))
        assertEquals("image/png", AutoSaveUtils.getMimeTypeFromExtension("png"))
        assertEquals("image/jpeg", AutoSaveUtils.getMimeTypeFromExtension("jpg"))
        assertEquals("text/markdown", AutoSaveUtils.getMimeTypeFromExtension("md"))
        assertEquals("text/plain", AutoSaveUtils.getMimeTypeFromExtension("txt"))
        assertEquals("application/octet-stream", AutoSaveUtils.getMimeTypeFromExtension("bin"))
    }

    @Test
    fun testSaveFileToDirectory(@TempDir tempDir: Path) {
        val sourceFile = File(tempDir.toFile(), "source_payload.bin")
        // MP3 ID3 header magic bytes
        val mp3Header = byteArrayOf(0x49.toByte(), 0x44.toByte(), 0x33.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte())
        sourceFile.writeBytes(mp3Header)

        val targetDir = File(tempDir.toFile(), "Podcasts")
        targetDir.mkdirs()

        val service = LocalService(
            serviceEid = "dtn://my-node/files",
            displayName = "File Exchange",
            viewerType = ViewerType.BUNDLE_LIST,
            autoSaveEnabled = true,
            autoSaveTargetDirectory = "Podcasts"
        )

        val bundle = BundleRecord(
            bundleId = "bundle-podcast-456",
            destinationEid = "dtn://my-node/files",
            sourceEid = "dtn://peer/files",
            creationTimestamp = 1700000000000L,
            sequenceNumber = 10,
            lifetimeMs = 3600000,
            payloadFilePath = sourceFile.absolutePath,
            state = BundleState.RECEIVED,
            isRead = false,
            bpsecStatus = BpsecStatus.VALID,
            hopCount = 1
        )

        val savedFile = AutoSaveUtils.savePayloadToFolder(service, bundle, sourceFile, targetDir)
        assertNotNull(savedFile)
        assertTrue(savedFile!!.exists())
        assertEquals(mp3Header.size.toLong(), savedFile.length())
        assertTrue(savedFile.name.endsWith("_bundle-podcast-456.mp3"))
    }

    @Test
    fun testAutoSaveDisabledReturnsNull(@TempDir tempDir: Path) {
        val sourceFile = File(tempDir.toFile(), "source_payload.bin")
        sourceFile.writeText("sample content")

        val targetDir = File(tempDir.toFile(), "Podcasts")
        targetDir.mkdirs()

        val service = LocalService(
            serviceEid = "dtn://my-node/files",
            displayName = "File Exchange",
            viewerType = ViewerType.BUNDLE_LIST,
            autoSaveEnabled = false,
            autoSaveTargetDirectory = "Podcasts"
        )

        val bundle = BundleRecord(
            bundleId = "bundle-podcast-789",
            destinationEid = "dtn://my-node/files",
            sourceEid = "dtn://peer/files",
            creationTimestamp = 1700000000000L,
            sequenceNumber = 11,
            lifetimeMs = 3600000,
            payloadFilePath = sourceFile.absolutePath,
            state = BundleState.RECEIVED,
            isRead = false,
            bpsecStatus = BpsecStatus.VALID,
            hopCount = 1
        )

        val savedFile = AutoSaveUtils.savePayloadToFolder(service, bundle, sourceFile, targetDir)
        assertNull(savedFile)
    }
}
