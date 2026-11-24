package io.github.loic_fejoz.dtn_android_messenger.util

import java.io.File

/**
 * Utility to repair and normalize Ogg Opus audio containers produced by buggy encoders,
 * specifically Android Stagefright's OggWriter (RFC 7845 / RFC 3533 compliance).
 *
 * Stagefright's OggWriter produces known conformance issues:
 * 1. Missing EOS (End Of Stream) flag on the last Ogg page (bit 2 = 0x04).
 * 2. Non-compliant granulepos (0) on the first audio page, outside headers.
 * 3. Implausibly low pre-skip in OpusHead (52 samples vs minimum 312 required by RFC 7845 §5.1),
 *    which leads to negative playback timestamps.
 *
 * These issues cause strict demuxers (like Mozilla Firefox's OggDemuxer) to abort playback with
 * NS_ERROR_DOM_MEDIA_DECODE_ERR (0x806e0006).
 */
object OggOpusFixer {

    private const val MIN_PRE_SKIP = 312

    private val OGG_CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        for (j in 0 until 8) {
            r = if ((r and 0x80000000.toInt()) != 0) {
                (r shl 1) xor 0x04C11DB7
            } else {
                r shl 1
            }
        }
        r
    }

    /**
     * Computes the 32-bit CRC checksum for an Ogg page (RFC 3533).
     */
    fun computeOggCrc(data: ByteArray, start: Int, length: Int): Int {
        var crc = 0
        for (i in start until start + length) {
            val b = data[i].toInt() and 0xFF
            crc = (crc shl 8) xor OGG_CRC_TABLE[((crc ushr 24) xor b) and 0xFF]
        }
        return crc
    }

    /**
     * Checks if the byte array begins with an Ogg capture pattern and contains an OpusHead packet.
     */
    fun isOggOpus(data: ByteArray): Boolean {
        if (data.size < 27) return false
        if (data[0] != 0x4F.toByte() || data[1] != 0x67.toByte() ||
            data[2] != 0x67.toByte() || data[3] != 0x53.toByte()
        ) {
            return false
        }
        val nsegs = data[26].toInt() and 0xFF
        val bodyOffset = 27 + nsegs
        if (data.size < bodyOffset + 8) return false
        return data[bodyOffset] == 'O'.code.toByte() &&
               data[bodyOffset + 1] == 'p'.code.toByte() &&
               data[bodyOffset + 2] == 'u'.code.toByte() &&
               data[bodyOffset + 3] == 's'.code.toByte() &&
               data[bodyOffset + 4] == 'H'.code.toByte() &&
               data[bodyOffset + 5] == 'e'.code.toByte() &&
               data[bodyOffset + 6] == 'a'.code.toByte() &&
               data[bodyOffset + 7] == 'd'.code.toByte()
    }

    /**
     * Calculates the number of 48 kHz PCM samples in an Opus packet based on its TOC byte (RFC 6716 §3.1).
     */
    fun calculateOpusSamples(toc: Int, frameCountByte: Int): Int {
        if (toc < 0) return 0
        val config = (toc ushr 3) and 0x1F
        val code = toc and 0x03

        val samplesPerFrame = when {
            config >= 16 -> {
                // CELT
                when (config and 0x03) {
                    0 -> 120  // 2.5 ms at 48kHz
                    1 -> 240  // 5 ms
                    2 -> 480  // 10 ms
                    else -> 960 // 20 ms
                }
            }
            config >= 12 -> {
                // Hybrid
                if ((config and 0x01) == 0) 480 else 960
            }
            else -> {
                // SILK
                when (config and 0x03) {
                    0 -> 480  // 10 ms
                    1 -> 960  // 20 ms
                    2 -> 1920 // 40 ms
                    else -> 2880 // 60 ms
                }
            }
        }

        val frames = when (code) {
            0 -> 1
            1, 2 -> 2
            else -> {
                val m = frameCountByte and 0x3F
                if (m <= 0) 1 else m
            }
        }

        return samplesPerFrame * frames
    }

    private data class PageInfo(
        val offset: Int,
        val length: Int,
        val nsegs: Int,
        val bodyOffset: Int,
        val bodyLength: Int,
        val flags: Int,
        val granulePos: Long,
    )

    /**
     * Analyzes and sanitizes an Ogg Opus stream to ensure strict RFC 7845 and RFC 3533 compliance:
     * - Ensures pre-skip >= 312 in OpusHead.
     * - Fixes non-compliant / 0 granule positions on audio pages.
     * - Guarantees EOS (0x04) flag on the final stream page.
     * - Recomputes Ogg page CRC-32 checksums for any modified pages.
     */
    fun fixOggOpusBytes(raw: ByteArray): ByteArray {
        if (!isOggOpus(raw)) return raw

        val data = raw.clone()
        val n = data.size

        val pages = mutableListOf<PageInfo>()
        var pos = 0
        while (pos + 27 <= n) {
            if (data[pos] != 0x4F.toByte() || data[pos + 1] != 0x67.toByte() ||
                data[pos + 2] != 0x67.toByte() || data[pos + 3] != 0x53.toByte()
            ) {
                break
            }
            val flags = data[pos + 5].toInt() and 0xFF
            val granulePos = readLittleEndianLong(data, pos + 6)
            val nsegs = data[pos + 26].toInt() and 0xFF
            if (pos + 27 + nsegs > n) break

            var bodyLen = 0
            for (i in 0 until nsegs) {
                bodyLen += data[pos + 27 + i].toInt() and 0xFF
            }
            val pageLen = 27 + nsegs + bodyLen
            if (pos + pageLen > n) break

            pages.add(PageInfo(pos, pageLen, nsegs, pos + 27 + nsegs, bodyLen, flags, granulePos))
            pos += pageLen
        }

        if (pages.isEmpty()) return raw

        // Page 0: OpusHead
        val p0 = pages[0]
        var preskip = MIN_PRE_SKIP
        if (p0.bodyLength >= 12 &&
            data[p0.bodyOffset] == 'O'.code.toByte() &&
            data[p0.bodyOffset + 1] == 'p'.code.toByte() &&
            data[p0.bodyOffset + 2] == 'u'.code.toByte() &&
            data[p0.bodyOffset + 3] == 's'.code.toByte() &&
            data[p0.bodyOffset + 4] == 'H'.code.toByte() &&
            data[p0.bodyOffset + 5] == 'e'.code.toByte() &&
            data[p0.bodyOffset + 6] == 'a'.code.toByte() &&
            data[p0.bodyOffset + 7] == 'd'.code.toByte()
        ) {
            val origPreskip = (data[p0.bodyOffset + 10].toInt() and 0xFF) or
                              ((data[p0.bodyOffset + 11].toInt() and 0xFF) shl 8)
            if (origPreskip < MIN_PRE_SKIP) {
                preskip = MIN_PRE_SKIP
                data[p0.bodyOffset + 10] = (preskip and 0xFF).toByte()
                data[p0.bodyOffset + 11] = ((preskip ushr 8) and 0xFF).toByte()

                // Recalculate Page 0 CRC
                data[p0.offset + 22] = 0
                data[p0.offset + 23] = 0
                data[p0.offset + 24] = 0
                data[p0.offset + 25] = 0
                val crc = computeOggCrc(data, p0.offset, p0.length)
                writeLittleEndianInt(data, p0.offset + 22, crc)
            } else {
                preskip = origPreskip
            }
        }

        // Find where audio pages begin (after OpusHead and OpusTags header pages)
        var firstAudioIdx = 1
        for (i in 0 until pages.size) {
            val page = pages[i]
            if (isHeaderPage(data, page)) {
                firstAudioIdx = i + 1
            }
        }

        // Check if granule positions need fixing:
        // If the first audio page has granulepos == 0 or less than preskip, they are buggy and must be recalculated.
        val needGranuleFix = if (firstAudioIdx < pages.size) {
            pages[firstAudioIdx].granulePos < preskip
        } else {
            false
        }

        var cumSamples = preskip.toLong()
        var runningToc = -1
        var runningFrameCountByte = 0
        var packetBytesRead = 0
        val lastPageIdx = pages.size - 1

        for (idx in firstAudioIdx until pages.size) {
            val page = pages[idx]
            var pageModified = false
            var completedPacketsInPage = 0
            var currBodyOffset = page.bodyOffset

            if (needGranuleFix) {
                for (s in 0 until page.nsegs) {
                    val segLen = data[page.offset + 27 + s].toInt() and 0xFF
                    if (segLen > 0) {
                        if (packetBytesRead == 0) {
                            runningToc = data[currBodyOffset].toInt() and 0xFF
                            packetBytesRead++
                            if ((runningToc and 0x03) == 3 && segLen > 1) {
                                runningFrameCountByte = data[currBodyOffset + 1].toInt() and 0x3F
                                packetBytesRead++
                            }
                        } else if (packetBytesRead == 1 && (runningToc and 0x03) == 3) {
                            runningFrameCountByte = data[currBodyOffset].toInt() and 0x3F
                            packetBytesRead++
                        }
                    }
                    currBodyOffset += segLen

                    if (segLen < 255) {
                        val samples = calculateOpusSamples(runningToc, runningFrameCountByte)
                        cumSamples += samples
                        completedPacketsInPage++
                        runningToc = -1
                        runningFrameCountByte = 0
                        packetBytesRead = 0
                    }
                }

                val newGranule = if (completedPacketsInPage > 0) cumSamples else -1L
                if (newGranule != page.granulePos) {
                    writeLittleEndianLong(data, page.offset + 6, newGranule)
                    pageModified = true
                }
            }

            // If last page, ensure EOS flag is set (bit 2 = 0x04)
            if (idx == lastPageIdx) {
                val currentFlags = data[page.offset + 5].toInt() and 0xFF
                if ((currentFlags and 0x04) == 0) {
                    data[page.offset + 5] = (currentFlags or 0x04).toByte()
                    pageModified = true
                }
            }

            if (pageModified) {
                data[page.offset + 22] = 0
                data[page.offset + 23] = 0
                data[page.offset + 24] = 0
                data[page.offset + 25] = 0
                val crc = computeOggCrc(data, page.offset, page.length)
                writeLittleEndianInt(data, page.offset + 22, crc)
            }
        }

        // If there were no audio pages, ensure the last header page has EOS
        if (firstAudioIdx >= pages.size && pages.isNotEmpty()) {
            val lastPage = pages.last()
            val currentFlags = data[lastPage.offset + 5].toInt() and 0xFF
            if ((currentFlags and 0x04) == 0) {
                data[lastPage.offset + 5] = (currentFlags or 0x04).toByte()
                data[lastPage.offset + 22] = 0
                data[lastPage.offset + 23] = 0
                data[lastPage.offset + 24] = 0
                data[lastPage.offset + 25] = 0
                val crc = computeOggCrc(data, lastPage.offset, lastPage.length)
                writeLittleEndianInt(data, lastPage.offset + 22, crc)
            }
        }

        return data
    }

    private fun isHeaderPage(data: ByteArray, page: PageInfo): Boolean {
        if (page.bodyLength < 8) return false
        val b = page.bodyOffset
        val isHead = data[b] == 'O'.code.toByte() && data[b+1] == 'p'.code.toByte() &&
                     data[b+2] == 'u'.code.toByte() && data[b+3] == 's'.code.toByte() &&
                     data[b+4] == 'H'.code.toByte() && data[b+5] == 'e'.code.toByte() &&
                     data[b+6] == 'a'.code.toByte() && data[b+7] == 'd'.code.toByte()

        val isTags = data[b] == 'O'.code.toByte() && data[b+1] == 'p'.code.toByte() &&
                     data[b+2] == 'u'.code.toByte() && data[b+3] == 's'.code.toByte() &&
                     data[b+4] == 'T'.code.toByte() && data[b+5] == 'a'.code.toByte() &&
                     data[b+6] == 'g'.code.toByte() && data[b+7] == 's'.code.toByte()

        return isHead || isTags
    }

    /**
     * Fixes an Ogg Opus file in place on the filesystem.
     * Returns true if the file was recognized as Ogg Opus and processed, false otherwise.
     */
    fun fixOggOpusFile(file: File): Boolean {
        if (!file.exists() || file.length() < 27) return false
        return try {
            val bytes = file.readBytes()
            if (!isOggOpus(bytes)) return false
            val fixed = fixOggOpusBytes(bytes)
            file.writeBytes(fixed)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readLittleEndianLong(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
               ((data[offset + 1].toLong() and 0xFF) shl 8) or
               ((data[offset + 2].toLong() and 0xFF) shl 16) or
               ((data[offset + 3].toLong() and 0xFF) shl 24) or
               ((data[offset + 4].toLong() and 0xFF) shl 32) or
               ((data[offset + 5].toLong() and 0xFF) shl 40) or
               ((data[offset + 6].toLong() and 0xFF) shl 48) or
               ((data[offset + 7].toLong() and 0xFF) shl 56)
    }

    private fun writeLittleEndianInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeLittleEndianLong(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 4] = ((value ushr 32) and 0xFF).toByte()
        data[offset + 5] = ((value ushr 40) and 0xFF).toByte()
        data[offset + 6] = ((value ushr 48) and 0xFF).toByte()
        data[offset + 7] = ((value ushr 56) and 0xFF).toByte()
    }
}
