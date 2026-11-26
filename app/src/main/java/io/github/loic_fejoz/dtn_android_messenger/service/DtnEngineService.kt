package io.github.loic_fejoz.dtn_android_messenger.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.Observer
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
import org.koin.android.ext.android.inject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class DtnEngineService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ingestionMutex = Mutex()

    private val localServiceDao: LocalServiceDao by inject()
    private val bundleRecordDao: BundleRecordDao by inject()
    private val routingRuleDao: RoutingRuleDao by inject()
    private val convergenceProfileDao: ConvergenceProfileDao by inject()
    private val bpsecKeyDao: BpsecKeyDao by inject()
    private val logDao: SystemLogDao by inject()
    private val senmlEntryDao: SenmlEntryDao by inject()
    private val outboxRepository: io.github.loic_fejoz.dtn_android_messenger.data.repository.BundleOutboxRepository by inject()
    private val timeProvider: io.github.loic_fejoz.dtn_android_messenger.util.TimeProvider by inject()

    private lateinit var tcpClAdapter: TcpClAdapter
    private lateinit var bluetoothAdapter: BluetoothClassicAdapter
    private lateinit var fileClaAdapter: FileClaAdapter
    private lateinit var outboxDispatcher: OutboxDispatcher
    private val adapters = mutableListOf<ConvergenceLayerAdapter>()

    private var flushJob: Job? = null
    private var cleanupJob: Job? = null
    private var pullJob: Job? = null
    private var fileClaPollJob: Job? = null
    private var periodicFlushJob: Job? = null
    private val lastPullTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastFilePollTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
        ): String = RoutingPolicy.selectNextHop(destination, rules)

        internal fun selectBpsecKey(
            targetEid: String,
            keys: List<BpsecKey>,
        ): BpsecKey? = BpsecProcessor.selectBpsecKey(targetEid, keys)

        internal fun shouldRejectUnsignedBundle(policy: String): Boolean =
            BpsecProcessor.shouldRejectUnsignedBundle(policy)

        internal fun isLocalDestination(
            destinationEid: String,
            localNodeBase: String,
            localServices: List<LocalService>,
        ): Pair<Boolean, List<LocalService>> =
            RoutingPolicy.isLocalDestination(destinationEid, localNodeBase, localServices)

        internal fun buildBibeOuterBundle(
            innerBundleBytes: ByteArray,
            innerBundleId: String,
            bibeGateway: String,
            localNodeBase: String,
            creationTimeMillis: Long,
            sequenceNumber: Long,
            lifetimeMs: Long,
            hopCount: Int,
        ): Bundle =
            BibeCodec.buildBibeOuterBundle(
                innerBundleBytes = innerBundleBytes,
                innerBundleId = innerBundleId,
                bibeGateway = bibeGateway,
                localNodeBase = localNodeBase,
                creationTimeMillis = creationTimeMillis,
                sequenceNumber = sequenceNumber,
                lifetimeMs = lifetimeMs,
                hopCount = hopCount,
            )

        internal fun formatNotificationPreview(
            data: ByteArray,
            filePath: String?,
        ): String = DtnNotificationHelper.formatNotificationPreview(data, filePath)

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

        outboxDispatcher =
            OutboxDispatcher(
                context = this,
                bundleRecordDao = bundleRecordDao,
                routingRuleDao = routingRuleDao,
                convergenceProfileDao = convergenceProfileDao,
                bpsecKeyDao = bpsecKeyDao,
                outboxRepository = outboxRepository,
                timeProvider = timeProvider,
                fileClaAdapter = fileClaAdapter,
                adapters = adapters,
                log = { lvl, msg -> log(lvl, msg) },
                onBundleUpdated = { updateTrigger.emit(Unit) },
            )

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
            val creationTime = Bpv7Parser.fromDtnTimeMillis(primary.creationTimestamp.first)
            val seqNo = primary.creationTimestamp.second
            val duplicate = bundleRecordDao.findDuplicate(primary.source.uri, creationTime, seqNo)
            if (duplicate != null) {
                log("INFO", "Duplicate bundle from ${primary.source.uri} (timestamp=$creationTime, seq=$seqNo) already exists. Discarding.")
                return true // Already safely in local storage
            }

            log("INFO", "Processing received bundle from ${primary.source.uri}")

            // Check BPSec BIB integrity
            val prefs = io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.getEncryptedSharedPreferences(this)
            val policy = prefs.getString("bpsec_policy", "none") ?: "none"
            val verification =
                BpsecProcessor.verifyIncomingBib(
                    bundle = bundle,
                    policy = policy,
                    keys = bpsecKeyDao.getAllList(),
                    log = { lvl, msg -> log(lvl, msg) },
                )
            if (!verification.accepted) {
                return false
            }
            val bpsecStatus = verification.status

            // Save payload to file
            val bundleId = UUID.randomUUID().toString()
            val payloadFile = persistPayloadFile(bundleId, payload.data)

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
                val innerBytesToProcess = BibeCodec.extractInnerBundleBytes(payload.data) { lvl, msg -> log(lvl, msg) }
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
                notifyLocalServices(matchedLocalServices, record, payload.data, creationTime)
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

    private fun persistPayloadFile(
        bundleId: String,
        data: ByteArray,
    ): File {
        val payloadsDir = File(filesDir, "payloads")
        if (!payloadsDir.exists()) payloadsDir.mkdirs()

        val tempFile = File(payloadsDir, "temp_$bundleId.bin")
        FileOutputStream(tempFile).use { fos ->
            fos.write(data)
        }
        val extension = io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils.getPayloadFileExtension(tempFile.absolutePath)
        val payloadFile = File(payloadsDir, "$bundleId.$extension")
        if (!tempFile.renameTo(payloadFile)) {
            tempFile.copyTo(payloadFile, overwrite = true)
            tempFile.delete()
        }
        return payloadFile
    }

    private suspend fun notifyLocalServices(
        matchedLocalServices: List<LocalService>,
        record: BundleRecord,
        payloadData: ByteArray,
        creationTime: Long,
    ) {
        matchedLocalServices.forEach { service ->
            if (service.viewerType == ViewerType.SENML_LAST) {
                processSenmlPayload(service.serviceEid, payloadData, creationTime)
            }
            if (service.isNotificationEnabled) {
                triggerMessageNotification(service, record, payloadData)
            }
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
    ) = DtnNotificationHelper.triggerMessageNotification(this, service, record, data)

    private suspend fun flushQueue(forceProfileId: String? = null): Boolean =
        outboxDispatcher.flushQueue(forceProfileId)

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

    private fun isProfileEligible(profile: ConvergenceProfile): Boolean =
        RoutingPolicy.isProfileEligible(this, profile)

    private suspend fun findProfileForNextHop(
        nextHop: String,
        allowPausedProfileId: String? = null,
    ): ConvergenceProfile? =
        RoutingPolicy.findProfileForNextHop(
            context = this,
            nextHop = nextHop,
            profiles = convergenceProfileDao.getAllList(),
            allowPausedProfileId = allowPausedProfileId,
        )

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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!::outboxDispatcher.isInitialized) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
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

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        log("WARN", "Foreground service timeout reached (fgsType=$fgsType); stopping gracefully")
        stopSelf(startId)
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

    private fun buildForegroundNotification(): Notification =
        DtnNotificationHelper.buildForegroundNotification(this)

    private fun createNotificationChannels() =
        DtnNotificationHelper.createNotificationChannels(this)
}
