package io.github.loic_fejoz.dtn_android_messenger.cla

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.loic_fejoz.dtn_android_messenger.data.dao.SystemLogDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.SystemLog
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

interface ConvergenceLayerAdapter {
    val name: String

    fun start(
        scope: CoroutineScope,
        listener: suspend (ByteArray) -> Boolean,
    )

    fun stop()

    suspend fun sendBundle(
        bundleBytes: ByteArray,
        targetAddress: String,
        onAcknowledged: (suspend () -> Unit)? = null,
    ): Boolean

    suspend fun sendBundles(
        bundles: List<Pair<ByteArray, (suspend () -> Unit)?>>,
        targetAddress: String,
    ): Boolean
}

private const val MAX_EXTENSION_LENGTH = 65536
private const val TCPCL_IDLE_TIMEOUT_MS = 30000L
private const val TCPCL_GRACE_TIMEOUT_MS = 3000L

private const val MSG_XFER_SEGMENT = 1
private const val MSG_XFER_ACK = 2
private const val MSG_XFER_REFUSE = 3
private const val MSG_KEEPALIVE = 4
private const val MSG_SESS_TERM = 5
private const val MSG_SESS_INIT = 7

// RFC 9174 Section 5.2.2 Table 9 - XFER_SEGMENT / XFER_ACK Flags
internal const val XFER_FLAG_END = 0x01
internal const val XFER_FLAG_START = 0x02

// RFC 9174 Section 5.2.4 Table 11 - XFER_REFUSE Reason Codes
private const val REFUSE_REASON_UNKNOWN: Byte = 0x00
private const val REFUSE_REASON_NOT_ACCEPTABLE: Byte = 0x01
private const val REFUSE_REASON_NOT_SUPPORTED: Byte = 0x02

// RFC 9174 Section 8.7 Table 16 - SESS_TERM Reason Codes
private const val REASON_UNKNOWN: Byte = 0x00
private const val REASON_IDLE_TIMEOUT: Byte = 0x01
private const val REASON_VERSION_MISMATCH: Byte = 0x02
private const val REASON_BUSY: Byte = 0x03
private const val REASON_CONTACT_FAILURE: Byte = 0x04
private const val REASON_RESOURCE_EXHAUSTION: Byte = 0x05

private suspend fun readSessInitPayload(input: ByteReadChannel) {
    val staticBody = ByteArray(20)
    input.readFully(staticBody, 0, 20)
    val nodeIdLen = ByteBuffer.wrap(staticBody, 18, 2).short.toInt() and 0xFFFF
    if (nodeIdLen > 0) {
        val nodeIdBytes = ByteArray(nodeIdLen)
        input.readFully(nodeIdBytes, 0, nodeIdLen)
    }
    val extLenBytes = ByteArray(4)
    input.readFully(extLenBytes, 0, 4)
    val extLen = ByteBuffer.wrap(extLenBytes).int
    if (extLen < 0 || extLen > MAX_EXTENSION_LENGTH) {
        throw IllegalArgumentException("Invalid SESS_INIT extension length: $extLen (exceeds max $MAX_EXTENSION_LENGTH)")
    }
    if (extLen > 0) {
        val extBytes = ByteArray(extLen)
        input.readFully(extBytes, 0, extLen)
    }
}

private suspend fun writeXferAck(
    output: ByteWriteChannel,
    flags: Int,
    transferId: Long,
    acknowledgedLength: Long,
) {
    val ackMsg = ByteArray(18)
    ackMsg[0] = MSG_XFER_ACK.toByte()
    ackMsg[1] = flags.toByte()
    ByteBuffer.wrap(ackMsg, 2, 8).putLong(transferId)
    ByteBuffer.wrap(ackMsg, 10, 8).putLong(acknowledgedLength)
    output.writeFully(ackMsg, 0, 18)
}

private suspend fun writeXferRefuse(
    output: ByteWriteChannel,
    reasonCode: Byte,
    transferId: Long,
) {
    val refuseMsg = ByteArray(10)
    refuseMsg[0] = MSG_XFER_REFUSE.toByte()
    refuseMsg[1] = reasonCode
    ByteBuffer.wrap(refuseMsg, 2, 8).putLong(transferId)
    output.writeFully(refuseMsg, 0, 10)
}

