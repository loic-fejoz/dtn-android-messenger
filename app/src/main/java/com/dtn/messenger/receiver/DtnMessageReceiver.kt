package com.dtn.messenger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.dtn.messenger.data.dao.BundleRecordDao
import com.dtn.messenger.data.model.BpsecStatus
import com.dtn.messenger.data.model.BundleRecord
import com.dtn.messenger.data.model.BundleState
import com.dtn.messenger.service.DtnEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class DtnMessageReceiver : BroadcastReceiver(), KoinComponent {
    private val bundleRecordDao: BundleRecordDao by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val notificationId = intent.getIntExtra("notification_id", -1)
        if (notificationId != -1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(notificationId)
        }

        val results = RemoteInput.getResultsFromIntent(intent)
        if (results != null) {
            val replyText = results.getCharSequence("extra_voice_reply")?.toString()
            val destEid = intent.getStringExtra("dest_eid")
            val srcEid = intent.getStringExtra("src_eid")

            if (replyText != null && destEid != null && srcEid != null) {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val payloadsDir = File(context.filesDir, "payloads")
                        if (!payloadsDir.exists()) payloadsDir.mkdirs()

                        val bundleId = UUID.randomUUID().toString()
                        val payloadFile = File(payloadsDir, "$bundleId.bin")
                        FileOutputStream(payloadFile).use { fos ->
                            fos.write(replyText.toByteArray(Charsets.UTF_8))
                        }

                        // Create outbound bundle record in database
                        val record =
                            BundleRecord(
                                bundleId = bundleId,
                                // reply target is the sender
                                destinationEid = srcEid,
                                // reply origin is local service EID
                                sourceEid = destEid,
                                creationTimestamp = System.currentTimeMillis(),
                                sequenceNumber = System.currentTimeMillis() % 100000,
                                // 1 hour lifetime
                                lifetimeMs = 3600000L,
                                payloadFilePath = payloadFile.absolutePath,
                                state = BundleState.OUTBOX,
                                isRead = true,
                                bpsecStatus = BpsecStatus.UNVERIFIED,
                                hopCount = 0,
                            )
                        bundleRecordDao.insert(record)

                        // Notify service to flush queue. Since this is triggered by a user notification action,
                        // it is exempt from background start restrictions, but we must use startForegroundService.
                        val serviceIntent =
                            Intent(context, DtnEngineService::class.java).apply {
                                action = "FLUSH_QUEUE"
                            }
                        try {
                            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                        } catch (e: Exception) {
                            android.util.Log.e("DtnMessageReceiver", "Failed to start foreground service on reply: ${e.message}")
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
