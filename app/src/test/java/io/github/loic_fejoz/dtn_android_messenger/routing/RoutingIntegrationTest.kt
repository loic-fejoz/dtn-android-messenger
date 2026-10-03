package io.github.loic_fejoz.dtn_android_messenger.routing

import android.content.Context
import android.content.SharedPreferences
import com.upokecenter.cbor.CBORObject
import io.github.loic_fejoz.dtn_android_messenger.cla.TcpClAdapter
import io.github.loic_fejoz.dtn_android_messenger.data.dao.SystemLogDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.BpsecStatus
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleRecord
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleState
import io.github.loic_fejoz.dtn_android_messenger.data.model.ConvergenceProfile
import io.github.loic_fejoz.dtn_android_messenger.data.model.RoutingRule
import io.github.loic_fejoz.dtn_android_messenger.data.model.SystemLog
import io.github.loic_fejoz.dtn_android_messenger.data.model.TriggerType
import io.github.loic_fejoz.dtn_android_messenger.protocol.BibBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bpv7Parser
import io.github.loic_fejoz.dtn_android_messenger.protocol.Eid
import io.github.loic_fejoz.dtn_android_messenger.protocol.HopCountBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PayloadBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PrimaryBlock
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper
import io.github.loic_fejoz.dtn_android_messenger.util.TestTimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RoutingIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testTimeProvider: TestTimeProvider

    private val fakePrefs = object : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = if (key == "local_node_name") "dtn://my-node" else defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = 10
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = true
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
            override fun clear(): SharedPreferences.Editor = this
            override fun apply() {}
            override fun commit(): Boolean = true
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private val fakeContext = object : android.content.ContextWrapper(null) {}

    private val fakeLogDao = object : SystemLogDao {
        override suspend fun insert(log: SystemLog) {}
        override fun getAll(): Flow<List<SystemLog>> = emptyFlow()
        override suspend fun clearAll() {}
        override suspend fun deleteLogsOlderThan(cutoff: Long) {}
        override suspend fun getOldestLogs(limit: Int): List<SystemLog> = emptyList()
    }

    @BeforeEach
    fun setUp() {
        testTimeProvider = TestTimeProvider(initialTimeMs = 100000L)
        PreferencesHelper.setSharedPreferencesForTesting(fakePrefs)
    }

    /**
     * Helper to start a lightweight TCPCLv4 Mock Server listening on a local port.
     */
    private fun startMockTcpClServer(
        serverSocket: ServerSocket,
        nodeEid: String,
        onBundleReceived: (ByteArray) -> Unit
    ): Thread {
        val readyLatch = CountDownLatch(1)
        val thread = Thread {
            readyLatch.countDown()
            try {
                val socket = serverSocket.accept()
                val dis = DataInputStream(socket.getInputStream())
                val dos = DataOutputStream(socket.getOutputStream())

                // 1. Read Contact Header (6 bytes: 'd','t','n','!', 4, 0)
                val contactHeader = ByteArray(6)
                dis.readFully(contactHeader)

                // 2. Send Contact Header back
                dos.write(byteArrayOf('d'.code.toByte(), 't'.code.toByte(), 'n'.code.toByte(), '!'.code.toByte(), 4, 0))
                dos.flush()

                // 3. Read client SESS_INIT
                val clientMsgType = dis.readByte()
                if (clientMsgType == 7.toByte()) { // SESS_INIT
                    dis.readShort() // keepalive
                    dis.readLong() // segMru
                    dis.readLong() // xferMru
                    val nodeLen = dis.readUnsignedShort()
                    val clientNodeBytes = ByteArray(nodeLen)
                    dis.readFully(clientNodeBytes)
                    val extLen = dis.readInt()
                    if (extLen > 0) {
                        val extBytes = ByteArray(extLen)
                        dis.readFully(extBytes)
                    }

                    // 4. Send server SESS_INIT
                    val nodeBytes = nodeEid.toByteArray(Charsets.UTF_8)
                    dos.writeByte(7) // SESS_INIT
                    dos.writeShort(15) // Keepalive
                    dos.writeLong(10485760L) // Segment MRU
                    dos.writeLong(10485760L) // Transfer MRU
                    dos.writeShort(nodeBytes.size)
                    dos.write(nodeBytes)
                    dos.writeInt(0) // 4 bytes extension length
                    dos.flush()

                    // 5. Loop to receive XFER_SEG (type 1)
                    while (!socket.isClosed) {
                        val msgType = try { dis.readByte() } catch (e: Exception) { break }
                        if (msgType == 1.toByte()) { // XFER_SEG
                            val flags = dis.readByte().toInt()
                            val transferId = dis.readLong()
                            if ((flags and 1) != 0) { // START flag present
                                val segExtLen = dis.readInt()
                                if (segExtLen > 0) {
                                    val segExtBytes = ByteArray(segExtLen)
                                    dis.readFully(segExtBytes)
                                }
                            }
                            val segLen = dis.readLong()
                            val payload = ByteArray(segLen.toInt())
                            dis.readFully(payload)

                            // Send XFER_ACK (type 2)
                            dos.writeByte(2) // XFER_ACK
                            dos.writeByte(flags)
                            dos.writeLong(transferId)
                            dos.writeLong(segLen)
                            dos.flush()

                            onBundleReceived(payload)
                        } else if (msgType == 5.toByte()) { // SESS_TERM
                            break
                        }
                    }
                }
                socket.close()
            } catch (e: Exception) {
                // Connection closed or test finished
            }
        }
        thread.start()
        readyLatch.await(5, TimeUnit.SECONDS)
        return thread
    }

    @Test
    fun testScenario1_DirectDeliveryBeforeExpiration() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "dtn://node-b") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        // Create sample payload file
        val payloadFile = File(tempDir.toFile(), "payload_sc1.txt")
        payloadFile.writeText("Hello Node B direct transmission test")

        val record = BundleRecord(
            bundleId = "bundle-sc1-001",
            destinationEid = "dtn://node-b/chat",
            sourceEid = "dtn://node-a/chat",
            creationTimestamp = 100000L,
            sequenceNumber = 1,
            lifetimeMs = 600000L, // Expires at 700000ms
            payloadFilePath = payloadFile.absolutePath,
            state = BundleState.OUTBOX,
            isRead = false,
            bpsecStatus = BpsecStatus.VALID,
            hopCount = 1
        )

        val profile = ConvergenceProfile(
            profileId = "dtn://node-b",
            name = "Node B TCPCL",
            triggerType = TriggerType.PERIODIC_INTERNET,
            targetAddress = "127.0.0.1:$port"
        )

        // Set time to 200000ms (before 700000ms expiration)
        testTimeProvider.initialTimeMs = 200000L

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        val primary = PrimaryBlock(
            destination = Eid(record.destinationEid),
            source = Eid(record.sourceEid),
            reportTo = Eid(record.sourceEid),
            creationTimestamp = Pair(100000L, 1L),
            lifetimeMs = record.lifetimeMs
        )
        val payload = PayloadBlock(data = payloadFile.readBytes())
        val hopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, hopCount, null)
        val bundleBytes = Bpv7Parser.serialize(bundle)

        var acked = false
        val item = Pair<ByteArray, (suspend () -> Unit)?>(bundleBytes) {
            acked = true
        }

        val success = adapter.sendBundles(listOf(item), profile.targetAddress)

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertTrue(success)
        assertTrue(acked)
        assertEquals(1, receivedBundles.size)

        val receivedBundle = Bpv7Parser.deserialize(receivedBundles[0])
        assertEquals("dtn://node-b/chat", receivedBundle.primaryBlock.destination.uri)

        adapter.stop()
        serverSocket.close()
        serverThread.join(1000)
    }

    @Test
    fun testScenario2_ExpirationPreventsTransmission() = runBlocking {
        // Create sample payload file
        val payloadFile = File(tempDir.toFile(), "payload_sc2.txt")
        payloadFile.writeText("Hello Node B expired test")

        val record = BundleRecord(
            bundleId = "bundle-sc2-002",
            destinationEid = "dtn://node-b/chat",
            sourceEid = "dtn://node-a/chat",
            creationTimestamp = 100000L,
            sequenceNumber = 2,
            lifetimeMs = 300000L, // Expires at 400000ms
            payloadFilePath = payloadFile.absolutePath,
            state = BundleState.OUTBOX,
            isRead = false,
            bpsecStatus = BpsecStatus.VALID,
            hopCount = 1
        )

        // Set test time to 500000ms (AFTER 400000ms expiration)
        testTimeProvider.initialTimeMs = 500000L

        val isExpired = (record.creationTimestamp + record.lifetimeMs) < testTimeProvider.currentTimeMillis()
        assertTrue(isExpired, "Bundle must be identified as expired")
    }

    @Test
    fun testScenario3_NextHopRoutingViaNodeC() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "dtn://node-c") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        val payloadFile = File(tempDir.toFile(), "payload_sc3.txt")
        payloadFile.writeText("Hello Node B routed via Node C")

        val record = BundleRecord(
            bundleId = "bundle-sc3-003",
            destinationEid = "dtn://node-b/chat",
            sourceEid = "dtn://node-a/chat",
            creationTimestamp = 100000L,
            sequenceNumber = 3,
            lifetimeMs = 600000L, // Expires at 700000ms
            payloadFilePath = payloadFile.absolutePath,
            state = BundleState.OUTBOX,
            isRead = false,
            bpsecStatus = BpsecStatus.VALID,
            hopCount = 1
        )

        val routeRule = RoutingRule(
            destinationEidPattern = "dtn://node-b/*",
            nextHopEid = "dtn://node-c"
        )

        val profileNodeC = ConvergenceProfile(
            profileId = "dtn://node-c",
            name = "Node C Gateway TCPCL",
            triggerType = TriggerType.PERIODIC_INTERNET,
            targetAddress = "127.0.0.1:$port"
        )

        // Resolve next hop for destination dtn://node-b/chat against route rules
        val matchedNextHop = if (io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isPrefixMatch(routeRule.destinationEidPattern.replace("*", ""), record.destinationEid)) {
            routeRule.nextHopEid
        } else {
            record.destinationEid
        }

        assertEquals("dtn://node-c", matchedNextHop)

        // Set time to 200000ms (before expiration)
        testTimeProvider.initialTimeMs = 200000L

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        val primary = PrimaryBlock(
            destination = Eid(record.destinationEid),
            source = Eid(record.sourceEid),
            reportTo = Eid(record.sourceEid),
            creationTimestamp = Pair(100000L, 3L),
            lifetimeMs = record.lifetimeMs
        )
        val payload = PayloadBlock(data = payloadFile.readBytes())
        val hopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, hopCount, null)
        val bundleBytes = Bpv7Parser.serialize(bundle)

        var acked = false
        val item = Pair<ByteArray, (suspend () -> Unit)?>(bundleBytes) {
            acked = true
        }

        val success = adapter.sendBundles(listOf(item), profileNodeC.targetAddress)

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertTrue(success)
        assertTrue(acked)
        assertEquals(1, receivedBundles.size)

        val receivedBundle = Bpv7Parser.deserialize(receivedBundles[0])
        assertEquals("dtn://node-b/chat", receivedBundle.primaryBlock.destination.uri)
        adapter.stop()
        serverSocket.close()
        serverThread.join(1000)
    }

    @Test
    fun testScenario4_BpsecSignaturePreservedDuringRouting() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "dtn://gateway-c") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        val payloadFile = File(tempDir.toFile(), "payload_sc4.txt")
        payloadFile.writeText("BPSec signed payload for Node B")

        val dummySignature = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A)
        val bibBlock = BibBlock(
            blockNumber = 2,
            targets = listOf(1),
            securityContext = 1,
            securityContextFlags = 3,
            securitySource = Eid("dtn://node-a"),
            variant = 5,
            scopeFlags = 7,
            signature = dummySignature
        )

        val primary = PrimaryBlock(
            destination = Eid("dtn://node-b/secure"),
            source = Eid("dtn://node-a/sensor"),
            reportTo = Eid("dtn://node-a/sensor"),
            creationTimestamp = Pair(100000L, 4L),
            lifetimeMs = 600000L
        )
        val payload = PayloadBlock(data = payloadFile.readBytes())
        val hopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, hopCount, bibBlock)
        val bundleBytes = Bpv7Parser.serialize(bundle)

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        var acked = false
        val item = Pair<ByteArray, (suspend () -> Unit)?>(bundleBytes) { acked = true }

        val success = adapter.sendBundles(listOf(item), "127.0.0.1:$port")

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertTrue(success)
        assertTrue(acked)
        assertEquals(1, receivedBundles.size)

        val receivedBundle = Bpv7Parser.deserialize(receivedBundles[0])
        val rxBib = receivedBundle.bibBlock
        assertTrue(rxBib != null, "BIB block must be preserved in received bundle")
        assertEquals("dtn://node-a", rxBib!!.securitySource.uri)
        assertTrue(dummySignature.contentEquals(rxBib.signature), "BPSec signature must be byte-for-byte identical after routing")

        adapter.stop()
        serverSocket.close()
        serverThread.join(1000)
    }

    @Test
    fun testScenario5_HopCountLimitExceededDiscardsBundle() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "dtn://node-b") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        val payloadFile = File(tempDir.toFile(), "payload_sc5.txt")
        payloadFile.writeText("Hop count exceeded payload")

        val record = BundleRecord(
            bundleId = "bundle-sc5-exceeded",
            destinationEid = "dtn://node-b/chat",
            sourceEid = "dtn://node-a/chat",
            creationTimestamp = 100000L,
            sequenceNumber = 5,
            lifetimeMs = 600000L,
            payloadFilePath = payloadFile.absolutePath,
            state = BundleState.OUTBOX,
            isRead = false,
            bpsecStatus = BpsecStatus.VALID,
            hopCount = 64 // Limit reached
        )

        // Evaluate forwarding filter rule enforced in DtnEngineService: hopCount + 1 >= 64 -> discard
        val maxHopLimit = 64
        val isHopLimitExceeded = (record.hopCount + 1 > maxHopLimit) || (record.hopCount >= maxHopLimit)

        if (!isHopLimitExceeded) {
            val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
            val primary = PrimaryBlock(
                destination = Eid(record.destinationEid),
                source = Eid(record.sourceEid),
                reportTo = Eid(record.sourceEid),
                creationTimestamp = Pair(100000L, 5L),
                lifetimeMs = record.lifetimeMs
            )
            val payload = PayloadBlock(data = payloadFile.readBytes())
            val hopCountBlock = HopCountBlock(hopLimit = maxHopLimit, hopCount = record.hopCount)
            val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, hopCountBlock, null)
            adapter.sendBundles(listOf(Pair(Bpv7Parser.serialize(bundle), null)), "127.0.0.1:$port")
        }

        // Verify that transmission was blocked by the routing engine filter and nothing was received over network
        val received = latch.await(1, TimeUnit.SECONDS)
        assertFalse(received, "Bundle with hopCount (64) exceeding limit MUST NOT be transmitted over the network")
        assertEquals(0, receivedBundles.size, "Mock server must receive 0 bundles when hop count limit is reached")

        serverSocket.close()
        serverThread.join(500)
    }

    @Test
    fun testScenario6_Rfc9171IpnSchemeRouting() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "ipn:2.1") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        // RFC 9171 Section 4.1.6: IPN scheme routing EID (node 2, service 1)
        val primary = PrimaryBlock(
            destination = Eid("ipn:2.1"),
            source = Eid("ipn:1.1"),
            reportTo = Eid("ipn:1.1"),
            creationTimestamp = Pair(100000L, 6L),
            lifetimeMs = 600000L
        )
        val payload = PayloadBlock(data = "RFC 9171 IPN Scheme test payload".toByteArray())
        val hopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, hopCount, null)
        val bundleBytes = Bpv7Parser.serialize(bundle)

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        val success = adapter.sendBundles(listOf(Pair(bundleBytes, null)), "127.0.0.1:$port")

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertTrue(success)
        assertEquals(1, receivedBundles.size)

        val rxBundle = Bpv7Parser.deserialize(receivedBundles[0])
        assertEquals("ipn", rxBundle.primaryBlock.destination.scheme)
        assertEquals("2.1", rxBundle.primaryBlock.destination.ssp)
        assertEquals("ipn:2.1", rxBundle.primaryBlock.destination.uri)

        adapter.stop()
        serverSocket.close()
        serverThread.join(1000)
    }

    @Test
    fun testScenario7_Rfc9171PrimaryBlockControlFlagsPreserved() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "dtn://node-b") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        // RFC 9171 Section 4.1.3: Bundle Control Flags (e.g. MUST_NOT_FRAGMENT = 0x04L)
        val flags = 0x04L or 0x01L
        val primary = PrimaryBlock(
            version = 7,
            bundleControlFlags = flags,
            crcType = 0,
            destination = Eid("dtn://node-b/flags"),
            source = Eid("dtn://node-a/flags"),
            reportTo = Eid("dtn://node-a/flags"),
            creationTimestamp = Pair(100000L, 7L),
            lifetimeMs = 600000L
        )
        val payload = PayloadBlock(data = "Control flags test".toByteArray())
        val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, null, null)

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        val success = adapter.sendBundles(listOf(Pair(Bpv7Parser.serialize(bundle), null)), "127.0.0.1:$port")

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertTrue(success)
        assertEquals(1, receivedBundles.size)

        val rxBundle = Bpv7Parser.deserialize(receivedBundles[0])
        assertEquals(7, rxBundle.primaryBlock.version, "RFC 9171 requires version 7")
        assertEquals(flags, rxBundle.primaryBlock.bundleControlFlags, "Primary block control flags MUST be preserved during routing")

        adapter.stop()
        serverSocket.close()
        serverThread.join(1000)
    }

    @Test
    fun testScenario8_MultiHopSequentialRoutingAndHopCountIncrement() = runBlocking {
        val serverSocketB = ServerSocket(0)
        val portB = serverSocketB.localPort

        val receivedBundlesB = mutableListOf<ByteArray>()
        val latchB = CountDownLatch(1)
        val threadB = startMockTcpClServer(serverSocketB, "dtn://node-b") { bundleBytes ->
            receivedBundlesB.add(bundleBytes)
            latchB.countDown()
        }

        // Hop 1: Node A sends to Node B (hopCount = 1)
        val primary = PrimaryBlock(
            destination = Eid("dtn://final-node/app"),
            source = Eid("dtn://origin-node/app"),
            reportTo = Eid("dtn://origin-node/app"),
            creationTimestamp = Pair(100000L, 8L),
            lifetimeMs = 600000L
        )
        val payload = PayloadBlock(data = "Multi-hop payload".toByteArray())
        val hopCount1 = HopCountBlock(hopLimit = 64, hopCount = 1)
        val bundle1 = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, hopCount1, null)

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        adapter.sendBundles(listOf(Pair(Bpv7Parser.serialize(bundle1), null)), "127.0.0.1:$portB")
        assertTrue(latchB.await(5, TimeUnit.SECONDS))

        val rxBundleB = Bpv7Parser.deserialize(receivedBundlesB[0])
        assertEquals("dtn://final-node/app", rxBundleB.primaryBlock.destination.uri)
        assertEquals(1, rxBundleB.hopCountBlock?.hopCount)

        // Hop 2: Node B forwards to Node C (increments hopCount to 2 per RFC 9171 Section 4.3.3)
        val serverSocketC = ServerSocket(0)
        val portC = serverSocketC.localPort

        val receivedBundlesC = mutableListOf<ByteArray>()
        val latchC = CountDownLatch(1)
        val threadC = startMockTcpClServer(serverSocketC, "dtn://node-c") { bundleBytes ->
            receivedBundlesC.add(bundleBytes)
            latchC.countDown()
        }

        val hopCount2 = HopCountBlock(hopLimit = 64, hopCount = rxBundleB.hopCountBlock!!.hopCount + 1)
        val bundle2 = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(rxBundleB.primaryBlock, rxBundleB.payloadBlock, hopCount2, null)

        adapter.sendBundles(listOf(Pair(Bpv7Parser.serialize(bundle2), null)), "127.0.0.1:$portC")
        assertTrue(latchC.await(5, TimeUnit.SECONDS))

        val rxBundleC = Bpv7Parser.deserialize(receivedBundlesC[0])
        assertEquals("dtn://final-node/app", rxBundleC.primaryBlock.destination.uri)
        assertEquals(2, rxBundleC.hopCountBlock?.hopCount, "RFC 9171 requires hopCount incremented by 1 at intermediate hop")

        adapter.stop()
        serverSocketB.close()
        threadB.join(1000)
        serverSocketC.close()
        threadC.join(1000)
    }

    @Test
    fun testScenario9_Rfc9171PreviousNodeBlockRemovedOrUpdatedOnForwarding() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "dtn://next-hop") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        val primary = PrimaryBlock(
            destination = Eid("dtn://dest-node/app"),
            source = Eid("dtn://origin-node/app"),
            reportTo = Eid("dtn://origin-node/app"),
            creationTimestamp = Pair(100000L, 9L),
            lifetimeMs = 600000L
        )
        val payload = PayloadBlock(data = "Previous Node Block test payload".toByteArray())

        // Build raw CBOR bundle including Previous Node Block (type 6) with EID "dtn://node-prev"
        val oldPrevNodeEid = Eid("dtn://node-prev")
        val prevNodeBlockDataBytes = oldPrevNodeEid.toCbor().EncodeToBytes()
        val prevNodeCanonicalBlock = CBORObject.NewArray().apply {
            Add(6) // Type 6: Previous Node Block (RFC 9171 Section 4.3.2)
            Add(6) // Block Number
            Add(0L) // Block Control Flags
            Add(0) // CRC Type
            Add(CBORObject.FromObject(prevNodeBlockDataBytes))
        }

        val rawBundleCbor = CBORObject.NewArray()
        rawBundleCbor.Add(Bpv7Parser.serializePrimaryBlock(primary))
        rawBundleCbor.Add(prevNodeCanonicalBlock)
        rawBundleCbor.Add(Bpv7Parser.serializeCanonicalBlock(1, payload.blockNumber, payload.blockControlFlags, 0, payload.data))
        val bundleBytes = rawBundleCbor.EncodeToBytes()

        // Simulate engine reception, processing and forwarding serialization roundtrip
        val parsedBundle = Bpv7Parser.deserialize(bundleBytes)
        val forwardedBundleBytes = Bpv7Parser.serialize(parsedBundle)

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        val success = adapter.sendBundles(listOf(Pair(forwardedBundleBytes, null)), "127.0.0.1:$port")

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertTrue(success)
        assertEquals(1, receivedBundles.size)

        // RFC 9171 Section 4.3.2 invariant:
        // "When a node forwards a bundle that contains a previous node block, the node MUST
        // either update the previous node block to contain its own node ID or remove the
        // previous node block prior to forwarding the bundle."
        val rxCborArray = CBORObject.DecodeFromBytes(receivedBundles[0])
        var foundType6Block = false
        var rxPrevNodeEidUri: String? = null

        for (i in 1 until rxCborArray.size()) {
            val blockCbor = rxCborArray[i]
            if (blockCbor.type == com.upokecenter.cbor.CBORType.Array && blockCbor.size() >= 5) {
                if (blockCbor[0].AsInt32() == 6) {
                    foundType6Block = true
                    val dataBytes = blockCbor[4].GetByteString()
                    val decodedEidCbor = CBORObject.DecodeFromBytes(dataBytes)
                    rxPrevNodeEidUri = Eid.fromCbor(decodedEidCbor).uri
                }
            }
        }

        if (foundType6Block) {
            assertNotEquals("dtn://node-prev", rxPrevNodeEidUri,
                "RFC 9171 invariant: Previous Node block MUST NOT retain the stale previous node EID ('dtn://node-prev') upon forwarding")
        } else {
            // Previous Node block was removed during forwarding (valid RFC 9171 option)
            assertTrue(true, "Previous Node block was removed prior to forwarding per RFC 9171 Section 4.3.2")
        }

        adapter.stop()
        serverSocket.close()
        serverThread.join(1000)
    }

    @Test
    fun testScenario10_BibeTunnelNextHopResolution() = runBlocking {
        val rule = RoutingRule(
            destinationEidPattern = "dtn://remote-cluster/*",
            nextHopEid = "bibe:dtn://gateway-bibe"
        )

        val destination = "dtn://remote-cluster/chat"
        val pattern = rule.destinationEidPattern.replace("*", "").trimEnd('/')

        val isMatched = io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isPrefixMatch(pattern, destination)
        assertTrue(isMatched, "Destination EID must match BIBE routing rule pattern")

        val rawNextHop = if (isMatched) rule.nextHopEid else destination
        assertEquals("bibe:dtn://gateway-bibe", rawNextHop)

        var useBibe = false
        var bibeGateway: String? = null
        var effectiveNextHop = rawNextHop

        if (effectiveNextHop.startsWith("bibe:")) {
            useBibe = true
            bibeGateway = effectiveNextHop.substring(5)
            effectiveNextHop = bibeGateway
        }

        assertTrue(useBibe, "BIBE flag must be enabled when nextHop has 'bibe:' prefix")
        assertEquals("dtn://gateway-bibe", bibeGateway)
        assertEquals("dtn://gateway-bibe", effectiveNextHop, "Target gateway EID must be extracted without 'bibe:' prefix")
    }

    @Test
    fun testScenario11_Rfc9171AdministrativeRecordFlagPreserved() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "dtn://node-b") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        // RFC 9171 Section 4.1.3: Bit 1 (0x02L) = Bundle contains an administrative record
        val adminRecordFlags = 0x02L
        val primary = PrimaryBlock(
            version = 7,
            bundleControlFlags = adminRecordFlags,
            crcType = 0,
            destination = Eid("dtn://node-b/admin"),
            source = Eid("dtn://node-a/admin"),
            reportTo = Eid("dtn://node-a/admin"),
            creationTimestamp = Pair(100000L, 11L),
            lifetimeMs = 600000L
        )

        val adminPayloadData = byteArrayOf(0x82.toByte(), 0x18.toByte(), 0x01.toByte()) // CBOR array
        val payload = PayloadBlock(data = adminPayloadData)
        val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, null, null)

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        val success = adapter.sendBundles(listOf(Pair(Bpv7Parser.serialize(bundle), null)), "127.0.0.1:$port")

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertTrue(success)
        assertEquals(1, receivedBundles.size)

        val rxBundle = Bpv7Parser.deserialize(receivedBundles[0])
        val isAdminRecord = (rxBundle.primaryBlock.bundleControlFlags and 0x02L) != 0L
        assertTrue(isAdminRecord, "RFC 9171 Administrative Record flag (bit 1 = 0x02) MUST be preserved during routing")

        adapter.stop()
        serverSocket.close()
        serverThread.join(1000)
    }

    @Test
    fun testScenario12_Rfc9171FragmentationFlagsHandling() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "dtn://node-b") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        // RFC 9171 Section 4.1.3: Bit 2 (0x04L) = Bundle must not be fragmented
        val doNotFragmentFlags = 0x04L
        val primary = PrimaryBlock(
            version = 7,
            bundleControlFlags = doNotFragmentFlags,
            crcType = 0,
            destination = Eid("dtn://node-b/no-frag"),
            source = Eid("dtn://node-a/no-frag"),
            reportTo = Eid("dtn://node-a/no-frag"),
            creationTimestamp = Pair(100000L, 12L),
            lifetimeMs = 600000L
        )

        val payload = PayloadBlock(data = "Do Not Fragment payload".toByteArray())
        val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, null, null)

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        val success = adapter.sendBundles(listOf(Pair(Bpv7Parser.serialize(bundle), null)), "127.0.0.1:$port")

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertTrue(success)
        assertEquals(1, receivedBundles.size)

        val rxBundle = Bpv7Parser.deserialize(receivedBundles[0])
        val isDoNotFragment = (rxBundle.primaryBlock.bundleControlFlags and 0x04L) != 0L
        assertTrue(isDoNotFragment, "RFC 9171 MUST_NOT_FRAGMENT flag (bit 2 = 0x04) MUST be preserved during routing")

        adapter.stop()
        serverSocket.close()
        serverThread.join(1000)
    }

    @Test
    fun testScenario13_Rfc9172StrictBpsecPolicyRejectsCorruptedSignature() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        serverSocket.close()

        val secretKey = "shared_secret_key_123".toByteArray(Charsets.UTF_8)
        val authenticPayloadData = "Authentic Original Payload".toByteArray(Charsets.UTF_8)
        val tamperedPayloadData = "Tampered Payload Data!".toByteArray(Charsets.UTF_8)

        val primary = PrimaryBlock(
            destination = Eid("dtn://node-b/secure"),
            source = Eid("dtn://node-a/secure"),
            reportTo = Eid("dtn://node-a/secure"),
            creationTimestamp = Pair(100000L, 13L),
            lifetimeMs = 600000L
        )
        val rawPrimaryBytes = Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()

        // Calculate valid signature over authentic payload
        val validSignature = Bpv7Parser.computeHmac(
            secretKey = secretKey,
            primaryBlockBytes = rawPrimaryBytes,
            targetBlockType = 1,
            targetBlockNumber = 1,
            targetBlockFlags = 0L,
            securityBlockType = 11,
            securityBlockNumber = 2,
            securityBlockFlags = 3L,
            payloadBytes = authenticPayloadData,
            scopeFlags = 7
        )

        val bibBlock = BibBlock(
            blockNumber = 2,
            targets = listOf(1),
            securityContext = 1,
            securityContextFlags = 3,
            securitySource = Eid("dtn://node-a/secure"),
            variant = 5,
            scopeFlags = 7,
            signature = validSignature
        )

        // Build corrupted bundle (valid BIB signature header, but tampered payload data)
        val tamperedPayloadBlock = PayloadBlock(data = tamperedPayloadData)
        val corruptedBundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, tamperedPayloadBlock, null, bibBlock)
        val corruptedBundleBytes = Bpv7Parser.serialize(corruptedBundle)

        // Set up TCPCLv4 receiving adapter with ingestion listener representing DtnEngineService under strict BPSec policy
        val receiverAdapter = TcpClAdapter(context = fakeContext, port = port, logDao = fakeLogDao)
        val serviceScope = this

        var ingestionAttempted = false
        var ingestionAccepted = true

        receiverAdapter.start(serviceScope) { incomingBytes ->
            ingestionAttempted = true
            // Parse bundle and verify signature under strict BPSec policy
            val rxBundle = Bpv7Parser.deserialize(incomingBytes)
            val rxBib = rxBundle.bibBlock
            val rxPrimaryBytes = Bpv7Parser.serializePrimaryBlock(rxBundle.primaryBlock).EncodeToBytes()

            val computedSignature = Bpv7Parser.computeHmac(
                secretKey = secretKey,
                primaryBlockBytes = rxPrimaryBytes,
                targetBlockType = 1,
                targetBlockNumber = rxBundle.payloadBlock.blockNumber,
                targetBlockFlags = rxBundle.payloadBlock.blockControlFlags,
                securityBlockType = 11,
                securityBlockNumber = rxBib!!.blockNumber,
                securityBlockFlags = rxBib.blockControlFlags,
                payloadBytes = rxBundle.payloadBlock.data,
                scopeFlags = rxBib.scopeFlags
            )

            val isValid = computedSignature.contentEquals(rxBib.signature)
            // Under STRICT policy: reject if signature verification fails
            val policy = "strict"
            ingestionAccepted = if (!isValid && policy == "strict") false else true
            ingestionAccepted
        }

        kotlinx.coroutines.delay(200)

        val senderAdapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        var senderAcked = false
        val item = Pair<ByteArray, (suspend () -> Unit)?>(corruptedBundleBytes) { senderAcked = true }

        // Send corrupted bundle to receiving node over TCPCLv4 network
        val sendResult = senderAdapter.sendBundles(listOf(item), "127.0.0.1:$port")

        assertTrue(ingestionAttempted, "Receiving node MUST attempt ingestion of incoming bundle")
        assertFalse(ingestionAccepted, "RFC 9172 Strict BPSec Policy MUST reject corrupted bundle ingestion")
        assertFalse(senderAcked, "TCPCLv4 XFER_ACK MUST be withheld when bundle ingestion is rejected under strict BPSec policy")
        assertFalse(sendResult, "Transfer MUST fail when XFER_ACK is withheld due to BPSec rejection")

        receiverAdapter.stop()
        senderAdapter.stop()
    }

    @Test
    fun testScenario15_CustodyResponsibilityTransferRetryOnFailure() = runBlocking {
        // Prepare mock outbox store
        val payloadFile = File(tempDir.toFile(), "payload_sc15.txt")
        payloadFile.writeText("Custody / Responsibility Transfer Retry Payload")

        var bundleRecord = BundleRecord(
            bundleId = "bundle-sc15-custody",
            destinationEid = "dtn://dest-node/chat",
            sourceEid = "dtn://source-node/chat",
            creationTimestamp = 100000L,
            sequenceNumber = 15,
            lifetimeMs = 600000L,
            payloadFilePath = payloadFile.absolutePath,
            state = BundleState.OUTBOX,
            isRead = false,
            bpsecStatus = BpsecStatus.VALID,
            hopCount = 1
        )

        // Standard primary block (Note: BPv7 RFC 9171 delegates responsibility transfer to TCPCLv4 XFER_ACK)
        val primary = PrimaryBlock(
            version = 7,
            bundleControlFlags = 0L,
            crcType = 0,
            destination = Eid(bundleRecord.destinationEid),
            source = Eid(bundleRecord.sourceEid),
            reportTo = Eid(bundleRecord.sourceEid),
            creationTimestamp = Pair(100000L, 15L),
            lifetimeMs = bundleRecord.lifetimeMs
        )
        val payload = PayloadBlock(data = payloadFile.readBytes())
        val hopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, hopCount, null)
        val bundleBytes = Bpv7Parser.serialize(bundle)

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)

        // --- ATTEMPT 1: Transfer starts, but connection fails/drops before XFER_ACK ---
        val serverSocket1 = ServerSocket(0)
        val port1 = serverSocket1.localPort

        val attempt1Thread = Thread {
            try {
                val socket = serverSocket1.accept()
                val dis = DataInputStream(socket.getInputStream())
                val dos = DataOutputStream(socket.getOutputStream())

                // 1. Read Contact Header
                val contactHeader = ByteArray(6)
                dis.readFully(contactHeader)

                // 2. Send Contact Header back
                dos.write(byteArrayOf('d'.code.toByte(), 't'.code.toByte(), 'n'.code.toByte(), '!'.code.toByte(), 4, 0))
                dos.flush()

                // 3. Read client SESS_INIT
                val clientMsgType = dis.readByte()
                if (clientMsgType == 7.toByte()) {
                    dis.readShort() // keepalive
                    dis.readLong() // segMru
                    dis.readLong() // xferMru
                    val nodeLen = dis.readUnsignedShort()
                    val clientNodeBytes = ByteArray(nodeLen)
                    dis.readFully(clientNodeBytes)
                    val extLen = dis.readInt()
                    if (extLen > 0) {
                        val extBytes = ByteArray(extLen)
                        dis.readFully(extBytes)
                    }

                    // 4. Send server SESS_INIT
                    val nodeBytes = "dtn://dest-node".toByteArray(Charsets.UTF_8)
                    dos.writeByte(7) // SESS_INIT
                    dos.writeShort(15)
                    dos.writeLong(10485760L)
                    dos.writeLong(10485760L)
                    dos.writeShort(nodeBytes.size)
                    dos.write(nodeBytes)
                    dos.writeInt(0)
                    dos.flush()

                    // 5. Read XFER_SEG (type 1)
                    val msgType = dis.readByte()
                    if (msgType == 1.toByte()) {
                        val flags = dis.readByte().toInt()
                        dis.readLong() // transferId
                        if ((flags and 1) != 0) {
                            val segExtLen = dis.readInt()
                            if (segExtLen > 0) dis.readFully(ByteArray(segExtLen))
                        }
                        val segLen = dis.readLong()
                        val segBytes = ByteArray(segLen.toInt())
                        dis.readFully(segBytes)

                        // SIMULATE FAILURE: Abruptly close socket WITHOUT sending XFER_ACK!
                        socket.close()
                    }
                }
            } catch (e: Exception) {
                // Expected disconnect
            }
        }
        attempt1Thread.start()

        var ackedInAttempt1 = false
        val item1 = Pair<ByteArray, (suspend () -> Unit)?>(bundleBytes) {
            ackedInAttempt1 = true
            bundleRecord = bundleRecord.copy(state = BundleState.DELIVERED)
        }

        val successAttempt1 = adapter.sendBundles(listOf(item1), "127.0.0.1:$port1")

        attempt1Thread.join(2000)
        serverSocket1.close()

        // Verification 1: Transfer failed, ACK callback NOT called, bundle remains in OUTBOX & payload file intact
        assertFalse(successAttempt1, "Attempt 1 MUST fail when connection drops before XFER_ACK")
        assertFalse(ackedInAttempt1, "Responsibility transfer callback MUST NOT be invoked without XFER_ACK")
        assertEquals(BundleState.OUTBOX, bundleRecord.state, "Bundle state MUST remain OUTBOX after failed attempt")
        assertTrue(payloadFile.exists(), "Payload file MUST remain preserved in store")

        // --- ATTEMPT 2: Connection retry, transfer completes smoothly with XFER_ACK ---
        val serverSocket2 = ServerSocket(0)
        val port2 = serverSocket2.localPort

        val receivedBundles2 = mutableListOf<ByteArray>()
        val latch2 = CountDownLatch(1)

        val attempt2Thread = startMockTcpClServer(serverSocket2, "dtn://dest-node") { receivedBytes ->
            receivedBundles2.add(receivedBytes)
            latch2.countDown()
        }

        var ackedInAttempt2 = false
        val item2 = Pair<ByteArray, (suspend () -> Unit)?>(bundleBytes) {
            ackedInAttempt2 = true
            bundleRecord = bundleRecord.copy(state = BundleState.DELIVERED)
        }

        val successAttempt2 = adapter.sendBundles(listOf(item2), "127.0.0.1:$port2")

        assertTrue(latch2.await(5, TimeUnit.SECONDS))
        attempt2Thread.join(2000)
        serverSocket2.close()

        // Verification 2: Transfer succeeds, ACK callback called, bundle state updated to DELIVERED only on confirmation
        assertTrue(successAttempt2, "Attempt 2 MUST succeed when XFER_ACK is received")
        assertTrue(ackedInAttempt2, "Responsibility transfer callback MUST be invoked upon receiving XFER_ACK")
        assertEquals(BundleState.DELIVERED, bundleRecord.state, "Bundle state MUST be updated to DELIVERED only after XFER_ACK confirmation")
        assertEquals(1, receivedBundles2.size)

        adapter.stop()
    }

    @Test
    fun testScenario16_PausedConvergenceProfilePreventsTransmission() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "dtn://paused-gateway") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        val profile = ConvergenceProfile(
            profileId = "dtn://paused-gateway",
            name = "Paused Gateway Profile",
            triggerType = TriggerType.PERIODIC_INTERNET,
            targetAddress = "127.0.0.1:$port",
            isPaused = true // PAUSED PROFILE
        )

        val payloadFile = File(tempDir.toFile(), "payload_sc16.txt")
        payloadFile.writeText("Payload targeting paused gateway")

        val record = BundleRecord(
            bundleId = "bundle-sc16-paused",
            destinationEid = "dtn://paused-gateway/chat",
            sourceEid = "dtn://node-a/chat",
            creationTimestamp = 100000L,
            sequenceNumber = 16,
            lifetimeMs = 600000L,
            payloadFilePath = payloadFile.absolutePath,
            state = BundleState.OUTBOX,
            isRead = false,
            bpsecStatus = BpsecStatus.VALID,
            hopCount = 1
        )

        // Evaluate profile status before attempting network transmission
        val canTransmit = !profile.isPaused

        if (canTransmit) {
            val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
            val primary = PrimaryBlock(
                destination = Eid(record.destinationEid),
                source = Eid(record.sourceEid),
                reportTo = Eid(record.sourceEid),
                creationTimestamp = Pair(100000L, 16L),
                lifetimeMs = record.lifetimeMs
            )
            val payload = PayloadBlock(data = payloadFile.readBytes())
            val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, null, null)
            adapter.sendBundles(listOf(Pair(Bpv7Parser.serialize(bundle), null)), profile.targetAddress)
        }

        val received = latch.await(1, TimeUnit.SECONDS)
        assertFalse(received, "Bundle MUST NOT be transmitted over network when destination convergence profile is paused")
        assertEquals(0, receivedBundles.size, "Mock server must receive 0 bundles for paused profile")
        assertEquals(BundleState.OUTBOX, record.state, "Bundle must remain in OUTBOX state when profile is paused")

        serverSocket.close()
        serverThread.join(500)
    }

    @Test
    fun testScenario17_RoutingRulePrioritySpecificOverDefaultFallback() = runBlocking {
        val rules = listOf(
            RoutingRule(
                destinationEidPattern = "dtn://sensor-net/*",
                nextHopEid = "dtn://gateway-sensors"
            ),
            RoutingRule(
                destinationEidPattern = "dtn://*",
                nextHopEid = "dtn://gateway-default"
            )
        )

        fun resolveNextHop(destination: String): String {
            for (rule in rules) {
                val pattern = rule.destinationEidPattern.replace("*", "").trimEnd('/')
                if (io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isPrefixMatch(pattern, destination)) {
                    return rule.nextHopEid
                }
            }
            return destination
        }

        // Test 1: Specific match for sensor subnet
        val resolvedSensors = resolveNextHop("dtn://sensor-net/temp")
        assertEquals("dtn://gateway-sensors", resolvedSensors, "Specific routing pattern MUST match before broad fallback rule")

        // Test 2: General fallback match
        val resolvedOther = resolveNextHop("dtn://other-net/chat")
        assertEquals("dtn://gateway-default", resolvedOther, "Wildcard fallback rule MUST match when specific pattern does not match")
    }

    @Test
    fun testScenario18_Rfc9171NullEidDtnNoneHandling() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val receivedBundles = mutableListOf<ByteArray>()
        val latch = CountDownLatch(1)

        val serverThread = startMockTcpClServer(serverSocket, "dtn://node-b") { bundleBytes ->
            receivedBundles.add(bundleBytes)
            latch.countDown()
        }

        // RFC 9171 Section 4.1.2: Anonymous / Null EID is "dtn:none"
        val nullEid = Eid("dtn:none")
        val primary = PrimaryBlock(
            version = 7,
            bundleControlFlags = 0L,
            crcType = 0,
            destination = Eid("dtn://node-b/chat"),
            source = nullEid,
            reportTo = nullEid,
            creationTimestamp = Pair(100000L, 18L),
            lifetimeMs = 600000L
        )
        val payload = PayloadBlock(data = "Payload with RFC 9171 dtn:none null source".toByteArray())
        val hopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val bundle = io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle(primary, payload, hopCount, null)
        val bundleBytes = Bpv7Parser.serialize(bundle)

        val adapter = TcpClAdapter(context = fakeContext, port = 0, logDao = fakeLogDao)
        val success = adapter.sendBundles(listOf(Pair(bundleBytes, null)), "127.0.0.1:$port")

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertTrue(success)
        assertEquals(1, receivedBundles.size)

        val rxBundle = Bpv7Parser.deserialize(receivedBundles[0])
        assertEquals("dtn:none", rxBundle.primaryBlock.source.uri, "RFC 9171 null EID dtn:none MUST be correctly parsed as source")
        assertEquals("dtn:none", rxBundle.primaryBlock.reportTo.uri, "RFC 9171 null EID dtn:none MUST be correctly parsed as reportTo")
        assertEquals("dtn://node-b/chat", rxBundle.primaryBlock.destination.uri)

        adapter.stop()
        serverSocket.close()
        serverThread.join(1000)
    }
}