private suspend fun readXferSegmentAndHandle(
    input: ByteReadChannel,
    maxSizeBytes: Long,
    inFlightTransfers: MutableMap<Long, java.io.ByteArrayOutputStream>,
    listener: (suspend (ByteArray) -> Boolean)?,
    log: (String, String) -> Unit,
    sendAck: suspend (flags: Int, transferId: Long, ackLen: Long) -> Unit,
    sendRefuse: suspend (reason: Byte, transferId: Long) -> Unit,
) {
    val flags = input.readByte().toInt() and 0xFF
    val transferId = input.readLong()

    // RFC 9174 §5.2.2: Transfer Extension Items length is present ONLY when START (bit 1 = 0x02) is set
    if ((flags and XFER_FLAG_START) != 0) {
        val extByteLen = input.readInt()
        if (extByteLen < 0 || extByteLen > MAX_EXTENSION_LENGTH) {
            throw IllegalArgumentException("Invalid XFER_SEGMENT extension length: $extByteLen (exceeds max $MAX_EXTENSION_LENGTH)")
        }
        if (extByteLen > 0) {
            val extBytes = ByteArray(extByteLen)
            input.readFully(extBytes, 0, extByteLen)
        }
    }

    // Validate 64-bit length before narrowing to Int to prevent truncation bypass
    val rawLength = input.readLong()
    if (rawLength <= 0L || rawLength > maxSizeBytes) {
        throw IllegalArgumentException("Invalid segment length: $rawLength (exceeds max $maxSizeBytes bytes)")
    }
    val length = rawLength.toInt()
    val segmentBytes =
        try {
            ByteArray(length)
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("Memory allocation failed for segment of size $length bytes", e)
        }
    input.readFully(segmentBytes, 0, length)

    val isStart = (flags and XFER_FLAG_START) != 0
    val isEnd = (flags and XFER_FLAG_END) != 0

    if (isStart && isEnd) {
        // Single-segment transfer fast path
        log("INFO", "Received bundle ($length bytes)")
        val accepted =
            try {
                listener?.invoke(segmentBytes) ?: false
            } catch (e: Exception) {
                log("ERROR", "Error ingesting received bundle: ${e.message}")
                false
            }
        if (accepted) {
            sendAck(flags, transferId, length.toLong())
        } else {
            log("WARN", "Bundle ingestion failed or rejected; sending XFER_REFUSE for transfer $transferId")
            sendRefuse(REFUSE_REASON_UNKNOWN, transferId)
        }
        return
    }

    // Multi-segment transfer reassembly
    if (isStart) {
        inFlightTransfers[transferId] = java.io.ByteArrayOutputStream()
    }
    val buffer = inFlightTransfers[transferId]
    if (buffer == null) {
        log("WARN", "Received non-START segment for unknown transfer $transferId; sending XFER_REFUSE")
        sendRefuse(REFUSE_REASON_NOT_ACCEPTABLE, transferId)
        return
    }

    if (buffer.size().toLong() + length.toLong() > maxSizeBytes) {
        inFlightTransfers.remove(transferId)
        log("WARN", "Reassembled transfer $transferId exceeds max bundle size ($maxSizeBytes bytes); sending XFER_REFUSE")
        sendRefuse(REFUSE_REASON_NOT_ACCEPTABLE, transferId)
        throw IllegalArgumentException("Reassembled transfer $transferId exceeds max $maxSizeBytes bytes")
    }

    buffer.write(segmentBytes)
    val cumulativeLen = buffer.size().toLong()

    if (isEnd) {
        inFlightTransfers.remove(transferId)
        val fullBundle = buffer.toByteArray()
        log("INFO", "Received multi-segment bundle (${fullBundle.size} bytes, transfer $transferId)")
        val accepted =
            try {
                listener?.invoke(fullBundle) ?: false
            } catch (e: Exception) {
                log("ERROR", "Error ingesting received bundle: ${e.message}")
                false
            }
        if (accepted) {
            sendAck(flags, transferId, cumulativeLen)
        } else {
            log("WARN", "Bundle ingestion failed or rejected; sending XFER_REFUSE for transfer $transferId")
            sendRefuse(REFUSE_REASON_UNKNOWN, transferId)
        }
    } else {
        // Intermediate segment ACK with cumulative acknowledged length
        sendAck(flags, transferId, cumulativeLen)
    }
}

