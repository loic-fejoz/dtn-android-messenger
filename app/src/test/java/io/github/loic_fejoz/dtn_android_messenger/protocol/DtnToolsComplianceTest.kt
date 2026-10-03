package io.github.loic_fejoz.dtn_android_messenger.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.InputStream

class DtnToolsComplianceTest {

    private fun loadFixture(filename: String): ByteArray {
        val path = "/fixtures/dtn_tools/$filename"
        val stream: InputStream = javaClass.getResourceAsStream(path)
            ?: throw IllegalArgumentException("Fixture file not found: $path")
        return stream.readBytes()
    }

    @Test
    fun testNominalBasicBundle() {
        try {
            val bytes = loadFixture("nominal_basic.bundle")
            val bundle = Bpv7Parser.deserialize(bytes)

            assertNotNull(bundle)
            assertEquals(7, bundle.primaryBlock.version)
            assertEquals("dtn://node-b/chat", bundle.primaryBlock.destination.uri)
            assertEquals("dtn://node-a/chat", bundle.primaryBlock.source.uri)
            assertEquals("Hello DTN Tools Nominal Payload", bundle.payloadBlock.data.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun testNominalHopCountBundle() {
        val bytes = loadFixture("nominal_hop_count.bundle")
        val bundle = Bpv7Parser.deserialize(bytes)

        assertNotNull(bundle)
        assertNotNull(bundle.hopCountBlock)
        assertEquals(15, bundle.hopCountBlock?.hopLimit)
        assertEquals(3, bundle.hopCountBlock?.hopCount)
    }

    @Test
    fun testNominalPrevNodeBundle() {
        val bytes = loadFixture("nominal_prev_node.bundle")
        // Previous Node block (Type 6) is parsed as an extension block or skipped cleanly
        val bundle = Bpv7Parser.deserialize(bytes)
        assertNotNull(bundle)
    }

    @Test
    fun testNominalBundleAgeBundle() {
        val bytes = loadFixture("nominal_bundle_age.bundle")
        // Bundle Age block (Type 7) is parsed as an extension block or skipped cleanly
        val bundle = Bpv7Parser.deserialize(bytes)
        assertNotNull(bundle)
    }

    @Test
    fun testInvalidCrcBundleRejected() {
        val bytes = loadFixture("invalid_crc.bundle")
        assertThrows(Exception::class.java) {
            Bpv7Parser.deserialize(bytes)
        }
    }

    @Test
    fun testInvalidVersionBundleRejected() {
        val bytes = loadFixture("invalid_version.bundle")
        assertThrows(Exception::class.java) {
            Bpv7Parser.deserialize(bytes)
        }
    }

    @Test
    fun testInvalidBcbEncryptedBundleHandling() {
        val bytes = loadFixture("invalid_bcb_encrypted.bundle")
        // Type 12 BPSec BCB is parsed safely without failing or crashing
        try {
            val bundle = Bpv7Parser.deserialize(bytes)
            assertNotNull(bundle)
        } catch (e: Exception) {
            assertTrue(e is IllegalArgumentException)
        }
    }

    @Test
    fun testCorruptedBitflipsDoNotCrash() {
        val bytes = loadFixture("corrupted_bitflips.bundle")
        try {
            Bpv7Parser.deserialize(bytes)
        } catch (e: Exception) {
            // Expected decoding failure; ensure no unhandled fatal error or infinite loop occurs
        }
    }
}
