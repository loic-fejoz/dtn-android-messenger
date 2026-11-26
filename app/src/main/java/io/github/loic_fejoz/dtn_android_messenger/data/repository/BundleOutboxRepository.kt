package io.github.loic_fejoz.dtn_android_messenger.data.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.loic_fejoz.dtn_android_messenger.data.dao.BundleRecordDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.BpsecStatus
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleRecord
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleState
import io.github.loic_fejoz.dtn_android_messenger.service.DtnEngineService
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper
import io.github.loic_fejoz.dtn_android_messenger.util.TimeProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BundleOutboxRepository(
    private val context: Context,
    private val bundleRecordDao: BundleRecordDao,
    private val timeProvider: TimeProvider,
) {
    private val sequenceLock = Any()
    private var lastTimestampMs = 0L
    private var sequenceCounter =
        runCatching {
            PreferencesHelper.getEncryptedSharedPreferences(context).getLong("outbox_seq_counter", 0L)
        }.getOrDefault(0L)

    private fun nextTimestampAndSequence(): Pair<Long, Long> =
        synchronized(sequenceLock) {
            val now = timeProvider.currentTimeMillis()
            val timestamp = maxOf(now, lastTimestampMs)
            lastTimestampMs = timestamp
            val seq = ++sequenceCounter
            runCatching {
                PreferencesHelper.getEncryptedSharedPreferences(context)
                    .edit()
                    .putLong("outbox_seq_counter", seq)
                    .apply()
            }
            Pair(timestamp, seq)
        }

    suspend fun enqueueTextBundle(
        sourceEid: String,
        destinationEid: String,
        text: String,
        lifetimeMs: Long = BundleRecord.DEFAULT_LIFETIME_MS,
    ): BundleRecord {
        val payloadsDir = File(context.filesDir, "payloads")
        if (!payloadsDir.exists()) payloadsDir.mkdirs()

        val bundleId = UUID.randomUUID().toString()
        val payloadFile = File(payloadsDir, "$bundleId.bin")
        FileOutputStream(payloadFile).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
        }
        return enqueuePayloadFile(
            sourceEid = sourceEid,
            destinationEid = destinationEid,
            payloadFile = payloadFile,
            bundleId = bundleId,
            lifetimeMs = lifetimeMs,
        )
    }

    suspend fun enqueuePayloadFile(
        sourceEid: String,
        destinationEid: String,
        payloadFile: File,
        bundleId: String = payloadFile.nameWithoutExtension,
        lifetimeMs: Long = BundleRecord.DEFAULT_LIFETIME_MS,
    ): BundleRecord {
        val (timestamp, seq) = nextTimestampAndSequence()
        val record =
            BundleRecord(
                bundleId = bundleId,
                destinationEid = destinationEid.trim(),
                sourceEid = sourceEid.trim(),
                creationTimestamp = timestamp,
                sequenceNumber = seq,
                lifetimeMs = lifetimeMs,
                payloadFilePath = payloadFile.absolutePath,
                state = BundleState.OUTBOX,
                isRead = true,
                bpsecStatus = BpsecStatus.UNVERIFIED,
                hopCount = 0,
            )
        bundleRecordDao.insert(record)
        return record
    }

    fun triggerQueueFlush() {
        val serviceIntent =
            Intent(context, DtnEngineService::class.java).apply {
                action = "FLUSH_QUEUE"
            }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e("BundleOutboxRepository", "Failed to start foreground service for flush: ${e.message}")
        }
    }
}
