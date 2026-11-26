package io.github.loic_fejoz.dtn_android_messenger.cla

import io.github.loic_fejoz.dtn_android_messenger.protocol.Bpv7Parser
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle
import io.github.loic_fejoz.dtn_android_messenger.protocol.Eid
import io.github.loic_fejoz.dtn_android_messenger.protocol.HopCountBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PayloadBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PrimaryBlock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class FileClaAdapterTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createAdapter(
        maxBytes: Long = 10L * 1024L * 1024L,
        logs: MutableList<Pair<String, String>> = mutableListOf(),
    ): FileClaAdapter =
        FileClaAdapter(
            directoryFactory = LocalFileClaDirectoryFactory(),
            maxBundleSizeBytesProvider = { maxBytes },
            logger = { level, message -> logs.add(level to message) },
        )

    private fun createSampleBundleBytes(payloadText: String): ByteArray {
        val bundle =
            Bundle(
                primaryBlock =
                    PrimaryBlock(
                        destination = Eid("dtn://remote/chat"),
                        source = Eid("dtn://local/chat"),
                        reportTo = Eid("dtn://local/chat"),
                        creationTimestamp = Pair(1000L, 1L),
                        lifetimeMs = 3600_000L,
                    ),
                payloadBlock = PayloadBlock(data = payloadText.toByteArray()),
                hopCountBlock = HopCountBlock(hopLimit = 64, hopCount = 0),
                bibBlock = null,
            )
        return Bpv7Parser.serialize(bundle)
    }

    @Test
    fun ensureClaFolders_createsRxAndTxDirectories() {
        val adapter = createAdapter()
        val basePath = tempDir.toAbsolutePath().toString()

        assertTrue(adapter.ensureClaFolders(basePath, "Foo"))
        assertTrue(File(tempDir.toFile(), "Foo/RX").isDirectory)
        assertTrue(File(tempDir.toFile(), "Foo/TX").isDirectory)

        // Idempotent on subsequent calls
        assertTrue(adapter.ensureClaFolders(basePath, "Foo"))
    }

    @Test
    fun sanitizeFolderName_replacesUnsafeCharactersAndLeadingDots() {
        assertEquals("__foo_bar_baz", FileClaAdapter.sanitizeFolderName("../foo/bar:baz"))
        assertEquals("_hidden", FileClaAdapter.sanitizeFolderName(".hidden"))
        assertEquals("Foo", FileClaAdapter.sanitizeFolderName("Foo."))
        assertEquals("ab", FileClaAdapter.sanitizeFolderName("a\u0000b"))
        assertEquals("", FileClaAdapter.sanitizeFolderName("."))
        assertEquals("", FileClaAdapter.sanitizeFolderName("..."))
        assertEquals("", FileClaAdapter.sanitizeFolderName("   "))
    }

    @Test
    fun sendNamedBundles_writesRawCborBytesAndInvokesAck() =
        runBlocking {
            val adapter = createAdapter()
            val basePath = tempDir.toAbsolutePath().toString()
            val bundleBytes = createSampleBundleBytes("Hello File-CLA")
            var acked = false

            val result =
                adapter.sendNamedBundles(
                    baseTreeUri = basePath,
                    claName = "Foo",
                    bundles =
                        listOf(
                            FileClaAdapter.NamedBundle(
                                bundleId = "bundle-123",
                                bundleBytes = bundleBytes,
                                onAcknowledged = { acked = true },
                            ),
                        ),
                )

            assertTrue(result)
            assertTrue(acked)

            val txDir = File(tempDir.toFile(), "Foo/TX")
            val outFile = File(txDir, "bundle-123.bundle")
            assertTrue(outFile.exists())
            assertArrayEquals(bundleBytes, outFile.readBytes())
            assertFalse(File(txDir, "bundle-123.bundle.tmp").exists())

            val parsed = Bpv7Parser.deserialize(outFile.readBytes())
            assertEquals("Hello File-CLA", String(parsed.payloadBlock.data))
        }

    @Test
    fun sendNamedBundles_overwritesExistingBundleFileCleanly() =
        runBlocking {
            val adapter = createAdapter()
            val basePath = tempDir.toAbsolutePath().toString()
            val firstBytes = createSampleBundleBytes("First")
            val secondBytes = createSampleBundleBytes("Second")

            assertTrue(
                adapter.sendNamedBundles(
                    basePath,
                    "Foo",
                    listOf(FileClaAdapter.NamedBundle("dup-id", firstBytes)),
                ),
            )
            assertTrue(
                adapter.sendNamedBundles(
                    basePath,
                    "Foo",
                    listOf(FileClaAdapter.NamedBundle("dup-id", secondBytes)),
                ),
            )

            val txFiles = File(tempDir.toFile(), "Foo/TX").listFiles()?.map { it.name } ?: emptyList()
            assertEquals(listOf("dup-id.bundle"), txFiles)
            assertArrayEquals(secondBytes, File(tempDir.toFile(), "Foo/TX/dup-id.bundle").readBytes())
        }

    @Test
    fun scanIncoming_ingestsValidBundleAndDeletesOnlyOnTrue() =
        runBlocking {
            val adapter = createAdapter()
            val basePath = tempDir.toAbsolutePath().toString()
            assertTrue(adapter.ensureClaFolders(basePath, "Foo"))

            val bundleBytes = createSampleBundleBytes("Incoming RX")
            val rxFile = File(tempDir.toFile(), "Foo/RX/in-1.bundle")
            rxFile.writeBytes(bundleBytes)

            // Reject -> file MUST stay in RX (Responsibility Transfer)
            val rejectedCount = adapter.scanIncoming(basePath, "Foo") { false }
            assertEquals(0, rejectedCount)
            assertTrue(rxFile.exists())

            // Modify timestamp/size so failure cache clears, then accept -> file MUST be deleted
            rxFile.setLastModified(rxFile.lastModified() + 5000L)
            var received: ByteArray? = null
            val acceptedCount =
                adapter.scanIncoming(basePath, "Foo") { bytes ->
                    received = bytes
                    true
                }
            assertEquals(1, acceptedCount)
            assertNotNull(received)
            assertArrayEquals(bundleBytes, received)
            assertFalse(rxFile.exists())
        }

    @Test
    fun scanIncoming_ignoresHiddenTempAndSubdirectories() =
        runBlocking {
            val adapter = createAdapter()
            val basePath = tempDir.toAbsolutePath().toString()
            assertTrue(adapter.ensureClaFolders(basePath, "Foo"))

            val rxDir = File(tempDir.toFile(), "Foo/RX")
            val hiddenFile = File(rxDir, ".DS_Store").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val tmpFile = File(rxDir, "partial.bundle.tmp").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val subDir = File(rxDir, "nested").apply { mkdirs() }

            var listenerCalls = 0
            val count =
                adapter.scanIncoming(basePath, "Foo") {
                    listenerCalls++
                    true
                }

            assertEquals(0, count)
            assertEquals(0, listenerCalls)
            assertTrue(hiddenFile.exists())
            assertTrue(tmpFile.exists())
            assertTrue(subDir.isDirectory)
        }

    @Test
    fun scanIncoming_rejectsOversizedAndDeduplicatesWarningLogs() =
        runBlocking {
            val logs = mutableListOf<Pair<String, String>>()
            val adapter = createAdapter(maxBytes = 64L, logs = logs)
            val basePath = tempDir.toAbsolutePath().toString()
            assertTrue(adapter.ensureClaFolders(basePath, "Foo"))

            val rxDir = File(tempDir.toFile(), "Foo/RX")
            val oversizedFile = File(rxDir, "too-big.bundle").apply { writeBytes(ByteArray(128) { 0x42 }) }
            val corruptFile = File(rxDir, "corrupt.bundle").apply { writeBytes(byteArrayOf(0x01, 0x02, 0x03)) }

            assertEquals(0, adapter.scanIncoming(basePath, "Foo") { false })
            val warnCountAfterFirstScan = logs.count { it.first == "WARN" }
            assertEquals(2, warnCountAfterFirstScan)

            // Second scan with unchanged files must not emit duplicate warnings
            assertEquals(0, adapter.scanIncoming(basePath, "Foo") { false })
            assertEquals(warnCountAfterFirstScan, logs.count { it.first == "WARN" })
            assertTrue(oversizedFile.exists())
            assertTrue(corruptFile.exists())
        }

    @Test
    fun sendNamedBundles_fallsBackToDirectWriteWhenRenameFails() =
        runBlocking {
            val localFactory = LocalFileClaDirectoryFactory()
            val nonRenamingFactory =
                ClaDirectoryFactory { location ->
                    val base = localFactory.fromLocation(location) ?: return@ClaDirectoryFactory null
                    object : ClaDirectory {
                        private fun wrapDir(dir: ClaDirectory): ClaDirectory =
                            object : ClaDirectory {
                                override fun listFiles(): List<ClaFile> = dir.listFiles().map { wrapFile(it) }

                                override fun findFile(name: String): ClaFile? = dir.findFile(name)?.let { wrapFile(it) }

                                override fun findOrCreateDirectory(name: String): ClaDirectory? =
                                    dir.findOrCreateDirectory(name)?.let { wrapDir(it) }

                                override fun createFile(
                                    mimeType: String,
                                    displayName: String,
                                ): ClaFile? = dir.createFile(mimeType, displayName)?.let { wrapFile(it) }
                            }

                        private fun wrapFile(file: ClaFile): ClaFile =
                            object : ClaFile by file {
                                override fun renameTo(displayName: String): Boolean = false
                            }

                        override fun listFiles(): List<ClaFile> = base.listFiles().map { wrapFile(it) }

                        override fun findFile(name: String): ClaFile? = base.findFile(name)?.let { wrapFile(it) }

                        override fun findOrCreateDirectory(name: String): ClaDirectory? =
                            base.findOrCreateDirectory(name)?.let { wrapDir(it) }

                        override fun createFile(
                            mimeType: String,
                            displayName: String,
                        ): ClaFile? = base.createFile(mimeType, displayName)?.let { wrapFile(it) }
                    }
                }

            val adapter =
                FileClaAdapter(
                    directoryFactory = nonRenamingFactory,
                    maxBundleSizeBytesProvider = { 10L * 1024L * 1024L },
                    logger = { _, _ -> },
                )
            val basePath = tempDir.toAbsolutePath().toString()
            val bundleBytes = createSampleBundleBytes("Direct fallback")
            var acked = false

            val result =
                adapter.sendNamedBundles(
                    baseTreeUri = basePath,
                    claName = "Foo",
                    bundles = listOf(FileClaAdapter.NamedBundle("fallback-1", bundleBytes) { acked = true }),
                )

            assertTrue(result)
            assertTrue(acked)
            val txDir = File(tempDir.toFile(), "Foo/TX")
            assertArrayEquals(bundleBytes, File(txDir, "fallback-1.bundle").readBytes())
            assertFalse(File(txDir, "fallback-1.bundle.tmp").exists())
        }

    @Test
    fun sendNamedBundles_preservesExistingBundleWhenTmpWriteFails() =
        runBlocking {
            val localFactory = LocalFileClaDirectoryFactory()
            val failingTmpFactory =
                ClaDirectoryFactory { location ->
                    val base = localFactory.fromLocation(location) ?: return@ClaDirectoryFactory null
                    object : ClaDirectory {
                        private fun wrapDir(dir: ClaDirectory): ClaDirectory =
                            object : ClaDirectory {
                                override fun listFiles(): List<ClaFile> = dir.listFiles()

                                override fun findFile(name: String): ClaFile? = dir.findFile(name)

                                override fun findOrCreateDirectory(name: String): ClaDirectory? =
                                    dir.findOrCreateDirectory(name)?.let { wrapDir(it) }

                                override fun createFile(
                                    mimeType: String,
                                    displayName: String,
                                ): ClaFile? {
                                    val created = dir.createFile(mimeType, displayName) ?: return null
                                    return if (displayName.endsWith(".tmp")) {
                                        object : ClaFile by created {
                                            override fun writeBytes(bytes: ByteArray): Boolean = false
                                        }
                                    } else {
                                        created
                                    }
                                }
                            }

                        override fun listFiles(): List<ClaFile> = base.listFiles()

                        override fun findFile(name: String): ClaFile? = base.findFile(name)

                        override fun findOrCreateDirectory(name: String): ClaDirectory? =
                            base.findOrCreateDirectory(name)?.let { wrapDir(it) }

                        override fun createFile(
                            mimeType: String,
                            displayName: String,
                        ): ClaFile? = base.createFile(mimeType, displayName)
                    }
                }

            val basePath = tempDir.toAbsolutePath().toString()
            val goodAdapter = createAdapter()
            val originalBytes = createSampleBundleBytes("Original safe bundle")
            assertTrue(goodAdapter.sendNamedBundles(basePath, "Foo", listOf(FileClaAdapter.NamedBundle("safe-1", originalBytes))))

            val failingAdapter =
                FileClaAdapter(
                    directoryFactory = failingTmpFactory,
                    maxBundleSizeBytesProvider = { 10L * 1024L * 1024L },
                    logger = { _, _ -> },
                )
            assertFalse(failingAdapter.sendNamedBundles(basePath, "Foo", listOf(FileClaAdapter.NamedBundle("safe-1", createSampleBundleBytes("New")))))

            // Original bundle must still exist intact
            assertArrayEquals(originalBytes, File(tempDir.toFile(), "Foo/TX/safe-1.bundle").readBytes())
        }

    @Test
    fun scanIncoming_prunesRemovedFilesFromFailureCache() =
        runBlocking {
            val logs = mutableListOf<Pair<String, String>>()
            val adapter = createAdapter(logs = logs)
            val basePath = tempDir.toAbsolutePath().toString()
            assertTrue(adapter.ensureClaFolders(basePath, "Foo"))

            val rxFile = File(tempDir.toFile(), "Foo/RX/transient.bundle").apply { writeBytes(byteArrayOf(0x01, 0x02)) }
            val fixedTime = 1_700_000_000_000L
            rxFile.setLastModified(fixedTime)

            assertEquals(0, adapter.scanIncoming(basePath, "Foo") { false })
            assertEquals(1, logs.count { it.first == "WARN" })

            // Remove externally, scan so cache prunes, then recreate with identical name/mtime/size
            assertTrue(rxFile.delete())
            assertEquals(0, adapter.scanIncoming(basePath, "Foo") { false })

            rxFile.writeBytes(byteArrayOf(0x01, 0x02))
            rxFile.setLastModified(fixedTime)
            assertEquals(0, adapter.scanIncoming(basePath, "Foo") { false })
            assertEquals(2, logs.count { it.first == "WARN" })
        }

    @Test
    fun sendNamedBundles_continuesBatchWhenOnAcknowledgedThrows() =
        runBlocking {
            val adapter = createAdapter()
            val basePath = tempDir.toAbsolutePath().toString()
            var secondAcked = false

            val result =
                adapter.sendNamedBundles(
                    baseTreeUri = basePath,
                    claName = "Foo",
                    bundles =
                        listOf(
                            FileClaAdapter.NamedBundle("b1", createSampleBundleBytes("One")) {
                                error("Simulated DB error")
                            },
                            FileClaAdapter.NamedBundle("b2", createSampleBundleBytes("Two")) {
                                secondAcked = true
                            },
                        ),
                )

            assertTrue(result)
            assertTrue(secondAcked)
            assertTrue(File(tempDir.toFile(), "Foo/TX/b1.bundle").exists())
            assertTrue(File(tempDir.toFile(), "Foo/TX/b2.bundle").exists())
        }

    @Test
    fun scanIncoming_cachesUndeletableAcceptedFileToPreventDuplicateWarnings() =
        runBlocking {
            val logs = mutableListOf<Pair<String, String>>()
            val localFactory = LocalFileClaDirectoryFactory()
            val nonDeletingFactory =
                ClaDirectoryFactory { location ->
                    val base = localFactory.fromLocation(location) ?: return@ClaDirectoryFactory null
                    object : ClaDirectory {
                        private fun wrapDir(dir: ClaDirectory): ClaDirectory =
                            object : ClaDirectory {
                                override fun listFiles(): List<ClaFile> =
                                    dir.listFiles().map { file ->
                                        object : ClaFile by file {
                                            override fun delete(): Boolean = false
                                        }
                                    }

                                override fun findFile(name: String): ClaFile? = dir.findFile(name)

                                override fun findOrCreateDirectory(name: String): ClaDirectory? =
                                    dir.findOrCreateDirectory(name)?.let { wrapDir(it) }

                                override fun createFile(
                                    mimeType: String,
                                    displayName: String,
                                ): ClaFile? = dir.createFile(mimeType, displayName)
                            }

                        override fun listFiles(): List<ClaFile> = base.listFiles()

                        override fun findFile(name: String): ClaFile? = base.findFile(name)

                        override fun findOrCreateDirectory(name: String): ClaDirectory? =
                            base.findOrCreateDirectory(name)?.let { wrapDir(it) }

                        override fun createFile(
                            mimeType: String,
                            displayName: String,
                        ): ClaFile? = base.createFile(mimeType, displayName)
                    }
                }

            val adapter =
                FileClaAdapter(
                    directoryFactory = nonDeletingFactory,
                    maxBundleSizeBytesProvider = { 10L * 1024L * 1024L },
                    logger = { level, message -> logs.add(level to message) },
                )
            val basePath = tempDir.toAbsolutePath().toString()
            assertTrue(adapter.ensureClaFolders(basePath, "Foo"))
            File(tempDir.toFile(), "Foo/RX/readonly.bundle").writeBytes(createSampleBundleBytes("Hello"))

            assertEquals(1, adapter.scanIncoming(basePath, "Foo") { true })
            assertEquals(1, logs.count { it.first == "WARN" })

            // Second scan must not re-ingest or emit duplicate warning
            assertEquals(0, adapter.scanIncoming(basePath, "Foo") { true })
            assertEquals(1, logs.count { it.first == "WARN" })
        }
}
