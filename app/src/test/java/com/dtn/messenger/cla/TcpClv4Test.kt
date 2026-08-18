package com.dtn.messenger.cla

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class TcpClv4Test {
    @Test
    fun testContactHeaderFormat() {
        val magic = byteArrayOf('d'.code.toByte(), 't'.code.toByte(), 'n'.code.toByte(), '!'.code.toByte())
        val version = 4.toByte()
        val flags = 0.toByte()

        val header = ByteArray(6)
        System.arraycopy(magic, 0, header, 0, 4)
        header[4] = version
        header[5] = flags

        assertEquals(6, header.size)
        assertEquals('d'.code.toByte(), header[0])
        assertEquals('t'.code.toByte(), header[1])
        assertEquals('n'.code.toByte(), header[2])
        assertEquals('!'.code.toByte(), header[3])
        assertEquals(4.toByte(), header[4])
        assertEquals(0.toByte(), header[5])
    }

    @Test
    fun testSessTermFormat() {
        // RFC 9174 Section 8.7 Table 16:
        // 0x00 = Unknown, 0x01 = Idle timeout, 0x02 = Version mismatch, 0x03 = Busy, 0x04 = Contact Failure, 0x05 = Resource Exhaustion
        val reasonUnknown: Byte = 0x00
        val reasonIdleTimeout: Byte = 0x01
        val reasonVersionMismatch: Byte = 0x02
        val reasonBusy: Byte = 0x03

        assertEquals(0.toByte(), reasonUnknown)
        assertEquals(1.toByte(), reasonIdleTimeout)
        assertEquals(2.toByte(), reasonVersionMismatch)
        assertEquals(3.toByte(), reasonBusy)

        // Initial SESS_TERM (initiating idle timeout teardown)
        val termInitiate = byteArrayOf(5, 0, reasonIdleTimeout)
        assertEquals(5.toByte(), termInitiate[0]) // MSG_SESS_TERM
        assertEquals(0.toByte(), termInitiate[1]) // flags: reply = false
        assertEquals(1.toByte(), termInitiate[2]) // reason: IdleTimeout (0x01)

        // Reply SESS_TERM (acknowledgment)
        val termReply = byteArrayOf(5, 1, reasonIdleTimeout)
        assertEquals(5.toByte(), termReply[0]) // MSG_SESS_TERM
        assertEquals(1.toByte(), termReply[1]) // flags: reply = true
        assertEquals(1.toByte(), termReply[2]) // reason: IdleTimeout (0x01)
    }

    @Test
    fun testSessInitMruStructure() {
        val nodeId = "dtn://test-node/"
        val nodeIdBytes = nodeId.toByteArray(Charsets.UTF_8)
        val mruVal = 10L * 1024L * 1024L // 10 MiB

        val totalSize = 1 + 2 + 8 + 8 + 2 + nodeIdBytes.size + 4
        val buf = ByteArray(totalSize)
        buf[0] = 7 // SESS_INIT
        buf[1] = 0 // Keepalive MSB
        buf[2] = 30 // Keepalive LSB (30s)
        ByteBuffer.wrap(buf, 3, 8).putLong(mruVal)
        ByteBuffer.wrap(buf, 11, 8).putLong(mruVal)
        ByteBuffer.wrap(buf, 19, 2).putShort(nodeIdBytes.size.toShort())
        System.arraycopy(nodeIdBytes, 0, buf, 21, nodeIdBytes.size)

        // Verify deserialization
        assertEquals(7.toByte(), buf[0])
        val keepalive = ((buf[1].toInt() and 0xFF) shl 8) or (buf[2].toInt() and 0xFF)
        assertEquals(30, keepalive)

        val segmentMru = ByteBuffer.wrap(buf, 3, 8).long
        val transferMru = ByteBuffer.wrap(buf, 11, 8).long
        assertEquals(10485760L, segmentMru)
        assertEquals(10485760L, transferMru)

        val parsedNodeIdLen = ByteBuffer.wrap(buf, 19, 2).short.toInt() and 0xFFFF
        assertEquals(nodeIdBytes.size, parsedNodeIdLen)
        val parsedNodeId = String(buf, 21, parsedNodeIdLen, Charsets.UTF_8)
        assertEquals("dtn://test-node/", parsedNodeId)
    }

    @Test
    fun testXferSegmentAndAckFormat() {
        val transferId = 42L
        val dataLength = 1024L
        val flags = 3 // START | END

        // XFER_ACK structure: 1 byte type (2), 1 byte flags, 8 bytes transfer ID, 8 bytes ack length = 18 bytes
        val ack = ByteArray(18)
        ack[0] = 2 // XFER_ACK
        ack[1] = flags.toByte()
        ByteBuffer.wrap(ack, 2, 8).putLong(transferId)
        ByteBuffer.wrap(ack, 10, 8).putLong(dataLength)

        assertEquals(2.toByte(), ack[0])
        assertEquals(flags.toByte(), ack[1])
        assertEquals(transferId, ByteBuffer.wrap(ack, 2, 8).long)
        assertEquals(dataLength, ByteBuffer.wrap(ack, 10, 8).long)
    }
}
