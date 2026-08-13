package com.dtn.messenger.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import com.dtn.messenger.R
import com.dtn.messenger.cla.BluetoothClassicAdapter
import com.dtn.messenger.cla.ConvergenceLayerAdapter
import com.dtn.messenger.cla.TcpClAdapter
import com.dtn.messenger.data.dao.*
import com.dtn.messenger.data.model.*
import com.dtn.messenger.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.koin.android.ext.android.inject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class DtnEngineService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val localServiceDao: LocalServiceDao by inject()
    private val bundleRecordDao: BundleRecordDao by inject()
    private val routingRuleDao: RoutingRuleDao by inject()
    private val convergenceProfileDao: ConvergenceProfileDao by inject()
    private val bpsecKeyDao: BpsecKeyDao by inject()
    private val logDao: SystemLogDao by inject()

    private lateinit var tcpClAdapter: TcpClAdapter
    private lateinit var bluetoothAdapter: BluetoothClassicAdapter
    private val adapters = mutableListOf<ConvergenceLayerAdapter>()

    private var flushJob: Job? = null
    private var cleanupJob: Job? = null
    private var pullJob: Job? = null
    private var periodicFlushJob: Job? = null
    private val lastPullTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val isAndroidAutoActiveFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
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
        val keys = bpsecKeyDao.getAllList()
        return keys.find { key ->
            com.dtn.messenger.util.PayloadUtils.isPrefixMatch(key.nodeEid, sourceEid) ||
                com.dtn.messenger.util.PayloadUtils.isPrefixMatch(sourceEid, key.nodeEid)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        log("INFO", "DtnEngineService creating")
        createNotificationChannels()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildForegroundNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildForegroundNotification())
            }
        } catch (e: Exception) {
            log("ERROR", "Failed to start foreground service: ${e.message}")
            stopSelf()
        }

        // Initialize adapters
        tcpClAdapter = TcpClAdapter(context = this, port = 5051, logDao = logDao)
        bluetoothAdapter = BluetoothClassicAdapter(context = this, logDao = logDao)

        adapters.add(tcpClAdapter)
        adapters.add(bluetoothAdapter)

        // Initialize CarConnection observer
        try {
            val carConnection = androidx.car.app.connection.CarConnection(this)
            carConnection.type.observeForever(carConnectionObserver)
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
                        (it.triggerType == TriggerType.WIFI_SSID || it.triggerType == TriggerType.PERIODIC_INTERNET) && !it.isPaused
                    }

                // Manage Bluetooth Adapter
                if (bluetoothEnabled) {
                    if (!isBluetoothActive.value) {
                        log("INFO", "Starting Bluetooth Adapter")
                        bluetoothAdapter.start(serviceScope) { bundleBytes ->
                            serviceScope.launch {
                                onBundleReceived(bundleBytes)
                            }
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
                            serviceScope.launch {
                                onBundleReceived(bundleBytes)
                            }
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

    private suspend fun onBundleReceived(bundleBytes: ByteArray) {
        try {
            val bundle = Bpv7Parser.deserialize(bundleBytes)
            val primary = bundle.primaryBlock
            val payload = bundle.payloadBlock

            // Hop limit check
            val hopLimit = bundle.hopCountBlock?.hopLimit ?: 64
            val hopCount = bundle.hopCountBlock?.hopCount ?: 0
            if (hopCount >= hopLimit) {
                log("WARN", "Received bundle from ${primary.source.uri} exceeded hop limit ($hopCount/$hopLimit). Discarding.")
                return
            }

            // Duplicate check
            val creationTime = systemTimeFromDtn(primary.creationTimestamp.first)
            val seqNo = primary.creationTimestamp.second
            val duplicate = bundleRecordDao.findDuplicate(primary.source.uri, creationTime, seqNo)
            if (duplicate != null) {
                log("INFO", "Duplicate bundle from ${primary.source.uri} (timestamp=$creationTime, seq=$seqNo) already exists. Discarding.")
                return
            }

            log("INFO", "Processing received bundle from ${primary.source.uri}")

            // Check BPSec BIB integrity
            var bpsecStatus = BpsecStatus.UNVERIFIED
            val bib = bundle.bibBlock
            val prefs = com.dtn.messenger.util.PreferencesHelper.getEncryptedSharedPreferences(this)
            val policy = prefs.getString("bpsec_policy", "none") ?: "none"

            if (bib != null) {
                val keyRecord = findBpsecKey(primary.source.uri)
                if (keyRecord != null) {
                    val decryptedKey =
                        try {
                            com.dtn.messenger.util.CryptoManager.decrypt(keyRecord.secretKey)
                        } catch (e: Exception) {
                            log("ERROR", "Failed to decrypt BPSec key for ${primary.source.uri}")
                            return
                        }
                    // Compute expected signature
                    // For calculation, we need raw primary block bytes.
                    val rawPrimaryBytes = primary.rawBytes ?: Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()

                    val computedSignature =
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

                    bpsecStatus =
                        if (computedSignature.contentEquals(bib.signature)) {
                            BpsecStatus.VALID
                        } else {
                            BpsecStatus.INVALID
                        }
                    log("INFO", "BPSec signature verification: $bpsecStatus")

                    if (bpsecStatus == BpsecStatus.INVALID) {
                        if (policy == "strict") {
                            log("ERROR", "BPSec signature invalid under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                            return
                        } else if (policy == "warn") {
                            log("WARN", "BPSec signature invalid under WARN policy for bundle from ${primary.source.uri}.")
                        }
                    }
                } else {
                    log("WARN", "No BPSec key found for source ${primary.source.uri}, verification skipped")
                    if (policy == "strict") {
                        log("ERROR", "BPSec key missing under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                        return
                    }
                }
            } else {
                // Check if a key exists for this node EID
                val keyRecord = findBpsecKey(primary.source.uri)
                if (keyRecord != null) {
                    if (policy == "strict") {
                        log("ERROR", "BPSec signature missing under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                        return
                    } else if (policy == "warn") {
                        log("WARN", "BPSec signature missing under WARN policy for bundle from ${primary.source.uri}.")
                    }
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
            val extension = com.dtn.messenger.util.PayloadUtils.getPayloadFileExtension(tempFile.absolutePath)
            val payloadFile = File(payloadsDir, "$bundleId.$extension")
            tempFile.renameTo(payloadFile)

            // Verify if it is for local services on our node (supporting prefix/multicast match for multiple services)
            val localServices = localServiceDao.getAllList()
            val matchedLocalServices =
                localServices.filter { service ->
                    com.dtn.messenger.util.PayloadUtils.isPrefixMatch(service.serviceEid, primary.destination.uri) ||
                        com.dtn.messenger.util.PayloadUtils.isPrefixMatch(primary.destination.uri, service.serviceEid)
                }
            val isLocal = matchedLocalServices.isNotEmpty()

            // Check if we also need to forward it (multicast/anycast or transit routing)
            val nextHop = resolveNextHop(primary.destination.uri)
            val shouldForward = findProfileForNextHop(nextHop) != null

            val record =
                BundleRecord(
                    bundleId = bundleId,
                    destinationEid = primary.destination.uri,
                    sourceEid = primary.source.uri,
                    creationTimestamp = creationTime,
                    sequenceNumber = seqNo,
                    lifetimeMs = primary.lifetimeMs,
                    payloadFilePath = payloadFile.absolutePath,
                    state = if (shouldForward) BundleState.OUTBOX else BundleState.RECEIVED,
                    isRead = false,
                    bpsecStatus = bpsecStatus,
                    hopCount = hopCount,
                )

            bundleRecordDao.insert(record)
            log("INFO", "Bundle $bundleId inserted. State: ${record.state}")
            updateTrigger.emit(Unit)

            if (isLocal) {
                // Post notification for all matched local services
                matchedLocalServices.forEach { service ->
                    triggerMessageNotification(service, record, payload.data)
                }
            }

            if (shouldForward) {
                // Forward immediately
                log("INFO", "Received transit/multicast bundle targeting ${primary.destination.uri}. Forwarding immediately.")
                serviceScope.launch {
                    flushQueue()
                }
            }
        } catch (e: Exception) {
            log("ERROR", "Error handling received bundle: " + android.util.Log.getStackTraceString(e))
        }
    }

    private fun triggerMessageNotification(
        service: LocalService,
        record: BundleRecord,
        data: ByteArray,
    ) {
        val messageText =
            try {
                String(data, Charsets.UTF_8)
            } catch (e: Exception) {
                "Binary Payload"
            }

        val intent = packageManager.getLaunchIntentForPackage(packageName)
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
                Intent(this, com.dtn.messenger.receiver.DtnMessageReceiver::class.java).apply {
                    putExtra("dest_eid", service.serviceEid)
                    putExtra("src_eid", record.sourceEid)
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

    private suspend fun flushQueue(): Boolean {
        val outbox = bundleRecordDao.getByState(BundleState.OUTBOX)
        val transit = bundleRecordDao.getByState(BundleState.TRANSIT)
        val allOutgoing = outbox + transit

        if (allOutgoing.isEmpty()) {
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
            return false
        }

        log("INFO", "Flushing queue, ${allOutgoing.size} outgoing bundle(s)")
        var anySuccess = false

        for (record in allOutgoing) {
            val file = File(record.payloadFilePath)
            if (!file.exists()) {
                log("WARN", "Payload file not found for bundle ${record.bundleId}, skipping")
                continue
            }
            val payloadBytes = file.readBytes()

            if (record.hopCount + 1 >= 64) {
                log("WARN", "Bundle ${record.bundleId} exceeded hop limit (64) during forwarding. Discarding.")
                try {
                    val fileObj = File(record.payloadFilePath)
                    if (fileObj.exists()) fileObj.delete()
                } catch (e: Exception) {
                }
                bundleRecordDao.delete(record)
                continue
            }

            // Resolve next hop
            val nextHop = resolveNextHop(record.destinationEid)
            val profile = findProfileForNextHop(nextHop)
            if (profile == null) {
                log("WARN", "No convergence profile found to reach next-hop $nextHop (dest: ${record.destinationEid})")
                continue
            }

            // Load BPSec Key if present to sign bundle
            val keyRecord = bpsecKeyDao.getByKeyId(record.destinationEid) ?: bpsecKeyDao.getByKeyId(nextHop)

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
            val hopCount = HopCountBlock(hopLimit = 64, hopCount = record.hopCount + 1)

            var bib: BibBlock? = null
            if (keyRecord != null) {
                val decryptedKey =
                    try {
                        com.dtn.messenger.util.CryptoManager.decrypt(keyRecord.secretKey)
                    } catch (e: Exception) {
                        log("ERROR", "Failed to decrypt local BPSec key for signature")
                        null
                    }
                if (decryptedKey != null) {
                    // Compute signature
                    val rawPrimary = Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()
                    val signature =
                        Bpv7Parser.computeHmac(
                            secretKey = decryptedKey,
                            primaryBlockBytes = rawPrimary,
                            targetBlockType = 1,
                            targetBlockNumber = payload.blockNumber,
                            targetBlockFlags = payload.blockControlFlags,
                            securityBlockType = 11,
                            securityBlockNumber = 2, // BIB block number
                            securityBlockFlags = 3,
                            payloadBytes = payloadBytes,
                            scopeFlags = 7,
                        )
                    bib =
                        BibBlock(
                            blockNumber = 2,
                            securitySource = Eid(record.sourceEid),
                            signature = signature,
                        )
                    log("INFO", "Signed bundle ${record.bundleId} using key for EID: ${keyRecord.nodeEid}")
                }
            }

            val bundle = Bundle(primary, payload, hopCount, bib)
            val bundleBytes = Bpv7Parser.serialize(bundle)

            // Select adapter and transmit
            val adapter =
                adapters.find {
                    it.name.lowercase() == (if (profile.triggerType == TriggerType.BLUETOOTH_ALWAYS) "bluetooth" else "tcpclv4").lowercase()
                }
            if (adapter == null) {
                log("ERROR", "No adapter found for profile type ${profile.triggerType}")
                continue
            }

            log("INFO", "Transmitting bundle ${record.bundleId} via ${adapter.name} to ${profile.targetAddress}")
            val success = adapter.sendBundle(bundleBytes, profile.targetAddress)
            if (success) {
                val nextState = BundleState.DELIVERED
                bundleRecordDao.updateState(record.bundleId, nextState)
                log("INFO", "Bundle ${record.bundleId} successfully sent! New state: $nextState")
                anySuccess = true
                updateTrigger.emit(Unit)
            } else {
                log("WARN", "Transmission failed for bundle ${record.bundleId}")
            }
        }
        return anySuccess
    }

    private suspend fun resolveNextHop(destination: String): String {
        val rules = routingRuleDao.getAllList()
        for (rule in rules) {
            val pattern = rule.destinationEidPattern.replace("*", "").trimEnd('/')
            if (com.dtn.messenger.util.PayloadUtils.isPrefixMatch(pattern, destination)) {
                return rule.nextHopEid
            }
        }
        return destination // Default direct
    }

    private suspend fun findProfileForNextHop(nextHop: String): ConvergenceProfile? {
        val profiles = convergenceProfileDao.getAllList()
        // Try direct profile ID or name match
        var match = profiles.find { (it.profileId == nextHop || it.name == nextHop) && !it.isPaused }
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
            match = profiles.find { (it.profileId == nextHopNode || it.name == nextHopNode) && !it.isPaused }
        }
        return match
    }

    private suspend fun cleanupExpiredBundles() {
        val now = System.currentTimeMillis()
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
            "FORCE_PULL" -> {
                val address = intent.getStringExtra("address")
                if (address != null) {
                    serviceScope.launch {
                        log("INFO", "Forced connection triggered to $address. Flushing queue first.")
                        try {
                            flushQueue()
                        } catch (e: Exception) {
                            log("WARN", "Forced queue flush error: ${e.message}")
                        }
                        val adapter = adapters.find { it.name.lowercase().contains("tcp") }
                        if (adapter != null) {
                            adapter.sendBundle(ByteArray(0), address)
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
            androidx.car.app.connection.CarConnection(this).type.removeObserver(carConnectionObserver)
        } catch (e: Exception) {
        }
        isTcpActive.value = false
        isBluetoothActive.value = false
        flushJob?.cancel()
        cleanupJob?.cancel()
        pullJob?.cancel()
        periodicFlushJob?.cancel()
        adapters.forEach { it.stop() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildForegroundNotification(): Notification {
        val channelId = CHANNEL_ID
        val intent = packageManager.getLaunchIntentForPackage(packageName)
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
