package io.github.loic_fejoz.dtn_android_messenger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import io.github.loic_fejoz.dtn_android_messenger.data.repository.BundleOutboxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DtnMessageReceiver : BroadcastReceiver(), KoinComponent {
    private val outboxRepository: BundleOutboxRepository by inject()

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
                        outboxRepository.enqueueTextBundle(
                            sourceEid = destEid,
                            destinationEid = srcEid,
                            text = replyText,
                        )
                        outboxRepository.triggerQueueFlush()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
