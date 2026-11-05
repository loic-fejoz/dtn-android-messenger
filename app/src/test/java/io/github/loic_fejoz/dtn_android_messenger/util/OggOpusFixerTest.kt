package io.github.loic_fejoz.dtn_android_messenger.util

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class OggOpusFixerTest {

    private fun buildPage(
        flags: Int,
        granulePos: Long,
        seq: Int,
        body: ByteArray,
        packetSizes: List<Int> = listOf(body.size)
    ): ByteArray {
        val nsegs = packetSizes.sumOf { (it / 255) + 1 }
        val segTable = mutableListOf<Byte>()
        for (sz in packetSizes) {
            var rem = sz
            while (rem >= 255) {
                segTable.add(255.toByte())
                rem -= 255
            }
            segTable.add(rem.toByte())
        }

        val pageLen = 27 + segTable.size + body.size
        val page = ByteArray(pageLen)

        // Capture pattern
        page[0] = 0x4F; page[1] = 0x67; page[2] = 0x67; page[3] = 0x53
        page[4] = 0 // version
        page[5] = flags.toByte()

        // Granulepos
        for (i in 0 until 8) {
            page[6 + i] = ((granulePos ushr (i * 8)) and 0xFF).toByte()
        }

        // Serial = 0x12345678
        val serial = 0x12345678
        for (i in 0 until 4) {
            page[14 + i] = ((serial ushr (i * 8)) and 0xFF).toByte()
        }

        // Sequence number
        for (i in 0 until 4) {
            page[18 + i] = ((seq ushr (i * 8)) and 0xFF).toByte()
        }

        // Number of segments
        page[26] = segTable.size.toByte()
        for (i in segTable.indices) {
            page[27 + i] = segTable[i]
        }

        // Body
        System.arraycopy(body, 0, page, 27 + segTable.size, body.size)

        // Compute and set CRC
        val crc = OggOpusFixer.computeOggCrc(page, 0, pageLen)
        for (i in 0 until 4) {
            page[22 + i] = ((crc ushr (i * 8)) and 0xFF).toByte()
        }

        return page
    }

    private fun createBuggyAndroidOggOpus(): ByteArray {
        // Page 0: OpusHead with preskip = 52 (Stagefright bug)
        val opusHead = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(opusHead, 0)
        opusHead[8] = 1 // version
        opusHead[9] = 1 // channels = 1
        opusHead[10] = 52.toByte() // preskip = 52 (< 312)
        opusHead[11] = 0
        opusHead[12] = 0x80.toByte(); opusHead[13] = 0xBB.toByte(); opusHead[14] = 0; opusHead[15] = 0 // 48000 Hz
        opusHead[16] = 0; opusHead[17] = 0 // gain
        opusHead[18] = 0 // mapping family

        val p0 = buildPage(flags = 0x02, granulePos = 0, seq = 0, body = opusHead)

        // Page 1: OpusTags
        val opusTags = ByteArray(24)
        "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(opusTags, 0)
        val p1 = buildPage(flags = 0x00, granulePos = 0, seq = 1, body = opusTags)

        // Page 2: Audio packet 1 (20ms CELT FB, TOC = 0xFC, 960 samples)
        // Stagefright bug: granulepos = 0 outside headers!
        val audioPkt1 = ByteArray(100)
        audioPkt1[0] = 0xFC.toByte() // 20ms frame
        val p2 = buildPage(flags = 0x00, granulePos = 0L, seq = 2, body = audioPkt1)

        // Page 3: Audio packet 2 (20ms CELT FB, TOC = 0xFC, 960 samples)
        // Stagefright bug: EOS flag not set on last page!
        val audioPkt2 = ByteArray(100)
        audioPkt2[0] = 0xFC.toByte()
        val p3 = buildPage(flags = 0x00, granulePos = 960L, seq = 3, body = audioPkt2)

        return p0 + p1 + p2 + p3
    }

    @Test
    fun testIsOggOpus() {
        val buggy = createBuggyAndroidOggOpus()
        assertTrue(OggOpusFixer.isOggOpus(buggy))

        assertFalse(OggOpusFixer.isOggOpus(ByteArray(0)))
        assertFalse(OggOpusFixer.isOggOpus("Hello World".toByteArray()))
        assertFalse(OggOpusFixer.isOggOpus(ByteArray(30)))
    }

    @Test
    fun testCalculateOpusSamples() {
        // CELT 20ms FB: config 31 (0x1F), code 0 -> TOC = (31 << 3) | 0 = 0xF8
        assertEquals(960, OggOpusFixer.calculateOpusSamples(0xF8, 0))

        // CELT 10ms FB: config 30, code 0 -> TOC = (30 << 3) = 0xF0
        assertEquals(480, OggOpusFixer.calculateOpusSamples(0xF0, 0))

        // SILK 20ms: config 1, code 0 -> TOC = (1 << 3) = 0x08
        assertEquals(960, OggOpusFixer.calculateOpusSamples(0x08, 0))

        // SILK 60ms: config 3, code 0 -> TOC = (3 << 3) = 0x18
        assertEquals(2880, OggOpusFixer.calculateOpusSamples(0x18, 0))

        // Arbitrary count (code 3): 3 frames of 20ms
        assertEquals(960 * 3, OggOpusFixer.calculateOpusSamples(0xF8 or 3, 3))
    }

    @Test
    fun testFixBuggyAndroidOggOpus(@TempDir tempDir: Path) {
        val buggyBytes = createBuggyAndroidOggOpus()
        val fixedBytes = OggOpusFixer.fixOggOpusBytes(buggyBytes)

        assertTrue(fixedBytes.size == buggyBytes.size)

        val file = File(tempDir.toFile(), "test_fixed.ogg")
        file.writeBytes(fixedBytes)

        // Check OpusHead preskip (Page 0 body + 10..11)
        val p0Body = 27 + 1
        val preskip = (fixedBytes[p0Body + 10].toInt() and 0xFF) or ((fixedBytes[p0Body + 11].toInt() and 0xFF) shl 8)
        assertEquals(312, preskip, "Pre-skip must be updated to at least 312 per RFC 7845 §5.1")

        // Parse fixed pages
        var pos = 0
        var pageIdx = 0
        var lastPageFlags = 0
        var firstAudioGranule = 0L

        while (pos + 27 <= fixedBytes.size) {
            val flags = fixedBytes[pos + 5].toInt() and 0xFF
            var granule = 0L
            for (i in 0 until 8) {
                granule = granule or ((fixedBytes[pos + 6 + i].toLong() and 0xFF) shl (i * 8))
            }
            val nsegs = fixedBytes[pos + 26].toInt() and 0xFF
            var bodyLen = 0
            for (i in 0 until nsegs) {
                bodyLen += fixedBytes[pos + 27 + i].toInt() and 0xFF
            }
            val pageLen = 27 + nsegs + bodyLen

            // Verify page CRC
            val recordedCrc = (fixedBytes[pos + 22].toInt() and 0xFF) or
                              ((fixedBytes[pos + 23].toInt() and 0xFF) shl 8) or
                              ((fixedBytes[pos + 24].toInt() and 0xFF) shl 16) or
                              ((fixedBytes[pos + 25].toInt() and 0xFF) shl 24)

            val checkCopy = fixedBytes.copyOfRange(pos, pos + pageLen)
            checkCopy[22] = 0; checkCopy[23] = 0; checkCopy[24] = 0; checkCopy[25] = 0
            val computedCrc = OggOpusFixer.computeOggCrc(checkCopy, 0, pageLen)
            assertEquals(computedCrc, recordedCrc, "Page $pageIdx CRC must be valid")

            if (pageIdx == 2) {
                firstAudioGranule = granule
            }
            lastPageFlags = flags

            pos += pageLen
            pageIdx++
        }

        // First audio page granulepos must be >= preskip (312 + 960 = 1272)
        assertEquals(1272L, firstAudioGranule, "First audio page granulepos must be preskip + samples")
        assertTrue(firstAudioGranule >= 312L)

        // Last page must have EOS flag (0x04)
        assertTrue((lastPageFlags and 0x04) != 0, "Last Ogg page must have EOS flag set")

        // Validate with official opusinfo / ogginfo tools if present on the host
        val hasOpusInfo = File("/usr/bin/opusinfo").exists()
        val hasOggInfo = File("/usr/bin/ogginfo").exists()

        if (hasOpusInfo) {
            val proc = ProcessBuilder("opusinfo", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            println("opusinfo output:\n$output")
            assertEquals(0, exitCode, "opusinfo must return 0 without warnings on fixed file")
            assertTrue(output.contains("Logical stream 1 ended"))
            assertFalse(output.contains("Negative or zero granulepos"))
            assertFalse(output.contains("EOS not set"))
        }

        if (hasOggInfo) {
            val proc = ProcessBuilder("ogginfo", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            println("ogginfo output:\n$output")
            assertEquals(0, exitCode, "ogginfo must return 0 without warnings on fixed file")
            assertFalse(output.contains("EOS non positionné") || output.contains("EOS not set"))
        }
    }

    @Test
    fun testFixOggOpusFileInPlace(@TempDir tempDir: Path) {
        val file = File(tempDir.toFile(), "voice.ogg")
        file.writeBytes(createBuggyAndroidOggOpus())

        val result = OggOpusFixer.fixOggOpusFile(file)
        assertTrue(result)

        val fixedBytes = file.readBytes()
        val p0Body = 27 + 1
        val preskip = (fixedBytes[p0Body + 10].toInt() and 0xFF) or ((fixedBytes[p0Body + 11].toInt() and 0xFF) shl 8)
        assertEquals(312, preskip)
    }

    @Test
    fun testNonOggIgnored() {
        val txt = "Hello DTN".toByteArray()
        val result = OggOpusFixer.fixOggOpusBytes(txt)
        assertArrayEquals(txt, result)

        val empty = ByteArray(0)
        assertArrayEquals(empty, OggOpusFixer.fixOggOpusBytes(empty))
    }
}
