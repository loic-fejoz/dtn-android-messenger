package io.github.loic_fejoz.dtn_android_messenger.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import io.github.loic_fejoz.dtn_android_messenger.R
import io.github.loic_fejoz.dtn_android_messenger.cla.BluetoothClassicAdapter
import io.github.loic_fejoz.dtn_android_messenger.cla.ConvergenceLayerAdapter
import io.github.loic_fejoz.dtn_android_messenger.cla.FileClaAdapter
import io.github.loic_fejoz.dtn_android_messenger.cla.TcpClAdapter
import io.github.loic_fejoz.dtn_android_messenger.data.dao.*
import io.github.loic_fejoz.dtn_android_messenger.data.model.*
import io.github.loic_fejoz.dtn_android_messenger.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.koin.android.ext.android.inject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class DtnEngineService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()
    private val ingestionMutex = Mutex()

    private val localServiceDao: LocalServiceDao by inject()
    private val bundleRecordDao: BundleRecordDao by inject()
    private val routingRuleDao: RoutingRuleDao by inject()
    private val convergenceProfileDao: ConvergenceProfileDao by inject()
    private val bpsecKeyDao: BpsecKeyDao by inject()
    private val logDao: SystemLogDao by inject()
    private val senmlEntryDao: SenmlEntryDao by inject()
    private val timeProvider: io.github.loic_fejoz.dtn_android_messenger.util.TimeProvider by inject()

    private lateinit var tcpClAdapter: TcpClAdapter
    private lateinit var bluetoothAdapter: BluetoothClassicAdapter
    private lateinit var fileClaAdapter: FileClaAdapter
    private val adapters = mutableListOf<ConvergenceLayerAdapter>()

    private var flushJob: Job? = null
    private var cleanupJob: Job? = null
    private var pullJob: Job? = null
    private var fileClaPollJob: Job? = null
    private var periodicFlushJob: Job? = null
    private val lastPullTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastFilePollTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val bibeSeqCounter = java.util.concurrent.atomic.AtomicLong(1L)

    private val isAndroidAutoActiveFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var carConnectionTypeLiveData: androidx.lifecycle.LiveData<Int>? = null
    private val carConnectionObserver =
        Observer<Int> { connectionState ->
            val active = (
                connectionState == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION ||
                    connectionState == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NATIVE
            )
            if (active != isAndroidAutoActiveFlow.value) {
                isAndroidAutoActiveFlow.value = active
                log(
                    "INFO",
                    if (active) "Android Auto connected, temporarily pausing Bluetooth CLA" else "Android Auto disconnected, restoring Bluetooth CLA",
                )
            }
        }

    companion object {
        const val CHANNEL_ID = "dtn_engine_channel"
        const val CHAT_CHANNEL_ID = "dtn_chat_channel"
        const val NOTIFICATION_ID = 101

        // Static flow or triggers to notify UI of updates
        val updateTrigger = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1)
        val isTcpActive = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isBluetoothActive = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isFileClaActive = kotlinx.coroutines.flow.MutableStateFlow(false)
        private const val MAX_BIBE_DEPTH = 3

        internal fun selectNextHop(
            destination: String,
            rules: List<RoutingRule>,
        ): String {
            val sortedRules =
                rules.sortedByDescending {
                    it.destinationEidPattern.replace("*", "").trimEnd('/').length
                }
            for (rule in sortedRules) {
                val pattern = rule.destinationEidPattern.replace("*", "").trimEnd('/')
                if (io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isPrefixMatch(pattern, destination)) {
                    return rule.nextHopEid
                }
            }
            return destination
        }

        internal fun selectBpsecKey(
            targetEid: String,
            keys: List<BpsecKey>,
        ): BpsecKey? {
            if (!io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isValidEid(targetEid)) return null
            return keys
                .filter { key ->
                    io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isValidEid(key.nodeEid) &&
                        io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isPrefixMatch(key.nodeEid, targetEid)
                }
                .maxByOrNull { it.nodeEid.trim().trimEnd('/').length }
        }

        internal fun shouldRejectUnsignedBundle(policy: String): Boolean = policy == "strict"

        internal fun isLocalDestination(
            destinationEid: String,
            localNodeBase: String,
            localServices: List<LocalService>,
        ): Pair<Boolean, List<LocalService>> {
            if (!io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isValidEid(destinationEid)) {
                return Pair(false, emptyList())
            }
            val isLocalNode =
                io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isValidEid(localNodeBase) &&
                    io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isPrefixMatch(localNodeBase, destinationEid)
            val matchedLocalServices =
                localServices.filter { service ->
                    io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isValidEid(service.serviceEid) &&
                        io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isPrefixMatch(service.serviceEid, destinationEid)
                }
            return Pair(isLocalNode || matchedLocalServices.isNotEmpty(), matchedLocalServices)
        }

        internal fun buildBibeOuterBundle(
            innerBundleBytes: ByteArray,
            innerBundleId: String,
            bibeGateway: String,
            localNodeBase: String,
            creationTimeMillis: Long,
            sequenceNumber: Long,
            lifetimeMs: Long,
            hopCount: Int,
        ): Bundle {
            val pduContent =
                com.upokecenter.cbor.CBORObject.NewArray().apply {
                    Add(innerBundleId.hashCode() and 0x7FFFFFFF) // transmission-id
                    Add(0) // retransmission-time
                    Add(innerBundleBytes) // encapsulated-bundle
                }
            val pdu =
                com.upokecenter.cbor.CBORObject.NewArray().apply {
                    Add(64443)
                    Add(pduContent)
                }
            val outerPayloadBytes = pdu.EncodeToBytes()

            val cleanGateway = bibeGateway.trim().trimEnd('/')
            val gatewayBibeEid = if (cleanGateway.endsWith("/bibe")) cleanGateway else "$cleanGateway/bibe"
            val localBibeEid = "${localNodeBase.trimEnd('/')}/bibe"

            val outerPrimary =
                PrimaryBlock(
                    bundleControlFlags = PrimaryBlock.FLAG_ADMIN_RECORD,
                    destination = Eid(gatewayBibeEid),
                    source = Eid(localBibeEid),
                    reportTo = Eid(localBibeEid),
                    creationTimestamp = Pair(creationTimeMillis - 946684800000L, sequenceNumber),
                    lifetimeMs = lifetimeMs,
                )
            val outerPayload = PayloadBlock(data = outerPayloadBytes)
            val outerHopCount = HopCountBlock(hopLimit = 64, hopCount = hopCount)
            return Bundle(outerPrimary, outerPayload, outerHopCount, null)
        }

        internal fun formatNotificationPreview(
            data: ByteArray,
            filePath: String?,
        ): String {
            if (filePath != null) {
                if (io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isImagePayload(filePath)) {
                    return "[Image]"
                }
                if (io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.isAudioPayload(filePath)) {
                    return "[Audio]"
                }
            }
            if (data.isEmpty()) return ""
            val maxPrefixBytes = 512
            val prefixLen = data.size.coerceAtMost(maxPrefixBytes)
            var validLen = prefixLen
            if (prefixLen == maxPrefixBytes && data.size > maxPrefixBytes) {
                var i = prefixLen - 1
                while (i >= 0 && (data[i].toInt() and 0xC0) == 0x80) {
                    i--
                }
                if (i >= 0 && (data[i].toInt() and 0x80) != 0) {
                    validLen = i
                }
            }
            for (i in 0 until validLen) {
                val b = data[i].toInt() and 0xFF
                if (b == 0 || b < 0x09 || b in 0x0E..0x1F) {
                    return "[Binary Payload]"
                }
            }
            val decoded =
                try {
                    java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(data, 0, validLen))
                        .toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    return "[Binary Payload]"
                }
            val maxChars = 200
            return if (decoded.length > maxChars || data.size > maxPrefixBytes) {
                decoded.take(maxChars) + "…"
            } else {
                decoded
            }
        }

        @Volatile
        var isRunning = false
    }

    private fun log(
        level: String,
        message: String,
    ) {
        when (level) {
            "ERROR" -> android.util.Log.e("DtnEngineService", message)
            "WARN" -> android.util.Log.w("DtnEngineService", message)
            else -> android.util.Log.i("DtnEngineService", message)
        }
        serviceScope.launch {
            logDao.insert(SystemLog(timestamp = System.currentTimeMillis(), level = level, message = message))
        }
    }

    private suspend fun findBpsecKey(sourceEid: String): BpsecKey? {
        return selectBpsecKey(sourceEid, bpsecKeyDao.getAllList())
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        log("INFO", "DtnEngineService creating")
        createNotificationChannels()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgsType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                val hasBluetoothPermission =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            this,
                            android.Manifest.permission.BLUETOOTH_CONNECT,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasBluetoothPermission) {
                    fgsType = fgsType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(
                    NOTIFICATION_ID,
                    buildForegroundNotification(),
                    fgsType,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildForegroundNotification())
            }
        } catch (e: Exception) {
            log("ERROR", "Failed to start foreground service: ${e.message}")
            stopSelf()
            return
        }

        // Initialize adapters
        tcpClAdapter = TcpClAdapter(context = this, port = 5051, logDao = logDao)
        bluetoothAdapter = BluetoothClassicAdapter(context = this, logDao = logDao)
        fileClaAdapter = FileClaAdapter(context = this, logDao = logDao)

        adapters.add(tcpClAdapter)
        adapters.add(bluetoothAdapter)
        adapters.add(fileClaAdapter)

        // Initialize CarConnection observer
        try {
            val carConnection = androidx.car.app.connection.CarConnection(this)
            carConnectionTypeLiveData = carConnection.type
            carConnectionTypeLiveData?.observeForever(carConnectionObserver)
        } catch (e: Exception) {
            log("WARN", "CarConnection API not available: ${e.message}")
        }

        // Start/Stop adapters reactively based on profile status in db and Android Auto connectivity
        serviceScope.launch {
            combine(
                convergenceProfileDao.getAll(),
                isAndroidAutoActiveFlow,
            ) { profiles, autoActive ->
                Pair(profiles, autoActive)
            }.collect { (profiles, autoActive) ->
                val bluetoothEnabled = profiles.any { it.triggerType == TriggerType.BLUETOOTH_ALWAYS && !it.isPaused } && !autoActive
                val tcpEnabled =
                    profiles.any {
                        !it.isPaused &&
                            (
                                it.triggerType == TriggerType.PERIODIC_INTERNET ||
                                    (it.triggerType == TriggerType.WIFI_SSID && isProfileEligible(it))
                            )
                    }

                val fileClaEnabled = profiles.any { it.triggerType == TriggerType.FILE_CLA && !it.isPaused }
                isFileClaActive.value = fileClaEnabled

                // Manage Bluetooth Adapter
                if (bluetoothEnabled) {
                    if (!isBluetoothActive.value) {
                        log("INFO", "Starting Bluetooth Adapter")
                        bluetoothAdapter.start(serviceScope) { bundleBytes ->
                            onBundleReceived(bundleBytes)
                        }
                        isBluetoothActive.value = true
                    }
                } else {
                    if (isBluetoothActive.value) {
                        log("INFO", "Stopping Bluetooth Adapter")
                        bluetoothAdapter.stop()
                        isBluetoothActive.value = false
                    }
                }

                // Manage TCPCLv4 Adapter
                if (tcpEnabled) {
                    if (!isTcpActive.value) {
                        log("INFO", "Starting TCPCLv4 Adapter")
                        tcpClAdapter.start(serviceScope) { bundleBytes ->
                            onBundleReceived(bundleBytes)
                        }
                        isTcpActive.value = true
                    }
                } else {
                    if (isTcpActive.value) {
                        log("INFO", "Stopping TCPCLv4 Adapter")
                        tcpClAdapter.stop()
                        isTcpActive.value = false
                    }
                }
            }
        }

        // Schedule periodic tasks
        startPeriodicTasks()
    }

    private fun startPeriodicTasks() {
        // Run initial queue flush on startup after settling
        flushJob =
            serviceScope.launch {
                delay(2000)
                try {
                    flushQueue()
                } catch (e: Exception) {
                    log("WARN", "Initial queue flush error: ${e.message}")
                }
            }

        // Reactive, battery-efficient File-CLA RX polling scheduler
        fileClaPollJob =
            serviceScope.launch {
                convergenceProfileDao.getAll()
                    .map { profiles ->
                        lastFilePollTimes.keys.retainAll(profiles.mapTo(hashSetOf()) { it.profileId })
                        profiles.filter { it.triggerType == TriggerType.FILE_CLA && !it.isPaused }
                    }
                    .distinctUntilChanged()
                    .collectLatest { activeFileProfiles ->
                        if (activeFileProfiles.isEmpty()) return@collectLatest
                        while (isActive) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            var minRemainingMs = Long.MAX_VALUE
                            for (profile in activeFileProfiles) {
                                val intervalSec =
                                    (profile.triggerCondition?.trim()?.toLongOrNull() ?: FileClaAdapter.DEFAULT_POLL_INTERVAL_SECONDS)
                                        .coerceAtLeast(FileClaAdapter.MIN_POLL_INTERVAL_SECONDS)
                                val intervalMs = intervalSec * 1000L
                                val lastPoll = lastFilePollTimes[profile.profileId]
                                val elapsed = if (lastPoll == null) intervalMs else now - lastPoll
                                if (elapsed < 0 || elapsed >= intervalMs) {
                                    lastFilePollTimes[profile.profileId] = now
                                    scanFileClaProfile(profile, contextLabel = "RX poll")
                                    if (intervalMs < minRemainingMs) minRemainingMs = intervalMs
                                } else {
                                    val remaining = intervalMs - elapsed
                                    if (remaining < minRemainingMs) minRemainingMs = remaining
                                }
                            }
                            val sleepMs = if (minRemainingMs == Long.MAX_VALUE) 60_000L else minRemainingMs.coerceAtLeast(1000L)
                            delay(sleepMs)
                        }
                    }
            }

        // Periodic Internet Pull scheduler: checks profile configurations every 30 seconds
        pullJob =
            serviceScope.launch {
                while (isActive) {
                    try {
                        val profiles = convergenceProfileDao.getAllList()
                        val currentTime = System.currentTimeMillis()
                        for (profile in profiles) {
                            if (profile.isPaused) continue
                            if (profile.triggerType == TriggerType.PERIODIC_INTERNET) {
                                val intervalMins = profile.triggerCondition?.toIntOrNull() ?: 15
                                val intervalMs = intervalMins * 60 * 1000L
                                val lastPull = lastPullTimes[profile.profileId] ?: 0L
                                if (currentTime - lastPull >= intervalMs) {
                                    val adapter = adapters.find { it.name.lowercase() == "tcpclv4" }
                                    if (adapter != null) {
                                        log("INFO", "Performing configured periodic pull connection to ${profile.targetAddress} (interval: ${intervalMins}m)")
                                        lastPullTimes[profile.profileId] = currentTime
                                        launch {
                                            adapter.sendBundle(ByteArray(0), profile.targetAddress)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log("WARN", "Periodic pull scheduler error: ${e.message}")
                    }
                    delay(30000)
                }
            }

        // Cleanup task: runs every 15 minutes (900,000 ms) to purge expired bundles
        cleanupJob =
            serviceScope.launch {
                while (isActive) {
                    delay(900000)
                    try {
                        cleanupExpiredBundles()
                    } catch (e: Exception) {
                        log("WARN", "Cleanup task error: ${e.message}")
                    }
                }
            }

        // Periodic Queue Flush: checks for pending outgoing messages with exponential backoff
        periodicFlushJob =
            serviceScope.launch {
                var currentDelay = 30000L
                val minDelay = 30000L
                val maxDelay = 300000L // 5 minutes

                while (isActive) {
                    delay(currentDelay)
                    try {
                        val outbox = bundleRecordDao.getByState(BundleState.OUTBOX)
                        val transit = bundleRecordDao.getByState(BundleState.TRANSIT)
                        if (outbox.isNotEmpty() || transit.isNotEmpty()) {
                            log("INFO", "Periodic queue flush: found ${outbox.size + transit.size} pending bundle(s), attempting transmission...")
                            val success = flushQueue()
                            if (success) {
                                currentDelay = minDelay // Reset delay on success
                            } else {
                                currentDelay = (currentDelay * 2).coerceAtMost(maxDelay) // Exponential backoff on failure
                                log("INFO", "Transmission failed. Backing off retry delay to ${currentDelay / 1000}s")
                            }
                        } else {
                            currentDelay = minDelay // Reset delay if outbox is empty
                        }
                    } catch (e: Exception) {
                        log("WARN", "Periodic queue flush error: ${e.message}")
                    }
                }
            }
    }

    private suspend fun onBundleReceived(
        bundleBytes: ByteArray,
        depth: Int = 0,
    ): Boolean {
        if (depth > MAX_BIBE_DEPTH) {
            log("ERROR", "Exceeded maximum BIBE encapsulation depth ($MAX_BIBE_DEPTH). Rejecting bundle.")
            return false
        }
        try {
            val bundle = Bpv7Parser.deserialize(bundleBytes)
            val primary = bundle.primaryBlock
            val payload = bundle.payloadBlock

            // Hop limit check
            val hopLimit = bundle.hopCountBlock?.hopLimit ?: 64
            val hopCount = bundle.hopCountBlock?.hopCount ?: 0
            val isHopLimitExceeded = hopCount >= hopLimit
            if (isHopLimitExceeded) {
                log("WARN", "Received bundle from ${primary.source.uri} reached/exceeded hop limit ($hopCount/$hopLimit). Storing locally without forwarding.")
            }

            // Duplicate check
            val creationTime = systemTimeFromDtn(primary.creationTimestamp.first)
            val seqNo = primary.creationTimestamp.second
            val duplicate = bundleRecordDao.findDuplicate(primary.source.uri, creationTime, seqNo)
            if (duplicate != null) {
                log("INFO", "Duplicate bundle from ${primary.source.uri} (timestamp=$creationTime, seq=$seqNo) already exists. Discarding.")
                return true // Already safely in local storage
            }

            log("INFO", "Processing received bundle from ${primary.source.uri}")

            // Check BPSec BIB integrity
            var bpsecStatus = BpsecStatus.UNVERIFIED
            val bib = bundle.bibBlock
            val prefs = io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.getEncryptedSharedPreferences(this)
            val policy = prefs.getString("bpsec_policy", "none") ?: "none"

            if (bib != null) {
                val isSupportedBib =
                    bib.securityContext == 1 &&
                        bib.variant == 5 &&
                        bib.targets.contains(payload.blockNumber)
                if (!isSupportedBib) {
                    log(
                        "WARN",
                        "Unsupported BIB parameters (context=${bib.securityContext}, variant=${bib.variant}, targets=${bib.targets}) from ${primary.source.uri}",
                    )
                    if (policy == "strict") {
                        log("ERROR", "Unsupported BIB block under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                        return false
                    }
                } else {
                    val keyRecord = findBpsecKey(bib.securitySource.uri) ?: findBpsecKey(primary.source.uri)
                    if (keyRecord != null) {
                        val decryptedKey =
                            try {
                                io.github.loic_fejoz.dtn_android_messenger.util.CryptoManager.decrypt(keyRecord.secretKey)
                            } catch (e: Exception) {
                                log("ERROR", "Failed to decrypt BPSec key for ${primary.source.uri}")
                                return false
                            }
                        // Compute expected signature
                        // For calculation, we need raw primary block bytes.
                        val rawPrimaryBytes = primary.rawBytes ?: Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()

                        val computedSignature =
                            try {
                                Bpv7Parser.computeHmac(
                                    secretKey = decryptedKey,
                                    primaryBlockBytes = rawPrimaryBytes,
                                    targetBlockType = 1,
                                    targetBlockNumber = payload.blockNumber,
                                    targetBlockFlags = payload.blockControlFlags,
                                    securityBlockType = 11,
                                    securityBlockNumber = bib.blockNumber,
                                    securityBlockFlags = bib.blockControlFlags,
                                    payloadBytes = payload.data,
                                    scopeFlags = bib.scopeFlags,
                                )
                            } finally {
                                decryptedKey.fill(0)
                            }

                        bpsecStatus =
                            if (java.security.MessageDigest.isEqual(computedSignature, bib.signature)) {
                                BpsecStatus.VALID
                            } else {
                                BpsecStatus.INVALID
                            }
                        log("INFO", "BPSec signature verification: $bpsecStatus")

                        if (bpsecStatus == BpsecStatus.INVALID) {
                            if (policy == "strict") {
                                log("ERROR", "BPSec signature invalid under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                                return false
                            } else if (policy == "warn") {
                                log("WARN", "BPSec signature invalid under WARN policy for bundle from ${primary.source.uri}.")
                            }
                        }
                    } else {
                        log("WARN", "No BPSec key found for source ${bib.securitySource.uri} / ${primary.source.uri}, verification skipped")
                        if (policy == "strict") {
                            log("ERROR", "BPSec key missing under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                            return false
                        }
                    }
                }
            } else {
                if (shouldRejectUnsignedBundle(policy)) {
                    log("ERROR", "BPSec signature missing under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                    return false
                }
                // Check if a key exists for this node EID
                val keyRecord = findBpsecKey(primary.source.uri)
                if (keyRecord != null && policy == "warn") {
                    log("WARN", "BPSec signature missing under WARN policy for bundle from ${primary.source.uri}.")
                }
            }

            // Save payload to file
            val payloadsDir = File(filesDir, "payloads")
            if (!payloadsDir.exists()) payloadsDir.mkdirs()

            val bundleId = UUID.randomUUID().toString()
            val tempFile = File(payloadsDir, "temp_$bundleId.bin")
            FileOutputStream(tempFile).use { fos ->
                fos.write(payload.data)
            }
            val extension = io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.getPayloadFileExtension(tempFile.absolutePath)
            val payloadFile = File(payloadsDir, "$bundleId.$extension")
            if (!tempFile.renameTo(payloadFile)) {
                tempFile.copyTo(payloadFile, overwrite = true)
                tempFile.delete()
            }

            // Verify if it is for local services on our node (supporting prefix/multicast match for multiple services)
            val localNodeBase = (prefs.getString("local_node_name", "dtn://my-node") ?: "dtn://my-node").trim()
            val localServices = localServiceDao.getAllList()
            val (isLocal, matchedLocalServices) =
                isLocalDestination(
                    destinationEid = primary.destination.uri,
                    localNodeBase = localNodeBase,
                    localServices = localServices,
                )

            var isBibeDecapsulated = false
            val isBibeCandidate =
                isLocal &&
                    depth < MAX_BIBE_DEPTH &&
                    (
                        (primary.bundleControlFlags and PrimaryBlock.FLAG_ADMIN_RECORD) != 0L ||
                            primary.destination.uri.trimEnd('/').endsWith("/bibe", ignoreCase = true)
                    )
            if (isBibeCandidate) {
                var innerBytesToProcess: ByteArray? = null
                // 1. Check if payload is a BIBE PDU (Administrative Record 64443)
                try {
                    val cbor = com.upokecenter.cbor.CBORObject.DecodeFromBytes(payload.data)
                    if (cbor.type == com.upokecenter.cbor.CBORType.Array && cbor.size() >= 2) {
                        val recordType = cbor[0].AsInt32()
                        if (recordType == 64443) {
                            val content = cbor[1]
                            if (content.type == com.upokecenter.cbor.CBORType.Array && content.size() >= 3) {
                                innerBytesToProcess = content[2].GetByteString()
                                log("INFO", "Decapsulated BIBE PDU (type 64443). Re-processing inner bundle.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Not a CBOR administrative record
                }

                // 2. Fallback: Check if payload is a raw encapsulated bundle
                if (innerBytesToProcess == null) {
                    try {
                        val innerBundle = Bpv7Parser.deserialize(payload.data)
                        if (innerBundle.primaryBlock.version == 7) {
                            innerBytesToProcess = payload.data
                            log("INFO", "Decapsulated raw Bundle-in-Bundle. Re-processing inner bundle.")
                        }
                    } catch (e: Exception) {
                        // Not a raw bundle
                    }
                }

                if (innerBytesToProcess != null) {
                    val innerAccepted = onBundleReceived(innerBytesToProcess, depth + 1)
                    if (!innerAccepted) {
                        log("ERROR", "Inner BIBE bundle ingestion failed; withholding outer XFER_ACK.")
                        if (payloadFile.exists()) payloadFile.delete()
                        return false
                    }
                    isBibeDecapsulated = true
                }
            }

            // Check if we also need to forward it (multicast/anycast or transit routing)
            val nextHop = resolveNextHop(primary.destination.uri)
            val isMatchedBroadcast = matchedLocalServices.any { it.isBroadcast }
            val shouldForward = !isHopLimitExceeded && findProfileForNextHop(nextHop) != null && (!isLocal || isMatchedBroadcast)

            val record =
                BundleRecord(
                    bundleId = bundleId,
                    destinationEid = primary.destination.uri,
                    sourceEid = primary.source.uri,
                    creationTimestamp = creationTime,
                    sequenceNumber = seqNo,
                    lifetimeMs = primary.lifetimeMs,
                    payloadFilePath = payloadFile.absolutePath,
                    state =
                        if (isBibeDecapsulated) {
                            BundleState.DELIVERED
                        } else if (shouldForward) {
                            BundleState.OUTBOX
                        } else {
                            BundleState.RECEIVED
                        },
                    isRead = isBibeDecapsulated,
                    bpsecStatus = bpsecStatus,
                    hopCount = hopCount,
                    hopLimit = hopLimit,
                )

            val inserted =
                ingestionMutex.withLock {
                    val concurrentDup = bundleRecordDao.findDuplicate(primary.source.uri, creationTime, seqNo)
                    if (concurrentDup != null) {
                        false
                    } else {
                        bundleRecordDao.insertIgnore(record) != -1L
                    }
                }
            if (!inserted) {
                if (payloadFile.exists()) payloadFile.delete()
                log("INFO", "Concurrent duplicate bundle from ${primary.source.uri} (timestamp=$creationTime, seq=$seqNo) discarded.")
                return true
            }
            log("INFO", "Bundle $bundleId inserted. State: ${record.state}")
            updateTrigger.emit(Unit)

            if (isLocal && !isBibeDecapsulated) {
                // Post notification and process payload for matched local services
                matchedLocalServices.forEach { service ->
                    if (service.viewerType == ViewerType.SENML_LAST) {
                        processSenmlPayload(service.serviceEid, payload.data, creationTime)
                    }
                    if (service.isNotificationEnabled) {
                        triggerMessageNotification(service, record, payload.data)
                    }
                }
            }

            if (shouldForward) {
                // Forward immediately
                log("INFO", "Received transit/multicast bundle targeting ${primary.destination.uri}. Forwarding immediately.")
                serviceScope.launch {
                    flushQueue()
                }
            }

            return true
        } catch (e: Exception) {
            log("ERROR", "Error handling received bundle: " + android.util.Log.getStackTraceString(e))
            return false
        }
    }

    private suspend fun processSenmlPayload(
        serviceEid: String,
        data: ByteArray,
        fallbackTimestamp: Long,
    ) {
        try {
            val count = SenmlIngestionHelper.ingest(serviceEid, data, fallbackTimestamp, senmlEntryDao)
            if (count > 0) {
                log("INFO", "Processed $count SenML records for service $serviceEid")
            }
        } catch (e: Exception) {
            log("ERROR", "Error parsing SenML payload: ${e.message}")
        }
    }

    private fun triggerMessageNotification(
        service: LocalService,
        record: BundleRecord,
        data: ByteArray,
    ) {
        val messageText = formatNotificationPreview(data, record.payloadFilePath)

        val intent =
            Intent(this, io.github.loic_fejoz.dtn_android_messenger.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val soundUri = service.notificationSoundUri?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder =
            NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setSound(soundUri)

        if (service.viewerType == ViewerType.CHAT) {
            val user = androidx.core.app.Person.Builder().setName("Me").setKey("me").build()
            val sender = androidx.core.app.Person.Builder().setName(record.sourceEid).setKey(record.sourceEid).build()
            val messagingStyle =
                NotificationCompat.MessagingStyle(user)
                    .addMessage(messageText, record.creationTimestamp, sender)
            builder.setStyle(messagingStyle)

            val replyInput =
                androidx.core.app.RemoteInput.Builder("extra_voice_reply")
                    .setLabel("Reply")
                    .build()

            val replyIntent =
                Intent(this, io.github.loic_fejoz.dtn_android_messenger.receiver.DtnMessageReceiver::class.java).apply {
                    putExtra("dest_eid", service.serviceEid)
                    putExtra("src_eid", record.sourceEid)
                    putExtra("notification_id", record.bundleId.hashCode())
                }
            val replyPendingIntent =
                PendingIntent.getBroadcast(
                    this,
                    record.bundleId.hashCode(),
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )

            val replyAction =
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    "Reply",
                    replyPendingIntent,
                ).addRemoteInput(replyInput)
                    .build()

            builder.addAction(replyAction)
        } else {
            builder.setContentTitle(service.displayName)
            builder.setContentText("${record.sourceEid}: $messageText")
        }

        // Parse custom vibration pattern if available
        service.vibrationPatternJson?.let { patternStr ->
            try {
                val json = JSONArray(patternStr)
                val pattern = LongArray(json.length())
                for (i in 0 until json.length()) {
                    pattern[i] = json.getLong(i)
                }
                builder.setVibrate(pattern)
            } catch (e: Exception) {
                builder.setDefaults(Notification.DEFAULT_VIBRATE)
            }
        } ?: builder.setDefaults(Notification.DEFAULT_VIBRATE)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(record.bundleId.hashCode(), builder.build())
    }

    private suspend fun flushQueue(forceProfileId: String? = null): Boolean =
        flushMutex.withLock {
            val outbox = bundleRecordDao.getByState(BundleState.OUTBOX)
            val transit = bundleRecordDao.getByState(BundleState.TRANSIT)
            val allOutgoing = outbox + transit

            if (allOutgoing.isEmpty()) {
                if (forceProfileId != null) {
                    return@withLock false
                }
                val profiles = convergenceProfileDao.getAllList()
                for (profile in profiles) {
                    if (profile.isPaused) continue
                    if (profile.triggerType == TriggerType.PERIODIC_INTERNET) {
                        val adapter = adapters.find { it.name.lowercase() == "tcpclv4" }
                        if (adapter != null) {
                            log("INFO", "Performing periodic pull connection to ${profile.targetAddress}")
                            adapter.sendBundle(ByteArray(0), profile.targetAddress)
                        }
                    }
                }
                return@withLock false
            }

            log("INFO", "Flushing queue, ${allOutgoing.size} outgoing bundle(s)")
            var anySuccess = false

            data class OutgoingBatchItem(
                val record: BundleRecord,
                val bundleBytes: ByteArray,
                val profile: ConvergenceProfile,
            )

            val batchItems = mutableListOf<OutgoingBatchItem>()

            for (record in allOutgoing) {
                // Re-verify that record is still pending in case it was updated by a previous step
                val current = bundleRecordDao.getById(record.bundleId)
                if (current == null || (current.state != BundleState.OUTBOX && current.state != BundleState.TRANSIT)) {
                    continue
                }

                val file = File(record.payloadFilePath)
                if (!file.exists()) {
                    log("WARN", "Payload file not found for bundle ${record.bundleId}, removing orphaned record from queue")
                    bundleRecordDao.delete(record)
                    continue
                }
                val maxSizeBytes = io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.getMaxBundleSizeBytes(this@DtnEngineService)
                if (file.length() > maxSizeBytes) {
                    val maxMb = maxSizeBytes / (1024 * 1024)
                    log("WARN", "Bundle ${record.bundleId} payload size (${file.length()} bytes) exceeds maximum configured limit of $maxMb MB. Skipping.")
                    continue
                }
                val payloadBytes = file.readBytes()

                val isExpired = (record.creationTimestamp + record.lifetimeMs) < timeProvider.currentTimeMillis()
                if (isExpired) {
                    log("INFO", "Bundle ${record.bundleId} expired. Deleting payload and record.")
                    try {
                        val fileObj = File(record.payloadFilePath)
                        if (fileObj.exists()) fileObj.delete()
                    } catch (_: SecurityException) {
                    }
                    bundleRecordDao.delete(record)
                    continue
                }

                if (record.hopCount + 1 >= record.hopLimit) {
                    log("WARN", "Bundle ${record.bundleId} exceeded hop limit (${record.hopLimit}) during forwarding. Discarding.")
                    try {
                        val fileObj = File(record.payloadFilePath)
                        if (fileObj.exists()) fileObj.delete()
                    } catch (_: SecurityException) {
                    }
                    bundleRecordDao.delete(record)
                    continue
                }

                // Resolve next hop
                var nextHop = resolveNextHop(record.destinationEid)
                var useBibe = false
                var bibeGateway: String? = null
                if (nextHop.startsWith("bibe:")) {
                    useBibe = true
                    bibeGateway = nextHop.substring(5)
                    nextHop = bibeGateway
                }

                val profile = findProfileForNextHop(nextHop, allowPausedProfileId = forceProfileId)
                if (profile == null) {
                    if (forceProfileId == null) {
                        log("WARN", "No convergence profile found to reach next-hop $nextHop (dest: ${record.destinationEid})")
                    }
                    continue
                }
                if (forceProfileId != null && profile.profileId != forceProfileId) {
                    continue
                }

                // Load BPSec Key if present to sign bundle (peer key or local node signing key)
                val prefs = io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.getEncryptedSharedPreferences(this@DtnEngineService)
                val localNodeBase = (prefs.getString("local_node_name", "dtn://my-node") ?: "dtn://my-node").trim()
                val keyRecord =
                    findBpsecKey(record.destinationEid)
                        ?: findBpsecKey(nextHop)
                        ?: findBpsecKey(record.sourceEid)
                        ?: findBpsecKey(localNodeBase)

                // Build blocks
                val primary =
                    PrimaryBlock(
                        destination = Eid(record.destinationEid),
                        source = Eid(record.sourceEid),
                        reportTo = Eid(record.sourceEid),
                        creationTimestamp = Pair(dtnTimeFromSystem(record.creationTimestamp), record.sequenceNumber),
                        lifetimeMs = record.lifetimeMs,
                    )

                val payload = PayloadBlock(data = payloadBytes)
                val hopCount = HopCountBlock(hopLimit = record.hopLimit, hopCount = record.hopCount + 1)

                var bib: BibBlock? = null
                if (keyRecord != null) {
                    val decryptedKey =
                        try {
                            io.github.loic_fejoz.dtn_android_messenger.util.CryptoManager.decrypt(keyRecord.secretKey)
                        } catch (e: Exception) {
                            log("ERROR", "Failed to decrypt local BPSec key for signature")
                            null
                        }
                    if (decryptedKey != null) {
                        // Compute signature
                        val bibBlockNumber = 2
                        val bibBlockFlags = 0L
                        val rawPrimary = Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()
                        val signature =
                            try {
                                Bpv7Parser.computeHmac(
                                    secretKey = decryptedKey,
                                    primaryBlockBytes = rawPrimary,
                                    targetBlockType = 1,
                                    targetBlockNumber = payload.blockNumber,
                                    targetBlockFlags = payload.blockControlFlags,
                                    securityBlockType = 11,
                                    securityBlockNumber = bibBlockNumber,
                                    securityBlockFlags = bibBlockFlags,
                                    payloadBytes = payloadBytes,
                                    scopeFlags = 7,
                                )
                            } finally {
                                decryptedKey.fill(0)
                            }
                        bib =
                            BibBlock(
                                blockNumber = bibBlockNumber,
                                blockControlFlags = bibBlockFlags,
                                securitySource = Eid(record.sourceEid),
                                signature = signature,
                            )
                        log("INFO", "Signed bundle ${record.bundleId} using key for EID: ${keyRecord.nodeEid}")
                    }
                }

                val bundle = Bundle(primary, payload, hopCount, bib)
                var bundleBytes = Bpv7Parser.serialize(bundle)

                if (useBibe && bibeGateway != null) {
                    log("INFO", "Encapsulating bundle ${record.bundleId} in a BIBE tunnel via gateway $bibeGateway")
                    val outerBundle =
                        buildBibeOuterBundle(
                            innerBundleBytes = bundleBytes,
                            innerBundleId = record.bundleId,
                            bibeGateway = bibeGateway,
                            localNodeBase = localNodeBase,
                            creationTimeMillis = timeProvider.currentTimeMillis(),
                            sequenceNumber = bibeSeqCounter.getAndIncrement(),
                            lifetimeMs = record.lifetimeMs,
                            hopCount = record.hopCount + 1,
                        )
                    bundleBytes = Bpv7Parser.serialize(outerBundle)
                }

                batchItems.add(OutgoingBatchItem(record, bundleBytes, profile))
            }

            // Group by trigger type, target address, and (for FILE_CLA) profileId so separate File-CLAs stay isolated
            val grouped =
                batchItems.groupBy {
                    Triple(
                        it.profile.triggerType,
                        it.profile.targetAddress,
                        if (it.profile.triggerType == TriggerType.FILE_CLA) it.profile.profileId else "",
                    )
                }

            for ((key, items) in grouped) {
                val (triggerType, targetAddress, _) = key
                if (triggerType == TriggerType.FILE_CLA) {
                    val profileName = items.first().profile.name
                    log("INFO", "Writing ${items.size} bundle(s) via FileCLA '$profileName' to TX")
                    val namedBundles =
                        items.map { item ->
                            FileClaAdapter.NamedBundle(
                                bundleId = item.record.bundleId,
                                bundleBytes = item.bundleBytes,
                                onAcknowledged = {
                                    val nextState = BundleState.DELIVERED
                                    bundleRecordDao.updateState(item.record.bundleId, nextState)
                                    log("INFO", "Bundle ${item.record.bundleId} written to FileCLA '$profileName' TX! State updated to $nextState")
                                    anySuccess = true
                                    updateTrigger.emit(Unit)
                                },
                            )
                        }
                    val success = fileClaAdapter.sendNamedBundles(targetAddress, profileName, namedBundles)
                    if (success) {
                        anySuccess = true
                    }
                    continue
                }

                val adapter =
                    adapters.find {
                        it.name.lowercase() == (if (triggerType == TriggerType.BLUETOOTH_ALWAYS) "bluetooth" else "tcpclv4").lowercase()
                    }
                if (adapter == null) {
                    log("ERROR", "No adapter found for profile type $triggerType")
                    continue
                }

                log("INFO", "Transmitting ${items.size} bundle(s) via ${adapter.name} to $targetAddress in single session")
                val bundlesPayload =
                    items.map { item ->
                        Pair<ByteArray, (suspend () -> Unit)?>(item.bundleBytes) {
                            val nextState = BundleState.DELIVERED
                            bundleRecordDao.updateState(item.record.bundleId, nextState)
                            log("INFO", "Bundle ${item.record.bundleId} acknowledged by peer! State updated immediately to $nextState")
                            anySuccess = true
                            updateTrigger.emit(Unit)
                        }
                    }

                val success = adapter.sendBundles(bundlesPayload, targetAddress)
                if (success) {
                    anySuccess = true
                }
            }
            return@withLock anySuccess
        }

    private suspend fun scanFileClaProfile(
        profile: ConvergenceProfile,
        contextLabel: String,
    ): Int {
        return try {
            val ingested =
                fileClaAdapter.scanIncoming(profile.targetAddress, profile.name) { bundleBytes ->
                    onBundleReceived(bundleBytes)
                }
            if (ingested > 0) {
                log("INFO", "File-CLA '${profile.name}' ingested $ingested bundle(s) from RX")
            }
            ingested
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("WARN", "File-CLA '${profile.name}' $contextLabel error: ${e.message}")
            0
        }
    }

    private suspend fun resolveNextHop(destination: String): String {
        val rules = routingRuleDao.getAllList()
        return selectNextHop(destination, rules)
    }

    private fun isProfileEligible(profile: ConvergenceProfile): Boolean {
        if (profile.isPaused) return false
        if (profile.triggerType != TriggerType.WIFI_SSID) return true
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            if (!capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                return false
            }
            val requiredSsid = profile.triggerCondition?.trim().orEmpty()
            if (requiredSsid.isEmpty()) return true
            val currentSsid =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (capabilities.transportInfo as? android.net.wifi.WifiInfo)?.ssid
                } else {
                    @Suppress("DEPRECATION")
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                    @Suppress("DEPRECATION")
                    wm?.connectionInfo?.ssid
                }?.trim('"')
            if (currentSsid.isNullOrEmpty() || currentSsid == "<unknown ssid>") {
                // SSID may be redacted without location permission; Wi-Fi transport is active
                true
            } else {
                currentSsid.equals(requiredSsid, ignoreCase = true)
            }
        } catch (e: Exception) {
            true
        }
    }

    private suspend fun findProfileForNextHop(
        nextHop: String,
        allowPausedProfileId: String? = null,
    ): ConvergenceProfile? {
        val profiles = convergenceProfileDao.getAllList()

        fun isEligible(profile: ConvergenceProfile): Boolean =
            (allowPausedProfileId != null && profile.profileId == allowPausedProfileId) || isProfileEligible(profile)

        // Try direct profile ID or name match
        var match = profiles.find { (it.profileId == nextHop || it.name == nextHop) && isEligible(it) }
        if (match == null) {
            // Check if profile ID matches node prefix correctly
            val nextHopNode =
                if (nextHop.startsWith("dtn://")) {
                    val pathPart = nextHop.substring(6)
                    "dtn://" + pathPart.substringBefore("/")
                } else if (nextHop.startsWith("ipn:")) {
                    "ipn:" + nextHop.substring(4).substringBefore("/")
                } else {
                    nextHop
                }
            match = profiles.find { (it.profileId == nextHopNode || it.name == nextHopNode) && isEligible(it) }
        }
        return match
    }

    private suspend fun cleanupExpiredBundles() {
        val now = timeProvider.currentTimeMillis()
        val expired = bundleRecordDao.getExpired(now)
        if (expired.isNotEmpty()) {
            log("INFO", "Eviction task: cleaning up ${expired.size} expired bundle(s)")
            for (record in expired) {
                val file = File(record.payloadFilePath)
                if (file.exists()) file.delete()
                bundleRecordDao.delete(record)
            }
            updateTrigger.emit(Unit)
        }

        // Auto-prune logs older than 3 days to avoid database disk bloat
        try {
            val logCutoff = now - (3 * 24 * 60 * 60 * 1000L) // 3 days
            logDao.deleteLogsOlderThan(logCutoff)
        } catch (e: Exception) {
            // Keep going if pruning fails
        }
    }

    private fun dtnTimeFromSystem(systemMillis: Long): Long {
        return systemMillis - 946684800000L
    }

    private fun systemTimeFromDtn(dtnMillis: Long): Long {
        return dtnMillis + 946684800000L
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            "FLUSH_QUEUE" -> {
                serviceScope.launch {
                    flushQueue()
                }
            }
            "SYNC_FILE_CLA" -> {
                val profileId = intent.getStringExtra("profile_id")
                if (!profileId.isNullOrBlank()) {
                    serviceScope.launch {
                        val profile =
                            convergenceProfileDao.getAllList()
                                .find { it.profileId == profileId && it.triggerType == TriggerType.FILE_CLA }
                        if (profile != null) {
                            log("INFO", "Manual File-CLA sync triggered for '${profile.name}'")
                            lastFilePollTimes[profile.profileId] = android.os.SystemClock.elapsedRealtime()
                            scanFileClaProfile(profile, contextLabel = "Manual RX sync")
                            try {
                                flushQueue(forceProfileId = profile.profileId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log("WARN", "Manual File-CLA '${profile.name}' TX flush error: ${e.message}")
                            }
                        }
                    }
                }
            }
            "FORCE_PULL" -> {
                val address = intent.getStringExtra("address")
                if (address != null) {
                    serviceScope.launch {
                        log("INFO", "Forced connection triggered to $address.")
                        val matchingProfile =
                            convergenceProfileDao.getAllList()
                                .find { it.targetAddress == address && !it.isPaused }
                        val flushed =
                            try {
                                if (matchingProfile != null) {
                                    flushQueue(forceProfileId = matchingProfile.profileId)
                                } else {
                                    false
                                }
                            } catch (e: Exception) {
                                log("WARN", "Forced queue flush error: ${e.message}")
                                false
                            }
                        if (!flushed) {
                            val adapter = adapters.find { it.name.lowercase().contains("tcp") }
                            if (adapter != null) {
                                adapter.sendBundle(ByteArray(0), address)
                            }
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        log("INFO", "DtnEngineService destroying")
        isRunning = false
        try {
            carConnectionTypeLiveData?.removeObserver(carConnectionObserver)
        } catch (_: Exception) {
        }
        isTcpActive.value = false
        isBluetoothActive.value = false
        isFileClaActive.value = false
        flushJob?.cancel()
        cleanupJob?.cancel()
        pullJob?.cancel()
        fileClaPollJob?.cancel()
        periodicFlushJob?.cancel()
        adapters.forEach { it.stop() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildForegroundNotification(): Notification {
        val channelId = CHANNEL_ID
        val intent =
            Intent(this, io.github.loic_fejoz.dtn_android_messenger.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("DTN Engine Active")
            .setContentText("Delay-Tolerant Node Service is running...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val engineChannel =
                NotificationChannel(
                    CHANNEL_ID,
                    "DTN Background Engine",
                    NotificationManager.IMPORTANCE_LOW,
                )
            val chatChannel =
                NotificationChannel(
                    CHAT_CHANNEL_ID,
                    "DTN Chat Messaging",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    enableLights(true)
                    enableVibration(true)
                }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(engineChannel)
            manager.createNotificationChannel(chatChannel)
        }
    }
}
