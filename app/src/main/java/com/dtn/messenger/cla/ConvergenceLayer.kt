package com.dtn.messenger.cla

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.dtn.messenger.data.dao.SystemLogDao
import com.dtn.messenger.data.model.SystemLog
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
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
    ): Boolean
}

private const val MAX_EXTENSION_LENGTH = 65536

class TcpClAdapter(
    private val context: Context,
    private val port: Int = 5051,
    private val logDao: SystemLogDao,
) : ConvergenceLayerAdapter {
    override val name = "TCPCLv4"
    private var serverJob: Job? = null
    private var selectorManager: ActorSelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var listener: (suspend (ByteArray) -> Boolean)? = null

    private var adapterScope: CoroutineScope? = null

    private fun log(
        level: String,
        msg: String,
    ) {
        adapterScope?.launch(Dispatchers.IO) {
            logDao.insert(SystemLog(timestamp = System.currentTimeMillis(), level = level, message = "[TCPCL] $msg"))
        }
    }

    private fun getLocalNodeName(): String {
        val prefs = com.dtn.messenger.util.PreferencesHelper.getEncryptedSharedPreferences(context)
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
        buf[0] = 7 // SESS_INIT
        buf[1] = 0 // Keepalive MSB
        buf[2] = 30 // Keepalive LSB
        val mruVal = com.dtn.messenger.util.PreferencesHelper.getMaxBundleSizeBytes(context)
        java.nio.ByteBuffer.wrap(buf, 3, 8).putLong(mruVal)
        java.nio.ByteBuffer.wrap(buf, 11, 8).putLong(mruVal)
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
        selectorManager = ActorSelectorManager(Dispatchers.IO)
        serverJob =
            scope.launch(Dispatchers.IO) {
                try {
                    serverSocket = aSocket(selectorManager!!).tcp().bind("0.0.0.0", port)
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
                if (sessInitType != 7) {
                    throw Exception("Expected SESS_INIT from client, got type $sessInitType")
                }
                val staticBody = ByteArray(20)
                input.readFully(staticBody, 0, 20)
                val nodeIdLen = java.nio.ByteBuffer.wrap(staticBody, 18, 2).short.toInt() and 0xFFFF
                if (nodeIdLen > 0) {
                    val nodeIdBytes = ByteArray(nodeIdLen)
                    input.readFully(nodeIdBytes, 0, nodeIdLen)
                }
                val extLenBytes = ByteArray(4)
                input.readFully(extLenBytes, 0, 4)
                val extLen = java.nio.ByteBuffer.wrap(extLenBytes).int
                if (extLen < 0 || extLen > MAX_EXTENSION_LENGTH) {
                    throw Exception("Invalid SESS_INIT extension length: $extLen (exceeds max $MAX_EXTENSION_LENGTH)")
                }
                if (extLen > 0) {
                    val extBytes = ByteArray(extLen)
                    input.readFully(extBytes, 0, extLen)
                }

                // Send SESS_INIT back
                val serverSessInit = buildSessInit(getLocalNodeName())
                output.writeFully(serverSessInit, 0, serverSessInit.size)
            }

            // Message transmission phase
            while (socket.isActive) {
                val msgType = withTimeout(45000) { input.readByte().toInt() }
                when (msgType) {
                    1 -> { // XFER_SEGMENT
                        withTimeout(15000) {
                            val flags = input.readByte().toInt()
                            val transferId = input.readLong()

                            // Parse extension length if START is set (bit 0)
                            if ((flags and 1) != 0) {
                                val extByteLen = input.readInt()
                                if (extByteLen < 0 || extByteLen > MAX_EXTENSION_LENGTH) {
                                    throw Exception("Invalid XFER_SEGMENT extension length: $extByteLen (exceeds max $MAX_EXTENSION_LENGTH)")
                                }
                                if (extByteLen > 0) {
                                    val extBytes = ByteArray(extByteLen)
                                    input.readFully(extBytes, 0, extByteLen)
                                }
                            }

                            val length = input.readLong().toInt()
                            val maxSizeBytes = com.dtn.messenger.util.PreferencesHelper.getMaxBundleSizeBytes(context)
                            if (length <= 0 || length > maxSizeBytes) {
                                throw Exception("Invalid segment length: $length (exceeds max $maxSizeBytes bytes)")
                            }
                            val bundleBytes =
                                try {
                                    ByteArray(length)
                                } catch (e: OutOfMemoryError) {
                                    throw Exception("Memory allocation failed for segment of size $length bytes")
                                }
                            input.readFully(bundleBytes, 0, length)
                            log("INFO", "Received bundle ($length bytes)")

                            // Responsibility transfer: only acknowledge if bundle was successfully ingested and stored
                            val accepted =
                                try {
                                    listener?.invoke(bundleBytes) ?: false
                                } catch (e: Exception) {
                                    log("ERROR", "Error ingesting received bundle: ${e.message}")
                                    false
                                }

                            if (accepted) {
                                // Send XFER_ACK (type 2)
                                val ackMsg = ByteArray(18)
                                ackMsg[0] = 2 // XFER_ACK
                                ackMsg[1] = flags.toByte()
                                java.nio.ByteBuffer.wrap(ackMsg, 2, 8).putLong(transferId)
                                java.nio.ByteBuffer.wrap(ackMsg, 10, 8).putLong(length.toLong())
                                output.writeFully(ackMsg, 0, 18)
                            } else {
                                log("WARN", "Bundle ingestion failed or rejected; withholding XFER_ACK for transfer $transferId")
                            }
                        }
                    }
                    4 -> { // KEEPALIVE
                        // Ignore
                    }
                    5 -> { // SESS_TERM
                        withTimeout(5000) {
                            input.readByte()
                            val termReason = input.readByte()
                            log("INFO", "Received session term from client (reason $termReason)")
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
            socket.close()
        }
    }

    override suspend fun sendBundle(
        bundleBytes: ByteArray,
        targetAddress: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val parts = targetAddress.split(":")
            val host = parts[0]
            val destPort = parts.getOrNull(1)?.toIntOrNull() ?: port

            // Do not attempt internet connections if no network transport (wifi/cellular) is active.
            // We bypass the check for local/loopback hostnames for local testing (emulator).
            if (host != "127.0.0.1" && host != "localhost" && host != "10.0.2.2" && !isNetworkAvailable()) {
                log("WARN", "Network unavailable, aborting connection attempt to $host")
                return@withContext false
            }

            var socket: Socket? = null
            try {
                log("INFO", "Connecting to $host:$destPort")
                val sel = ActorSelectorManager(Dispatchers.IO)
                socket =
                    withTimeout(10000) {
                        aSocket(sel).tcp().connect(host, destPort)
                    }

                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = true)

                // Handshake phase
                withTimeout(15000) {
                    // 1. Send contact header (6 bytes in TCPCLv4)
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

                    // Read contact header response
                    val serverHeader = ByteArray(6)
                    input.readFully(serverHeader, 0, 6)

                    if (serverHeader[0] != 'd'.code.toByte() || serverHeader[1] != 't'.code.toByte() ||
                        serverHeader[2] != 'n'.code.toByte() || serverHeader[3] != '!'.code.toByte() ||
                        serverHeader[4] != 4.toByte()
                    ) {
                        throw Exception("Invalid contact header version from server")
                    }

                    // 2. Exchange SESS_INIT
                    // Send SESS_INIT
                    val sessInit = buildSessInit(getLocalNodeName())
                    output.writeFully(sessInit, 0, sessInit.size)

                    // Read SESS_INIT back from server
                    val sessInitType = input.readByte().toInt()
                    if (sessInitType != 7) {
                        throw Exception("Expected SESS_INIT from server, got type $sessInitType")
                    }
                    val staticBody = ByteArray(20)
                    input.readFully(staticBody, 0, 20)
                    val nodeIdLen = java.nio.ByteBuffer.wrap(staticBody, 18, 2).short.toInt() and 0xFFFF
                    if (nodeIdLen > 0) {
                        val nodeIdBytes = ByteArray(nodeIdLen)
                        input.readFully(nodeIdBytes, 0, nodeIdLen)
                    }
                    val extLenBytes = ByteArray(4)
                    input.readFully(extLenBytes, 0, 4)
                    val extLen = java.nio.ByteBuffer.wrap(extLenBytes).int
                    if (extLen < 0 || extLen > MAX_EXTENSION_LENGTH) {
                        throw Exception("Invalid SESS_INIT extension length from server: $extLen (exceeds max $MAX_EXTENSION_LENGTH)")
                    }
                    if (extLen > 0) {
                        val extBytes = ByteArray(extLen)
                        input.readFully(extBytes, 0, extLen)
                    }
                }

                // 3. Send XFER_SEGMENT if we have bytes to send (START and END flags set = 3)
                if (bundleBytes.isNotEmpty()) {
                    withTimeout(20000) {
                        val xferHeader = ByteArray(22)
                        xferHeader[0] = 1 // MSG_XFER_SEGMENT
                        xferHeader[1] = 3 // START (1) | END (2)
                        java.nio.ByteBuffer.wrap(xferHeader, 2, 8).putLong(1L) // Transfer ID
                        xferHeader[10] = 0 // Extensions length
                        xferHeader[11] = 0
                        xferHeader[12] = 0
                        xferHeader[13] = 0
                        java.nio.ByteBuffer.wrap(xferHeader, 14, 8).putLong(bundleBytes.size.toLong()) // Data length

                        output.writeFully(xferHeader, 0, 22)
                        output.writeFully(bundleBytes, 0, bundleBytes.size)

                        // Wait for XFER_ACK
                        val ackType = input.readByte().toInt()
                        if (ackType == 2) {
                            val ackBody = ByteArray(17)
                            input.readFully(ackBody, 0, 17)
                            val ackLen = java.nio.ByteBuffer.wrap(ackBody, 9, 8).long
                            log("INFO", "Successfully sent bundle to $targetAddress, acknowledged $ackLen bytes")
                        } else {
                            throw Exception("Expected XFER_ACK (2), got type $ackType")
                        }
                    }
                }

                // 4. Enter read loop to receive bundles from server (pull-based transfer)
                log("INFO", "Entering client receive loop to pull bundles from $targetAddress...")
                while (socket.isActive) {
                    val msgType =
                        try {
                            // 3s quiescence timeout: if server has no further bundles, close gracefully and return promptly
                            withTimeout(3000) { input.readByte().toInt() }
                        } catch (e: java.io.EOFException) {
                            break
                        } catch (e: Exception) {
                            break
                        }
                    when (msgType) {
                        1 -> { // XFER_SEGMENT (Server sending to client)
                            withTimeout(15000) {
                                val flags = input.readByte().toInt()
                                val transferId = input.readLong()
                                if ((flags and 1) != 0) { // START
                                    val extByteLen = input.readInt()
                                    if (extByteLen > 0) {
                                        val extBytes = ByteArray(extByteLen)
                                        input.readFully(extBytes, 0, extByteLen)
                                    }
                                }
                                val length = input.readLong().toInt()
                                val maxSizeBytes = com.dtn.messenger.util.PreferencesHelper.getMaxBundleSizeBytes(context)
                                if (length <= 0 || length > maxSizeBytes) {
                                    throw Exception("Invalid segment length from server: $length (exceeds max $maxSizeBytes bytes)")
                                }
                                val rxBundle =
                                    try {
                                        ByteArray(length)
                                    } catch (e: OutOfMemoryError) {
                                        throw Exception("Memory allocation failed for segment of size $length bytes")
                                    }
                                input.readFully(rxBundle, 0, length)
                                log("INFO", "Received bundle from server ($length bytes)")

                                // Responsibility transfer: only acknowledge if bundle was successfully ingested and stored
                                val accepted =
                                    try {
                                        listener?.invoke(rxBundle) ?: false
                                    } catch (e: Exception) {
                                        log("ERROR", "Error ingesting received bundle from server: ${e.message}")
                                        false
                                    }

                                if (accepted) {
                                    // Send XFER_ACK back
                                    val ackMsg = ByteArray(18)
                                    ackMsg[0] = 2 // XFER_ACK
                                    ackMsg[1] = flags.toByte()
                                    java.nio.ByteBuffer.wrap(ackMsg, 2, 8).putLong(transferId)
                                    java.nio.ByteBuffer.wrap(ackMsg, 10, 8).putLong(length.toLong())
                                    output.writeFully(ackMsg, 0, 18)
                                } else {
                                    log("WARN", "Bundle ingestion failed or rejected; withholding XFER_ACK for transfer $transferId")
                                }
                            }
                        }
                        4 -> { // KEEPALIVE
                            // Ignore
                        }
                        5 -> { // SESS_TERM
                            withTimeout(5000) {
                                input.readByte()
                                val termReason = input.readByte()
                                log("INFO", "Server terminated session (reason $termReason)")
                            }
                            break
                        }
                        else -> {
                            break
                        }
                    }
                }

                // Send polite SESS_TERM
                val sessTerm = byteArrayOf(5, 0, 1) // SESS_TERM, flags = 0, reason = 1 (normal shutdown)
                try {
                    withTimeout(2000) {
                        output.writeFully(sessTerm, 0, 3)
                    }
                } catch (e: Exception) {
                }

                return@withContext true
            } catch (e: Exception) {
                log("ERROR", "Failed to send bundle to $targetAddress: ${e.message}")
                return@withContext false
            } finally {
                socket?.close()
            }
        }

    override fun stop() {
        serverSocket?.close()
        selectorManager?.close()
        serverJob?.cancel()
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
                val maxSizeBytes = com.dtn.messenger.util.PreferencesHelper.getMaxBundleSizeBytes(context)
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

    override suspend fun sendBundle(
        bundleBytes: ByteArray,
        targetAddress: String,
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
