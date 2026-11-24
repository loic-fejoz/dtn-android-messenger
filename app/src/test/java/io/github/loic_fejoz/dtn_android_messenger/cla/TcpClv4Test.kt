package io.github.loic_fejoz.dtn_android_messenger.cla

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.loic_fejoz.dtn_android_messenger.data.dao.SystemLogDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.SystemLog
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TcpClv4Test {

    private val fakePrefs = object : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = if (key == "local_node_name") "dtn://test-node" else defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = 10
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = true
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this
            override fun clear() = this
            override fun apply() {}
            override fun commit(): Boolean = true
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private val fakeContext = object : ContextWrapper(null) {}
    private val fakeLogDao = object : SystemLogDao {
        override suspend fun insert(log: SystemLog) {}
        override fun getAll(): Flow<List<SystemLog>> = emptyFlow()
        override fun getRecent(limit: Int): Flow<List<SystemLog>> = emptyFlow()
        override suspend fun clearAll() {}
        override suspend fun deleteLogsOlderThan(cutoff: Long) {}
        override suspend fun getOldestLogs(limit: Int): List<SystemLog> = emptyList()
    }

    @BeforeEach
    fun setUp() {
        PreferencesHelper.setSharedPreferencesForTesting(fakePrefs)
    }

    @AfterEach
    fun tearDown() {
        PreferencesHelper.setSharedPreferencesForTesting(null)
    }

    @Test
    fun testRfc9174SegmentFlagConstants() {
        assertEquals(0x01, XFER_FLAG_END)
        assertEquals(0x02, XFER_FLAG_START)
    }
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

    @Test
    fun testMultiSegmentReassemblyAndCumulativeAck() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val adapter = TcpClAdapter(context = fakeContext, port = port, logDao = fakeLogDao)

        try {
            adapter.start(scope) { bytes ->
                receivedBundles.add(bytes)
                latch.countDown()
                true
            }
            // Give server socket a moment to bind
            Thread.sleep(150)

            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val dos = DataOutputStream(socket.getOutputStream())
                val dis = DataInputStream(socket.getInputStream())

                // Contact header + SESS_INIT
                dos.write(byteArrayOf('d'.code.toByte(), 't'.code.toByte(), 'n'.code.toByte(), '!'.code.toByte(), 4, 0))
                dos.flush()
                val srvHeader = ByteArray(6)
                dis.readFully(srvHeader)

                val nodeBytes = "dtn://peer/".toByteArray(Charsets.UTF_8)
                dos.writeByte(7)
                dos.writeShort(30)
                dos.writeLong(10485760L)
                dos.writeLong(10485760L)
                dos.writeShort(nodeBytes.size)
                dos.write(nodeBytes)
                dos.writeInt(0)
                dos.flush()

                assertEquals(7, dis.readByte().toInt())
                dis.readShort()
                dis.readLong()
                dis.readLong()
                val srvNodeLen = dis.readUnsignedShort()
                dis.readFully(ByteArray(srvNodeLen))
                val srvExtLen = dis.readInt()
                if (srvExtLen > 0) dis.readFully(ByteArray(srvExtLen))

                val part1 = "Hello-".toByteArray(Charsets.UTF_8)
                val part2 = "Multi-".toByteArray(Charsets.UTF_8)
                val part3 = "Segment!".toByteArray(Charsets.UTF_8)
                val transferId = 77L

                // Segment 1: START (0x02) includes 4-byte extension length = 0
                dos.writeByte(1)
                dos.writeByte(XFER_FLAG_START)
                dos.writeLong(transferId)
                dos.writeInt(0)
                dos.writeLong(part1.size.toLong())
                dos.write(part1)
                dos.flush()

                assertEquals(2, dis.readByte().toInt())
                assertEquals(XFER_FLAG_START, dis.readByte().toInt() and 0xFF)
                assertEquals(transferId, dis.readLong())
                assertEquals(part1.size.toLong(), dis.readLong())

                // Segment 2: Middle (0x00) has NO extension length field
                dos.writeByte(1)
                dos.writeByte(0)
                dos.writeLong(transferId)
                dos.writeLong(part2.size.toLong())
                dos.write(part2)
                dos.flush()

                assertEquals(2, dis.readByte().toInt())
                assertEquals(0, dis.readByte().toInt() and 0xFF)
                assertEquals(transferId, dis.readLong())
                assertEquals((part1.size + part2.size).toLong(), dis.readLong())

                // Segment 3: END (0x01) has NO extension length field
                dos.writeByte(1)
                dos.writeByte(XFER_FLAG_END)
                dos.writeLong(transferId)
                dos.writeLong(part3.size.toLong())
                dos.write(part3)
                dos.flush()

                assertEquals(2, dis.readByte().toInt())
                assertEquals(XFER_FLAG_END, dis.readByte().toInt() and 0xFF)
                assertEquals(transferId, dis.readLong())
                assertEquals((part1.size + part2.size + part3.size).toLong(), dis.readLong())
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(1, receivedBundles.size)
            assertArrayEquals("Hello-Multi-Segment!".toByteArray(Charsets.UTF_8), receivedBundles[0])
        } finally {
            adapter.stop()
            scope.cancel()
        }
    }

    @Test
    fun testRejects64BitLengthTruncationBypass() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        var listenerCalled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val adapter = TcpClAdapter(context = fakeContext, port = port, logDao = fakeLogDao)

        try {
            adapter.start(scope) {
                listenerCalled = true
                true
            }
            Thread.sleep(150)

            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                val dos = DataOutputStream(socket.getOutputStream())
                val dis = DataInputStream(socket.getInputStream())

                dos.write(byteArrayOf('d'.code.toByte(), 't'.code.toByte(), 'n'.code.toByte(), '!'.code.toByte(), 4, 0))
                dos.flush()
                dis.readFully(ByteArray(6))

                val nodeBytes = "dtn://peer/".toByteArray(Charsets.UTF_8)
                dos.writeByte(7)
                dos.writeShort(30)
                dos.writeLong(10485760L)
                dos.writeLong(10485760L)
                dos.writeShort(nodeBytes.size)
                dos.write(nodeBytes)
                dos.writeInt(0)
                dos.flush()

                assertEquals(7, dis.readByte().toInt())
                dis.readShort()
                dis.readLong()
                dis.readLong()
                val srvNodeLen = dis.readUnsignedShort()
                dis.readFully(ByteArray(srvNodeLen))
                val srvExtLen = dis.readInt()
                if (srvExtLen > 0) dis.readFully(ByteArray(srvExtLen))

                // Send segment with 64-bit length 0x1_0000_0020L (truncates to 32 as Int)
                dos.writeByte(1)
                dos.writeByte(XFER_FLAG_START or XFER_FLAG_END)
                dos.writeLong(99L)
                dos.writeInt(0)
                dos.writeLong(0x1_0000_0020L)
                dos.flush()

                // Server must close connection without invoking listener
                val next = runCatching { dis.readByte().toInt() }.getOrDefault(-1)
                assertEquals(-1, next)
            }
            assertEquals(false, listenerCalled)
        } finally {
            adapter.stop()
            scope.cancel()
        }
    }
}
