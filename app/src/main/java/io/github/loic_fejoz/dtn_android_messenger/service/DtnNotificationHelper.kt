package io.github.loic_fejoz.dtn_android_messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import io.github.loic_fejoz.dtn_android_messenger.R
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleRecord
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import io.github.loic_fejoz.dtn_android_messenger.data.model.ViewerType
import io.github.loic_fejoz.dtn_android_messenger.receiver.DtnMessageReceiver
import io.github.loic_fejoz.dtn_android_messenger.ui.MainActivity
import io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object DtnNotificationHelper {
    fun formatNotificationPreview(
        data: ByteArray,
        filePath: String?,
    ): String {
        if (filePath != null) {
            if (PayloadUtils.isImagePayload(filePath)) {
                return "[Image]"
            }
            if (PayloadUtils.isAudioPayload(filePath)) {
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
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, 0, validLen))
                    .toString()
            } catch (_: CharacterCodingException) {
                return "[Binary Payload]"
            }
        val maxChars = 200
        return if (decoded.length > maxChars || data.size > maxPrefixBytes) {
            decoded.take(maxChars) + "…"
        } else {
            decoded
        }
    }

    fun buildForegroundNotification(context: Context): Notification {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat.Builder(context, DtnEngineService.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.engine_notification_title))
            .setContentText(context.getString(R.string.engine_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val engineChannel =
                NotificationChannel(
                    DtnEngineService.CHANNEL_ID,
                    context.getString(R.string.engine_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            val chatChannel =
                NotificationChannel(
                    DtnEngineService.CHAT_CHANNEL_ID,
                    context.getString(R.string.chat_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    enableLights(true)
                    enableVibration(true)
                }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(engineChannel)
            manager.createNotificationChannel(chatChannel)
        }
    }

    fun triggerMessageNotification(
        context: Context,
        service: LocalService,
        record: BundleRecord,
        data: ByteArray,
    ) {
        val messageText = formatNotificationPreview(data, record.payloadFilePath)

        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val soundUri =
            service.notificationSoundUri?.toUri()
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder =
            NotificationCompat.Builder(context, DtnEngineService.CHAT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setSound(soundUri)

        if (service.viewerType == ViewerType.CHAT) {
            val user = Person.Builder().setName("Me").setKey("me").build()
            val sender = Person.Builder().setName(record.sourceEid).setKey(record.sourceEid).build()
            val messagingStyle =
                NotificationCompat.MessagingStyle(user)
                    .addMessage(messageText, record.creationTimestamp, sender)
            builder.setStyle(messagingStyle)

            val replyInput =
                RemoteInput.Builder("extra_voice_reply")
                    .setLabel("Reply")
                    .build()

            val replyIntent =
                Intent(context, DtnMessageReceiver::class.java).apply {
                    putExtra("dest_eid", service.serviceEid)
                    putExtra("src_eid", record.sourceEid)
                    putExtra("notification_id", record.bundleId.hashCode())
                }
            val replyPendingIntent =
                PendingIntent.getBroadcast(
                    context,
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

        service.vibrationPatternJson?.let { patternStr ->
            try {
                val json = JSONArray(patternStr)
                val pattern = LongArray(json.length())
                for (i in 0 until json.length()) {
                    pattern[i] = json.getLong(i)
                }
                builder.setVibrate(pattern)
            } catch (_: Exception) {
                builder.setDefaults(Notification.DEFAULT_VIBRATE)
            }
        } ?: builder.setDefaults(Notification.DEFAULT_VIBRATE)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(record.bundleId.hashCode(), builder.build())
    }
}
