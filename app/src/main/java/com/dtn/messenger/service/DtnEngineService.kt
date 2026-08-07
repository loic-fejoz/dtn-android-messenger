package com.dtn.messenger.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.dtn.messenger.R
import com.dtn.messenger.cla.BluetoothClassicAdapter
import com.dtn.messenger.cla.ConvergenceLayerAdapter
import com.dtn.messenger.cla.TcpClAdapter
import com.dtn.messenger.data.dao.*
import com.dtn.messenger.data.model.*
import com.dtn.messenger.protocol.*
import kotlinx.coroutines.*
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

    companion object {
        const val CHANNEL_ID = "dtn_engine_channel"
        const val CHAT_CHANNEL_ID = "dtn_chat_channel"
        const val NOTIFICATION_ID = 101
        
        // Static flow or triggers to notify UI of updates
        val updateTrigger = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1)
        val isTcpActive = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isBluetoothActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    private fun log(level: String, message: String) {
        serviceScope.launch {
            logDao.insert(SystemLog(timestamp = System.currentTimeMillis(), level = level, message = message))
        }
    }

    override fun onCreate() {
        super.onCreate()
        log("INFO", "DtnEngineService creating")
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        // Initialize adapters
        tcpClAdapter = TcpClAdapter(context = this, port = 5051, logDao = logDao)
        bluetoothAdapter = BluetoothClassicAdapter(context = this, logDao = logDao)
        
        adapters.add(tcpClAdapter)
        adapters.add(bluetoothAdapter)

        // Start adapters
        adapters.forEach { adapter ->
            adapter.start(serviceScope) { bundleBytes ->
                serviceScope.launch {
                    onBundleReceived(bundleBytes)
                }
            }
            if (adapter.name.lowercase().contains("tcp")) {
                isTcpActive.value = true
            } else if (adapter.name.lowercase().contains("blue")) {
                isBluetoothActive.value = true
            }
        }

        // Schedule periodic tasks
        startPeriodicTasks()
    }

    private fun startPeriodicTasks() {
        // Queue flusher: runs every 10 seconds
        flushJob = serviceScope.launch {
            while (isActive) {
                try {
                    flushQueue()
                } catch (e: Exception) {
                    log("WARN", "Queue flush error: ${e.message}")
                }
                delay(10000)
            }
        }

        // Cleanup task: runs every 60 seconds
        cleanupJob = serviceScope.launch {
            while (isActive) {
                try {
                    cleanupExpiredBundles()
                } catch (e: Exception) {
                    log("WARN", "Cleanup task error: ${e.message}")
                }
                delay(60000)
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
            val prefs = getSharedPreferences("dtn_prefs", Context.MODE_PRIVATE)
            val policy = prefs.getString("bpsec_policy", "none") ?: "none"

            if (bib != null) {
                val keyRecord = bpsecKeyDao.getByKeyId(primary.source.uri)
                if (keyRecord != null) {
                    val decryptedKey = try {
                        com.dtn.messenger.util.CryptoManager.decrypt(keyRecord.secretKey)
                    } catch (e: Exception) {
                        log("ERROR", "Failed to decrypt BPSec key for ${primary.source.uri}")
                        return
                    }
                    // Compute expected signature
                    // For calculation, we need raw primary block bytes.
                    val rawPrimaryBytes = Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()
                    
                    val computedSignature = Bpv7Parser.computeHmac(
                        secretKey = decryptedKey,
                        primaryBlockBytes = rawPrimaryBytes,
                        targetBlockType = 1,
                        targetBlockNumber = payload.blockNumber,
                        targetBlockFlags = payload.blockControlFlags,
                        securityBlockType = 11,
                        securityBlockNumber = bib.blockNumber,
                        securityBlockFlags = bib.blockControlFlags,
                        payloadBytes = payload.data,
                        scopeFlags = bib.scopeFlags
                    )
                    
                    bpsecStatus = if (computedSignature.contentEquals(bib.signature)) {
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
                val keyRecord = bpsecKeyDao.getByKeyId(primary.source.uri)
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

            // Verify if it is for a local service on our node
            val localServices = localServiceDao.getById(primary.destination.uri)
            val isLocal = localServices != null

            val record = BundleRecord(
                bundleId = bundleId,
                destinationEid = primary.destination.uri,
                sourceEid = primary.source.uri,
                creationTimestamp = creationTime,
                sequenceNumber = seqNo,
                lifetimeMs = primary.lifetimeMs,
                payloadFilePath = payloadFile.absolutePath,
                state = if (isLocal) BundleState.RECEIVED else BundleState.OUTBOX,
                isRead = false,
                bpsecStatus = bpsecStatus,
                hopCount = hopCount
            )

            bundleRecordDao.insert(record)
            log("INFO", "Bundle $bundleId inserted. State: ${record.state}")
            updateTrigger.emit(Unit)

            if (isLocal) {
                // Post notification for local user
                localServices?.let { service ->
                    triggerMessageNotification(service, record, payload.data)
                }
            } else {
                // For store-and-forward, we received a transit bundle.
                // It will be forwarded during next queue flush because its state is OUTBOX.
                log("INFO", "Received transit bundle targeting ${primary.destination.uri}. Scheduled for forwarding.")
            }

        } catch (e: Exception) {
            log("ERROR", "Error handling received bundle: ${e.message}")
        }
    }

    private fun triggerMessageNotification(service: LocalService, record: BundleRecord, data: ByteArray) {
        val messageText = try { String(data, Charsets.UTF_8) } catch (e: Exception) { "Binary Payload" }
        
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = service.notificationSoundUri?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        
        val builder = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(soundUri)

        if (service.viewerType == ViewerType.CHAT) {
            val user = androidx.core.app.Person.Builder().setName("Me").setKey("me").build()
            val sender = androidx.core.app.Person.Builder().setName(record.sourceEid).setKey(record.sourceEid).build()
            val messagingStyle = NotificationCompat.MessagingStyle(user)
                .addMessage(messageText, record.creationTimestamp, sender)
            builder.setStyle(messagingStyle)

            val replyInput = androidx.core.app.RemoteInput.Builder("extra_voice_reply")
                .setLabel("Reply")
                .build()

            val replyIntent = Intent(this, com.dtn.messenger.receiver.DtnMessageReceiver::class.java).apply {
                putExtra("dest_eid", service.serviceEid)
                putExtra("src_eid", record.sourceEid)
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                this,
                record.bundleId.hashCode(),
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                "Reply",
                replyPendingIntent
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

    private suspend fun flushQueue() {
        val outbox = bundleRecordDao.getByState(BundleState.OUTBOX)
        val transit = bundleRecordDao.getByState(BundleState.TRANSIT)
        val allOutgoing = outbox + transit
        
        if (allOutgoing.isEmpty()) {
            val profiles = convergenceProfileDao.getAllList()
            for (profile in profiles) {
                if (profile.triggerType == TriggerType.PERIODIC_INTERNET) {
                    val adapter = adapters.find { it.name.lowercase() == "tcpclv4" }
                    if (adapter != null) {
                        log("INFO", "Performing periodic pull connection to ${profile.targetAddress}")
                        adapter.sendBundle(ByteArray(0), profile.targetAddress)
                    }
                }
            }
            return
        }

        log("INFO", "Flushing queue, ${allOutgoing.size} outgoing bundle(s)")

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
                } catch (e: Exception) {}
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
            val primary = PrimaryBlock(
                destination = Eid(record.destinationEid),
                source = Eid(record.sourceEid),
                reportTo = Eid(record.sourceEid),
                creationTimestamp = Pair(dtnTimeFromSystem(record.creationTimestamp), record.sequenceNumber),
                lifetimeMs = record.lifetimeMs
            )
            
            val payload = PayloadBlock(data = payloadBytes)
            val hopCount = HopCountBlock(hopLimit = 64, hopCount = record.hopCount + 1)
            
            var bib: BibBlock? = null
            if (keyRecord != null) {
                val decryptedKey = try {
                    com.dtn.messenger.util.CryptoManager.decrypt(keyRecord.secretKey)
                } catch (e: Exception) {
                    log("ERROR", "Failed to decrypt local BPSec key for signature")
                    null
                }
                if (decryptedKey != null) {
                    // Compute signature
                    val rawPrimary = Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()
                    val signature = Bpv7Parser.computeHmac(
                        secretKey = decryptedKey,
                        primaryBlockBytes = rawPrimary,
                        targetBlockType = 1,
                        targetBlockNumber = payload.blockNumber,
                        targetBlockFlags = payload.blockControlFlags,
                        securityBlockType = 11,
                        securityBlockNumber = 2, // BIB block number
                        securityBlockFlags = 3,
                        payloadBytes = payloadBytes,
                        scopeFlags = 7
                    )
                    bib = BibBlock(
                        blockNumber = 2,
                        securitySource = Eid(record.sourceEid),
                        signature = signature
                    )
                    log("INFO", "Signed bundle ${record.bundleId} using key for EID: ${keyRecord.nodeEid}")
                }
            }

            val bundle = Bundle(primary, payload, hopCount, bib)
            val bundleBytes = Bpv7Parser.serialize(bundle)

            // Select adapter and transmit
            val adapter = adapters.find { it.name.lowercase() == (if (profile.triggerType == TriggerType.BLUETOOTH_ALWAYS) "bluetooth" else "tcpclv4").lowercase() }
            if (adapter == null) {
                log("ERROR", "No adapter found for profile type ${profile.triggerType}")
                continue
            }

            log("INFO", "Transmitting bundle ${record.bundleId} via ${adapter.name} to ${profile.targetAddress}")
            val success = adapter.sendBundle(bundleBytes, profile.targetAddress)
            if (success) {
                val nextState = if (record.destinationEid == nextHop) BundleState.DELIVERED else BundleState.TRANSIT
                bundleRecordDao.updateState(record.bundleId, nextState)
                log("INFO", "Bundle ${record.bundleId} successfully sent! New state: $nextState")
                updateTrigger.emit(Unit)
            } else {
                log("WARN", "Transmission failed for bundle ${record.bundleId}")
            }
        }
    }

    private suspend fun resolveNextHop(destination: String): String {
        val rules = routingRuleDao.getAllList()
        for (rule in rules) {
            val pattern = rule.destinationEidPattern.replace("*", "")
            if (destination.startsWith(pattern)) {
                return rule.nextHopEid
            }
        }
        return destination // Default direct
    }

    private suspend fun findProfileForNextHop(nextHop: String): ConvergenceProfile? {
        val profiles = convergenceProfileDao.getAllList()
        // Try direct profile ID or name match
        var match = profiles.find { it.profileId == nextHop || it.name == nextHop }
        if (match == null) {
            // Check if profile ID matches node prefix correctly
            val nextHopNode = if (nextHop.startsWith("dtn://")) {
                val pathPart = nextHop.substring(6)
                "dtn://" + pathPart.substringBefore("/")
            } else if (nextHop.startsWith("ipn:")) {
                "ipn:" + nextHop.substring(4).substringBefore("/")
            } else {
                nextHop
            }
            match = profiles.find { it.profileId == nextHopNode || it.name == nextHopNode }
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
    }

    private fun dtnTimeFromSystem(systemMillis: Long): Long {
        return systemMillis - 946684800000L
    }

    private fun systemTimeFromDtn(dtnMillis: Long): Long {
        return dtnMillis + 946684800000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "FLUSH_QUEUE") {
            serviceScope.launch {
                flushQueue()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        log("INFO", "DtnEngineService destroying")
        isTcpActive.value = false
        isBluetoothActive.value = false
        flushJob?.cancel()
        cleanupJob?.cancel()
        adapters.forEach { it.stop() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildForegroundNotification(): Notification {
        val channelId = CHANNEL_ID
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            val engineChannel = NotificationChannel(
                CHANNEL_ID,
                "DTN Background Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            val chatChannel = NotificationChannel(
                CHAT_CHANNEL_ID,
                "DTN Chat Messaging",
                NotificationManager.IMPORTANCE_HIGH
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
