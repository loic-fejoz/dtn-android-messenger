package io.github.loic_fejoz.dtn_android_messenger.cla

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.loic_fejoz.dtn_android_messenger.data.dao.SystemLogDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.SystemLog
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID

class BluetoothClassicAdapter(
    private val context: Context,
    private val logDao: SystemLogDao,
) : ConvergenceLayerAdapter {
    override val name = "Bluetooth"
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var serverJob: Job? = null
    private var serverSocket: BluetoothServerSocket? = null
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    private var listener: (suspend (ByteArray) -> Boolean)? = null

    private val activeSockets = Collections.synchronizedList(mutableListOf<BluetoothSocket>())
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
                            delay(15000)
                            continue
                        }
                        wasDisabledLogged = false

                        var serverSock: BluetoothServerSocket? = null
                        try {
                            // In Android 12+, we need BLUETOOTH_CONNECT.
                            serverSock = bluetoothAdapter.listenUsingRfcommWithServiceRecord("DtnMessenger", sppUuid)
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
                            runCatching { serverSock?.close() }
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
                var bytesRead = 0
                while (bytesRead < 4) {
                    val read = inputStream.read(sizeBuf, bytesRead, 4 - bytesRead)
                    if (read == -1) throw IOException("Stream closed")
                    bytesRead += read
                }
                val length = ByteBuffer.wrap(sizeBuf).int
                val maxSizeBytes = PreferencesHelper.getMaxBundleSizeBytes(context)
                if (length <= 0 || length > maxSizeBytes) {
                    log("WARN", "Invalid RFCOMM packet length: $length (exceeds max $maxSizeBytes bytes)")
                    break
                }

                val bundleBytes =
                    try {
                        ByteArray(length)
                    } catch (_: OutOfMemoryError) {
                        log("WARN", "Memory allocation failed for RFCOMM packet length: $length")
                        break
                    }
                var bodyRead = 0
                while (bodyRead < length) {
                    val read = inputStream.read(bundleBytes, bodyRead, length - bodyRead)
                    if (read == -1) throw IOException("Stream closed")
                    bodyRead += read
                }
                log("INFO", "Received bundle via RFCOMM ($length bytes)")

                val accepted =
                    try {
                        listener?.invoke(bundleBytes) ?: false
                    } catch (e: Exception) {
                        log("ERROR", "Error ingesting received RFCOMM bundle: ${e.message}")
                        false
                    }

                if (accepted) {
                    runCatching {
                        socket.outputStream.write(1)
                        socket.outputStream.flush()
                    }
                } else {
                    log("WARN", "RFCOMM bundle ingestion failed or rejected; withholding ACK")
                }
            }
        } catch (e: Exception) {
            log("INFO", "RFCOMM session finished or failed: ${e.message}")
        } finally {
            activeSockets.remove(socket)
            runCatching { inputStream?.close() }
            runCatching { socket.close() }
        }
    }

    override suspend fun sendBundles(
        bundles: List<Pair<ByteArray, (suspend () -> Unit)?>>,
        targetAddress: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val nonEmpty = bundles.filter { it.first.isNotEmpty() }
            if (nonEmpty.isEmpty()) return@withContext true
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                log("WARN", "Bluetooth is not supported or currently disabled")
                return@withContext false
            }

            var socket: BluetoothSocket? = null
            var outputStream: OutputStream? = null
            var allSuccess = true
            try {
                val device = bluetoothAdapter.getRemoteDevice(targetAddress)
                log("INFO", "Attempting RFCOMM connection to ${device.name ?: targetAddress} for ${nonEmpty.size} bundle(s)")
                socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket.connect()

                outputStream = socket.outputStream
                val inputStream = socket.inputStream

                for ((bundleBytes, onAcknowledged) in nonEmpty) {
                    val sizeBuf = ByteBuffer.allocate(4).putInt(bundleBytes.size).array()
                    outputStream.write(sizeBuf)
                    outputStream.write(bundleBytes)
                    outputStream.flush()

                    val activeSocket = socket
                    val timeoutJob =
                        launch {
                            delay(5000)
                            runCatching { activeSocket.close() }
                        }
                    val ackByte =
                        try {
                            inputStream.read()
                        } catch (_: Exception) {
                            -1
                        } finally {
                            timeoutJob.cancel()
                        }

                    if (ackByte != 1) {
                        log("WARN", "No valid RFCOMM ACK received from $targetAddress (got $ackByte); withholding delivery confirmation")
                        allSuccess = false
                        break
                    }

                    try {
                        onAcknowledged?.invoke()
                    } catch (e: Exception) {
                        log("ERROR", "Error executing onAcknowledged callback: ${e.message}")
                    }
                    log("INFO", "Successfully sent and acknowledged bundle of size ${bundleBytes.size} to $targetAddress")
                }
                allSuccess
            } catch (e: SecurityException) {
                log("ERROR", "Bluetooth permission denied for outbound connection: ${e.message}")
                false
            } catch (e: Exception) {
                log("ERROR", "Failed to send bundle(s) to $targetAddress: ${e.message}")
                false
            } finally {
                runCatching { outputStream?.close() }
                runCatching { socket?.close() }
            }
        }

    override suspend fun sendBundle(
        bundleBytes: ByteArray,
        targetAddress: String,
        onAcknowledged: (suspend () -> Unit)?,
    ): Boolean {
        if (bundleBytes.isEmpty()) return true
        return sendBundles(listOf(Pair(bundleBytes, onAcknowledged)), targetAddress)
    }

    override fun stop() {
        runCatching { serverSocket?.close() }
        synchronized(activeSockets) {
            for (socket in activeSockets) {
                runCatching { socket.close() }
            }
            activeSockets.clear()
        }
        serverJob?.cancel()
    }
}