class TcpClAdapter(
    private val context: Context,
    private val port: Int = 5051,
    private val logDao: SystemLogDao,
) : ConvergenceLayerAdapter {
    override val name = "TCPCLv4"
    private var serverJob: Job? = null
    private val selectorLock = Any()
    private var selectorManager: ActorSelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var listener: (suspend (ByteArray) -> Boolean)? = null

    private var adapterScope: CoroutineScope? = null
    private val peerLocks = HashMap<String, Mutex>()

    private fun getOrCreateSelector(): ActorSelectorManager =
        synchronized(selectorLock) {
            selectorManager ?: ActorSelectorManager(Dispatchers.IO).also { selectorManager = it }
        }

    private fun getPeerLock(targetAddress: String): Mutex {
        synchronized(peerLocks) {
            var lock = peerLocks[targetAddress]
            if (lock == null) {
                lock = Mutex()
                peerLocks[targetAddress] = lock
            }
            return lock
        }
    }

    private fun log(
        level: String,
        msg: String,
    ) {
        adapterScope?.launch(Dispatchers.IO) {
            logDao.insert(SystemLog(timestamp = System.currentTimeMillis(), level = level, message = "[TCPCL] $msg"))
        }
    }

    private fun getLocalNodeName(): String {
        val prefs = io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.getEncryptedSharedPreferences(context)
        return (prefs.getString("local_node_name", "dtn://my-node") ?: "dtn://my-node").trim()
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            true // Fallback to true if connectivity check fails
        }
    }

    private fun buildSessInit(nodeId: String): ByteArray {
        var cleanNodeId = nodeId.trim()
        if (cleanNodeId.startsWith("dtn://") && !cleanNodeId.endsWith("/")) {
            cleanNodeId += "/"
        }
        val nodeIdBytes = cleanNodeId.toByteArray(Charsets.UTF_8)
        val nodeIdLen = nodeIdBytes.size
        val totalSize = 1 + 2 + 8 + 8 + 2 + nodeIdLen + 4
        val buf = ByteArray(totalSize)
        buf[0] = MSG_SESS_INIT.toByte() // 7
        buf[1] = 0 // Keepalive MSB
        buf[2] = 30 // Keepalive LSB (30 seconds)
        val mruVal = io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.getMaxBundleSizeBytes(context)
        java.nio.ByteBuffer.wrap(buf, 3, 8).putLong(mruVal) // Segment MRU
        java.nio.ByteBuffer.wrap(buf, 11, 8).putLong(mruVal) // Transfer MRU
        java.nio.ByteBuffer.wrap(buf, 19, 2).putShort(nodeIdLen.toShort())
        System.arraycopy(nodeIdBytes, 0, buf, 21, nodeIdLen)
        val extOffset = 21 + nodeIdLen
        buf[extOffset] = 0
        buf[extOffset + 1] = 0
        buf[extOffset + 2] = 0
        buf[extOffset + 3] = 0
        return buf
    }

    override fun start(
        scope: CoroutineScope,
        listener: suspend (ByteArray) -> Boolean,
    ) {
        this.adapterScope = scope
        this.listener = listener
        val sel = getOrCreateSelector()
        serverJob =
            scope.launch(Dispatchers.IO) {
                try {
                    serverSocket = aSocket(sel).tcp().bind("0.0.0.0", port)
                    log("INFO", "Server listening on port $port")
                    while (isActive) {
                        val socket = serverSocket!!.accept()
                        launch { handleIncomingConnection(socket) }
                    }
                } catch (e: Exception) {
                    log("WARN", "Server stopped or failed to bind: ${e.message}")
                }
            }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        log("INFO", "Accepted connection from ${socket.remoteAddress}")
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)
        val inFlightTransfers = HashMap<Long, java.io.ByteArrayOutputStream>()
        try {
            // Handshake phase with 10s timeout
            withTimeout(10000) {
                // Read 6 bytes contact header (RFC 9174)
                val clientHeader = ByteArray(6)
                input.readFully(clientHeader, 0, 6)
                if (clientHeader[0] != 'd'.code.toByte() || clientHeader[1] != 't'.code.toByte() ||
                    clientHeader[2] != 'n'.code.toByte() || clientHeader[3] != '!'.code.toByte()
                ) {
                    throw Exception("Invalid contact header from client")
                }

                // Send contact header back (6 bytes)
                val serverHeader =
                    byteArrayOf(
                        'd'.code.toByte(),
                        't'.code.toByte(),
                        'n'.code.toByte(),
                        '!'.code.toByte(),
                        4,
                        0,
                    )
                output.writeFully(serverHeader, 0, 6)

                // Read SESS_INIT (type 7)
                val sessInitType = input.readByte().toInt()
                if (sessInitType != MSG_SESS_INIT) {
                    throw Exception("Expected SESS_INIT from client, got type $sessInitType")
                }
                readSessInitPayload(input)

                // Send SESS_INIT back
                val serverSessInit = buildSessInit(getLocalNodeName())
                output.writeFully(serverSessInit, 0, serverSessInit.size)
            }

            // Message transmission phase
            while (socket.isActive) {
                val msgType = withTimeout(45000) { input.readByte().toInt() }
                when (msgType) {
                    MSG_XFER_SEGMENT -> {
                        withTimeout(15000) {
                            val maxSizeBytes = io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.getMaxBundleSizeBytes(context)
                            readXferSegmentAndHandle(
                                input = input,
                                maxSizeBytes = maxSizeBytes,
                                inFlightTransfers = inFlightTransfers,
                                listener = listener,
                                log = { lvl, msg -> log(lvl, msg) },
                                sendAck = { flags, transferId, ackLen -> writeXferAck(output, flags, transferId, ackLen) },
                                sendRefuse = { reason, transferId -> writeXferRefuse(output, reason, transferId) },
                            )
                        }
                    }
                    MSG_KEEPALIVE -> {
                        // Ignore
                    }
                    MSG_SESS_TERM -> {
                        withTimeout(5000) {
                            val termFlags = input.readByte().toInt()
                            val termReason = input.readByte()
                            log("INFO", "Received session term from client (flags $termFlags, reason $termReason)")
                            if ((termFlags and 1) == 0) {
                                try {
                                    withTimeout(TCPCL_GRACE_TIMEOUT_MS) {
                                        sendSessTerm(output, isReply = true, reasonCode = termReason)
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        }
                        break
                    }
                    else -> {
                        log("WARN", "Unknown message type: $msgType")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            log("WARN", "Incoming connection error: ${e.message}")
        } finally {
            inFlightTransfers.clear()
            socket.close()
        }
    }

    private val activeSessions = HashMap<String, ActiveTcpclSession>()

    override fun stop() {
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(activeSessions) {
            for (session in activeSessions.values) {
                try {
                    session.socket.close()
                } catch (e: Exception) {
                }
            }
            activeSessions.clear()
        }
        synchronized(selectorLock) {
            try {
                selectorManager?.close()
            } catch (e: Exception) {
            }
            selectorManager = null
        }
    }

    override suspend fun sendBundle(
        bundleBytes: ByteArray,
        targetAddress: String,
        onAcknowledged: (suspend () -> Unit)?,
    ): Boolean {
        val list = if (bundleBytes.isEmpty()) emptyList() else listOf(Pair(bundleBytes, onAcknowledged))
        return sendBundles(list, targetAddress)
    }

    override suspend fun sendBundles(
        bundles: List<Pair<ByteArray, (suspend () -> Unit)?>>,
        targetAddress: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            // Check if an existing active TCPCL session is available
            var existingSession = synchronized(activeSessions) { activeSessions[targetAddress] }
            if (existingSession != null && !existingSession.isClosing) {
                log("INFO", "Reusing active TCPCL session for $targetAddress to transmit ${bundles.size} bundle(s)")
                var allOk = true
                for ((bytes, onAck) in bundles) {
                    if (bytes.isNotEmpty()) {
                        val ok = existingSession.enqueueBundle(bytes, onAck)
                        if (!ok) allOk = false
                    }
                }
                return@withContext allOk
            }

            val parts = targetAddress.split(":")
            val host = parts[0]
            val destPort = parts.getOrNull(1)?.toIntOrNull() ?: port

            if (host != "127.0.0.1" && host != "localhost" && host != "10.0.2.2" && !isNetworkAvailable()) {
                log("WARN", "Network unavailable, aborting connection attempt to $host")
                return@withContext false
            }

            val lock = getPeerLock(targetAddress)
            val acquired =
                if (bundles.isEmpty()) {
                    lock.tryLock()
                } else {
                    lock.lock()
                    true
                }
            if (!acquired) {
                // If another coroutine is connecting, check if the session is now ready
                existingSession = synchronized(activeSessions) { activeSessions[targetAddress] }
                if (existingSession != null && !existingSession.isClosing) {
                    var allOk = true
                    for ((bytes, onAck) in bundles) {
                        if (bytes.isNotEmpty()) {
                            val ok = existingSession.enqueueBundle(bytes, onAck)
                            if (!ok) allOk = false
                        }
                    }
                    return@withContext allOk
                }
                log("INFO", "Connection to $targetAddress is already active, skipping duplicate pull connection")
                return@withContext true
            }

            var socket: Socket? = null
            try {
                // Check once more under the lock
                existingSession = synchronized(activeSessions) { activeSessions[targetAddress] }
                if (existingSession != null && !existingSession.isClosing) {
                    var allOk = true
                    for ((bytes, onAck) in bundles) {
                        if (bytes.isNotEmpty()) {
                            val ok = existingSession.enqueueBundle(bytes, onAck)
                            if (!ok) allOk = false
                        }
                    }
                    return@withContext allOk
                }

                log("INFO", "Connecting to $host:$destPort")
                val sel = getOrCreateSelector()
                val connectedSocket =
                    withTimeout(10000) {
                        aSocket(sel).tcp().connect(host, destPort)
                    }
                socket = connectedSocket

                val input = connectedSocket.openReadChannel()
                val output = connectedSocket.openWriteChannel(autoFlush = true)

                // Handshake phase
                withTimeout(15000) {
                    val clientHeader =
                        byteArrayOf(
                            'd'.code.toByte(),
                            't'.code.toByte(),
                            'n'.code.toByte(),
                            '!'.code.toByte(),
                            4,
                            0,
                        )
                    output.writeFully(clientHeader, 0, 6)

                    val serverHeader = ByteArray(6)
                    input.readFully(serverHeader, 0, 6)
                    if (serverHeader[0] != 'd'.code.toByte() || serverHeader[1] != 't'.code.toByte() ||
                        serverHeader[2] != 'n'.code.toByte() || serverHeader[3] != '!'.code.toByte() ||
                        serverHeader[4] != 4.toByte()
                    ) {
                        throw Exception("Invalid contact header version from server")
                    }

                    val sessInit = buildSessInit(getLocalNodeName())
                    output.writeFully(sessInit, 0, sessInit.size)

                    val sessInitType = input.readByte().toInt()
                    if (sessInitType != MSG_SESS_INIT) {
                        throw Exception("Expected SESS_INIT from server, got type $sessInitType")
                    }
                    readSessInitPayload(input)
                }

                val maxSizeBytes = io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.getMaxBundleSizeBytes(context)
                val sessionScope = adapterScope ?: CoroutineScope(Dispatchers.IO)
                lateinit var newSession: ActiveTcpclSession
                newSession =
                    ActiveTcpclSession(
                        targetAddress = targetAddress,
                        socket = connectedSocket,
                        input = input,
                        output = output,
                        sessionScope = sessionScope,
                        log = { lvl, msg -> log(lvl, msg) },
                        listener = listener,
                        maxSizeBytes = maxSizeBytes,
                        onSessionClosed = {
                            synchronized(activeSessions) {
                                if (activeSessions[targetAddress] === newSession) {
                                    activeSessions.remove(targetAddress)
                                }
                            }
                        },
                    )

                synchronized(activeSessions) {
                    activeSessions[targetAddress] = newSession
                }
                newSession.start()
                socket = null // Ownership transferred to ActiveTcpclSession

                var allOk = true
                for ((bytes, onAck) in bundles) {
                    if (bytes.isNotEmpty()) {
                        val ok = newSession.enqueueBundle(bytes, onAck)
                        if (!ok) allOk = false
                    }
                }
                return@withContext allOk
            } catch (e: Exception) {
                runCatching { socket?.close() }
                log("ERROR", "Failed to communicate with $targetAddress: ${e.message}")
                return@withContext false
            } finally {
                lock.unlock()
            }
        }
}

private suspend fun sendSessTerm(
    output: ByteWriteChannel,
    isReply: Boolean,
    reasonCode: Byte,
) {
    val flags: Byte = if (isReply) 1 else 0
    val sessTermMsg = byteArrayOf(MSG_SESS_TERM.toByte(), flags, reasonCode)
    output.writeFully(sessTermMsg, 0, 3)
}

private class OutboundTransfer(
    val bundleBytes: ByteArray,
    val onAcknowledged: (suspend () -> Unit)?,
    val ackDeferred: CompletableDeferred<Boolean>,
    val completionDeferred: CompletableDeferred<Boolean>,
)

private class ActiveTcpclSession(
    val targetAddress: String,
    val socket: Socket,
    val input: ByteReadChannel,
    val output: ByteWriteChannel,
    val sessionScope: CoroutineScope,
    private val log: (String, String) -> Unit,
    private val listener: (suspend (ByteArray) -> Boolean)?,
    private val maxSizeBytes: Long,
    private val onSessionClosed: () -> Unit,
) {
    val outgoingChannel = kotlinx.coroutines.channels.Channel<OutboundTransfer>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val pendingAcks = HashMap<Long, CompletableDeferred<Boolean>>()
    private val acksLock = Any()
    private val writeLock = Mutex()

    @Volatile
    private var lastActivityTime = System.currentTimeMillis()

    @Volatile
    var isClosing = false
        private set

    private var transferIdSeq = 1L

    fun start() {
        sessionScope.launch(Dispatchers.IO) {
            val readerJob = launch { readLoop() }
            val writerJob = launch { writeLoop() }
            val idleJob = launch { idleTimerLoop() }

            readerJob.join()
            isClosing = true
            outgoingChannel.close()
            while (true) {
                val pending = outgoingChannel.tryReceive().getOrNull() ?: break
                pending.ackDeferred.complete(false)
                pending.completionDeferred.complete(false)
            }
            writerJob.cancel()
            idleJob.cancel()
            try {
                socket.close()
            } catch (e: Exception) {
            }
            onSessionClosed()
        }
    }

    private suspend fun writeLoop() {
        for (task in outgoingChannel) {
            if (isClosing) {
                task.ackDeferred.complete(false)
                task.completionDeferred.complete(false)
                continue
            }
            val transferId =
                synchronized(acksLock) {
                    val tid = transferIdSeq++
                    pendingAcks[tid] = task.ackDeferred
                    tid
                }
            try {
                withTimeout(20000) {
                    val xferHeader = ByteArray(22)
                    xferHeader[0] = MSG_XFER_SEGMENT.toByte()
                    xferHeader[1] = 3 // START (1) | END (2)
                    java.nio.ByteBuffer.wrap(xferHeader, 2, 8).putLong(transferId)
                    xferHeader[10] = 0
                    xferHeader[11] = 0
                    xferHeader[12] = 0
                    xferHeader[13] = 0
                    java.nio.ByteBuffer.wrap(xferHeader, 14, 8).putLong(task.bundleBytes.size.toLong())

                    writeLock.withLock {
                        output.writeFully(xferHeader, 0, 22)
                        output.writeFully(task.bundleBytes, 0, task.bundleBytes.size)
                    }
                    lastActivityTime = System.currentTimeMillis()

                    val acked = task.ackDeferred.await()
                    if (acked) {
                        try {
                            task.onAcknowledged?.invoke()
                        } catch (e: Exception) {
                            log("ERROR", "Error executing onAcknowledged callback: ${e.message}")
                        }
                    }
                    task.completionDeferred.complete(acked)
                }
            } catch (e: Exception) {
                log("ERROR", "Failed sending transfer $transferId to $targetAddress: ${e.message}")
                synchronized(acksLock) {
                    pendingAcks.remove(transferId)
                }
                task.ackDeferred.complete(false)
                task.completionDeferred.complete(false)
            }
        }
    }

    private suspend fun readLoop() {
        val inFlightTransfers = HashMap<Long, java.io.ByteArrayOutputStream>()
        try {
            while (socket.isActive && !isClosing) {
                val msgType =
                    try {
                        input.readByte().toInt()
                    } catch (e: java.io.EOFException) {
                        log("INFO", "Connection closed by remote peer $targetAddress")
                        break
                    } catch (e: Exception) {
                        if (!isClosing) {
                            log("INFO", "Connection closed or error from $targetAddress: ${e.message}")
                        }
                        break
                    }

                lastActivityTime = System.currentTimeMillis()

                when (msgType) {
                    MSG_XFER_ACK -> {
                        val ackBody = ByteArray(17)
                        input.readFully(ackBody, 0, 17)
                        val ackTransferId = java.nio.ByteBuffer.wrap(ackBody, 1, 8).long
                        val ackLen = java.nio.ByteBuffer.wrap(ackBody, 9, 8).long
                        log("INFO", "Successfully received XFER_ACK for transfer $ackTransferId from $targetAddress ($ackLen bytes)")
                        val deferred =
                            synchronized(acksLock) {
                                pendingAcks.remove(ackTransferId)
                            }
                        deferred?.complete(true)
                    }
                    MSG_XFER_REFUSE -> {
                        val refuseBody = ByteArray(9)
                        input.readFully(refuseBody, 0, 9)
                        val refuseReason = refuseBody[0].toInt()
                        val refuseTransferId = java.nio.ByteBuffer.wrap(refuseBody, 1, 8).long
                        log("WARN", "Received XFER_REFUSE for transfer $refuseTransferId (reason code $refuseReason) from $targetAddress")
                        val deferred =
                            synchronized(acksLock) {
                                pendingAcks.remove(refuseTransferId)
                            }
                        deferred?.complete(false)
                    }
                    MSG_XFER_SEGMENT -> {
                        withTimeout(15000) {
                            readXferSegmentAndHandle(
                                input = input,
                                maxSizeBytes = maxSizeBytes,
                                inFlightTransfers = inFlightTransfers,
                                listener = listener,
                                log = log,
                                sendAck = { flags, transferId, ackLen ->
                                    writeLock.withLock {
                                        writeXferAck(output, flags, transferId, ackLen)
                                    }
                                },
                                sendRefuse = { reason, transferId ->
                                    writeLock.withLock {
                                        writeXferRefuse(output, reason, transferId)
                                    }
                                },
                            )
                        }
                    }
                    MSG_KEEPALIVE -> {
                        log("DEBUG", "Received KEEPALIVE from $targetAddress")
                    }
                    MSG_SESS_TERM -> {
                        withTimeout(5000) {
                            val termFlags = input.readByte().toInt()
                            val termReason = input.readByte()
                            log("INFO", "Server terminated session (flags $termFlags, reason $termReason)")
                            if ((termFlags and 1) == 0) {
                                try {
                                    withTimeout(TCPCL_GRACE_TIMEOUT_MS) {
                                        writeLock.withLock {
                                            sendSessTerm(output, isReply = true, reasonCode = termReason)
                                        }
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        }
                        break
                    }
                    else -> {
                        log("WARN", "Unknown message type $msgType from $targetAddress")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (!isClosing) {
                log("INFO", "Session read error for $targetAddress: ${e.message}")
            }
        }
    }

    private suspend fun idleTimerLoop() {
        while (socket.isActive && !isClosing) {
            delay(1000)
            val now = System.currentTimeMillis()
            val hasPending = synchronized(acksLock) { pendingAcks.isNotEmpty() }
            if (!hasPending && (now - lastActivityTime >= TCPCL_IDLE_TIMEOUT_MS)) {
                log("INFO", "30s idle timeout reached for $targetAddress, initiating clean SESS_TERM")
                isClosing = true
                try {
                    withTimeout(TCPCL_GRACE_TIMEOUT_MS) {
                        writeLock.withLock {
                            sendSessTerm(output, isReply = false, reasonCode = REASON_IDLE_TIMEOUT)
                        }
                    }
                } catch (e: Exception) {
                }
                break
            }
        }
    }

    suspend fun enqueueBundle(
        bundleBytes: ByteArray,
        onAcknowledged: (suspend () -> Unit)?,
    ): Boolean {
        if (isClosing) return false
        val ackDeferred = CompletableDeferred<Boolean>()
        val completionDeferred = CompletableDeferred<Boolean>()
        val task = OutboundTransfer(bundleBytes, onAcknowledged, ackDeferred, completionDeferred)
        val sendResult = outgoingChannel.trySend(task)
        if (sendResult.isFailure) return false
        return completionDeferred.await()
    }
}

class BluetoothClassicAdapter(
    private val context: Context,
    private val logDao: SystemLogDao,
) : ConvergenceLayerAdapter {
    override val name = "Bluetooth"
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var serverJob: Job? = null
    private var serverSocket: BluetoothServerSocket? = null
    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    private var listener: (suspend (ByteArray) -> Boolean)? = null

    private val activeSockets = java.util.Collections.synchronizedList(mutableListOf<BluetoothSocket>())
    private var adapterScope: CoroutineScope? = null

    private fun log(
        level: String,
        msg: String,
    ) {
        adapterScope?.launch(Dispatchers.IO) {
            logDao.insert(SystemLog(timestamp = System.currentTimeMillis(), level = level, message = "[Bluetooth] $msg"))
        }
    }

    override fun start(
        scope: CoroutineScope,
        listener: suspend (ByteArray) -> Boolean,
    ) {
        this.adapterScope = scope
        this.listener = listener
        if (bluetoothAdapter == null) {
            log("WARN", "Bluetooth is not supported on this device")
            return
        }

        serverJob =
            scope.launch(Dispatchers.IO) {
                var wasDisabledLogged = false
                while (isActive) {
                    try {
                        if (!bluetoothAdapter.isEnabled) {
                            if (!wasDisabledLogged) {
                                log("WARN", "Bluetooth is currently disabled. Waiting for activation...")
                                wasDisabledLogged = true
                            }
                            delay(15000) // Sleep 15s when disabled (uses near-0 CPU)
                            continue
                        }
                        wasDisabledLogged = false

                        var serverSock: BluetoothServerSocket? = null
                        try {
                            // In Android 12+, we need BLUETOOTH_CONNECT.
                            serverSock = bluetoothAdapter.listenUsingRfcommWithServiceRecord("DtnMessenger", SPP_UUID)
                            serverSocket = serverSock
                            log("INFO", "RFCOMM server socket opened")
                            while (isActive && bluetoothAdapter.isEnabled) {
                                val socket = serverSock.accept()
                                launch { handleIncomingRfcomm(socket) }
                            }
                        } catch (e: SecurityException) {
                            log("ERROR", "Bluetooth permission denied: ${e.message}")
                            break
                        } catch (e: Exception) {
                            if (isActive) {
                                log("WARN", "RFCOMM listener error, restarting in 5s: ${e.message}")
                                delay(5000)
                            }
                        } finally {
                            try {
                                serverSock?.close()
                            } catch (e: Exception) {
                            }
                        }
                    } catch (e: SecurityException) {
                        log("ERROR", "Bluetooth permission check denied: ${e.message}")
                        break
                    }
                }
            }
    }

    private suspend fun handleIncomingRfcomm(socket: BluetoothSocket) {
        activeSockets.add(socket)
        log("INFO", "Bluetooth device connected")
        var inputStream: InputStream? = null
        try {
            inputStream = socket.inputStream
            val sizeBuf = ByteArray(4)
            while (socket.isConnected) {
                // Read 4 bytes length
                var bytesRead = 0
                while (bytesRead < 4) {
                    val read = inputStream.read(sizeBuf, bytesRead, 4 - bytesRead)
                    if (read == -1) throw Exception("Stream closed")
                    bytesRead += read
                }
                val length = ByteBuffer.wrap(sizeBuf).int
                val maxSizeBytes = io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.getMaxBundleSizeBytes(context)
                if (length <= 0 || length > maxSizeBytes) {
                    log("WARN", "Invalid RFCOMM packet length: $length (exceeds max $maxSizeBytes bytes)")
                    break
                }

                val bundleBytes =
                    try {
                        ByteArray(length)
                    } catch (e: OutOfMemoryError) {
                        log("WARN", "Memory allocation failed for RFCOMM packet length: $length")
                        break
                    }
                var bodyRead = 0
                while (bodyRead < length) {
                    val read = inputStream.read(bundleBytes, bodyRead, length - bodyRead)
                    if (read == -1) throw Exception("Stream closed")
                    bodyRead += read
                }
                log("INFO", "Received bundle via RFCOMM ($length bytes)")

                // Responsibility transfer: only acknowledge if bundle was successfully ingested and stored
                val accepted =
                    try {
                        listener?.invoke(bundleBytes) ?: false
                    } catch (e: Exception) {
                        log("ERROR", "Error ingesting received RFCOMM bundle: ${e.message}")
                        false
                    }

                if (accepted) {
                    // Send 1-byte ACK back to client to confirm full receipt and storage before they close
                    try {
                        socket.outputStream.write(1)
                        socket.outputStream.flush()
                    } catch (e: Exception) {
                        // Ignore write failures on acknowledgment
                    }
                } else {
                    log("WARN", "RFCOMM bundle ingestion failed or rejected; withholding ACK")
                }
            }
        } catch (e: Exception) {
            log("INFO", "RFCOMM session finished or failed: ${e.message}")
        } finally {
            activeSockets.remove(socket)
            try {
                inputStream?.close()
            } catch (e: Exception) {
            }
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }

    override suspend fun sendBundles(
        bundles: List<Pair<ByteArray, (suspend () -> Unit)?>>,
        targetAddress: String,
    ): Boolean {
        if (bundles.isEmpty()) return sendBundle(ByteArray(0), targetAddress, null)
        var allSuccess = true
        for ((bytes, onAck) in bundles) {
            val ok = sendBundle(bytes, targetAddress, onAck)
            if (!ok) allSuccess = false
        }
        return allSuccess
    }

    override suspend fun sendBundle(
        bundleBytes: ByteArray,
        targetAddress: String,
        onAcknowledged: (suspend () -> Unit)?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                log("WARN", "Bluetooth is not supported or currently disabled")
                return@withContext false
            }
            var socket: BluetoothSocket? = null
            var outputStream: OutputStream? = null
            try {
                val device = bluetoothAdapter.getRemoteDevice(targetAddress)
                log("INFO", "Attempting RFCOMM connection to ${device.name ?: targetAddress}")
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()

                outputStream = socket.outputStream
                val sizeBuf = ByteBuffer.allocate(4).putInt(bundleBytes.size).array()
                outputStream.write(sizeBuf)
                outputStream.write(bundleBytes)
                outputStream.flush()

                // Wait for 1-byte ACK from receiver to verify full reception before closing the connection.
                // A 5-second timeout ensures backward-compatibility with older nodes that don't send an ACK.
                try {
                    val startWait = System.currentTimeMillis()
                    val inputStream = socket.inputStream
                    while (inputStream.available() == 0 && (System.currentTimeMillis() - startWait) < 5000) {
                        delay(100)
                    }
                    if (inputStream.available() > 0) {
                        inputStream.read()
                    }
                    try {
                        onAcknowledged?.invoke()
                    } catch (e: Exception) {
                        log("ERROR", "Error executing onAcknowledged callback: ${e.message}")
                    }
                } catch (e: Exception) {
                    // Proceed to close even if ACK read fails/times out
                }

                log("INFO", "Successfully sent bundle of size ${bundleBytes.size} to $targetAddress")
                return@withContext true
            } catch (e: SecurityException) {
                log("ERROR", "Bluetooth permission denied for outbound connection: ${e.message}")
                return@withContext false
            } catch (e: Exception) {
                log("ERROR", "Failed to send bundle to $targetAddress: ${e.message}")
                return@withContext false
            } finally {
                try {
                    outputStream?.close()
                } catch (e: Exception) {
                }
                try {
                    socket?.close()
                } catch (e: Exception) {
                }
            }
        }

    override fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        synchronized(activeSockets) {
            for (socket in activeSockets) {
                try {
                    socket.close()
                } catch (e: Exception) {
                }
            }
            activeSockets.clear()
        }
        serverJob?.cancel()
    }
}
