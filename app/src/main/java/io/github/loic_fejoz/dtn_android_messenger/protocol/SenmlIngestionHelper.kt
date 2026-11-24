package io.github.loic_fejoz.dtn_android_messenger.protocol

import io.github.loic_fejoz.dtn_android_messenger.data.dao.SenmlEntryDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.SenmlEntry

object SenmlIngestionHelper {
    suspend fun ingest(
        serviceEid: String,
        data: ByteArray,
        fallbackTimestamp: Long,
        senmlEntryDao: SenmlEntryDao,
    ): Int {
        val records = SenmlParser.parse(data, fallbackTimestamp)
        val latestRecordsByName =
            records.groupBy { it.name }
                .mapValues { (_, list) -> list.maxByOrNull { it.timestamp }!! }
                .values

        for (rec in latestRecordsByName) {
            val existing = senmlEntryDao.getEntry(serviceEid, rec.name)
            if (existing != null) {
                if (rec.timestamp >= existing.timestamp) {
                    senmlEntryDao.insertOrUpdate(
                        existing.copy(
                            value = rec.value,
                            unit = rec.unit,
                            timestamp = rec.timestamp,
                            isDeleted = false,
                        ),
                    )
                }
            } else {
                val maxOrder = senmlEntryDao.getMaxOrder(serviceEid) ?: 0
                senmlEntryDao.insertOrUpdate(
                    SenmlEntry(
                        serviceEid = serviceEid,
                        name = rec.name,
                        value = rec.value,
                        unit = rec.unit,
                        timestamp = rec.timestamp,
                        displayOrder = maxOrder + 1,
                        isDeleted = false,
                    ),
                )
            }
        }
        return records.size
    }
}
