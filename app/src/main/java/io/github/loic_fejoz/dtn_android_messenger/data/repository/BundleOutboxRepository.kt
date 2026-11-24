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
import io.github.loic_fejoz.dtn_android_messenger.util.TimeProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class BundleOutboxRepository(
    private val context: Context,
    private val bundleRecordDao: BundleRecordDao,
    private val timeProvider: TimeProvider,
) {
    private val sequenceCounter = AtomicLong(1L)

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
        val record =
            BundleRecord(
                bundleId = bundleId,
                destinationEid = destinationEid.trim(),
                sourceEid = sourceEid.trim(),
                creationTimestamp = timeProvider.currentTimeMillis(),
                sequenceNumber = sequenceCounter.getAndIncrement(),
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
