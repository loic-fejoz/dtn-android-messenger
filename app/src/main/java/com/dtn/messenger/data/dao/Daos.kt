package com.dtn.messenger.data.dao

import androidx.room.*
import com.dtn.messenger.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalServiceDao {
    @Query("SELECT * FROM local_services")
    fun getAll(): Flow<List<LocalService>>

    @Query("SELECT * FROM local_services")
    suspend fun getAllList(): List<LocalService>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(service: LocalService)

    @Delete
    suspend fun delete(service: LocalService)

    @Query("SELECT * FROM local_services WHERE serviceEid = :serviceEid")
    suspend fun getById(serviceEid: String): LocalService?
}

@Dao
interface BundleRecordDao {
    @Query("SELECT * FROM bundle_records ORDER BY creationTimestamp DESC")
    fun getAll(): Flow<List<BundleRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bundle: BundleRecord)

    @Delete
    suspend fun delete(bundle: BundleRecord)

    @Query("SELECT * FROM bundle_records WHERE bundleId = :bundleId")
    suspend fun getById(bundleId: String): BundleRecord?

    @Query("SELECT * FROM bundle_records WHERE destinationEid = :destEid ORDER BY creationTimestamp DESC")
    fun getByDestination(destEid: String): Flow<List<BundleRecord>>

    @Query("SELECT * FROM bundle_records WHERE state = :state")
    suspend fun getByState(state: BundleState): List<BundleRecord>

    @Query("UPDATE bundle_records SET state = :state WHERE bundleId = :id")
    suspend fun updateState(
        id: String,
        state: BundleState,
    )

    @Query("UPDATE bundle_records SET isRead = :isRead WHERE bundleId = :id")
    suspend fun markAsRead(
        id: String,
        isRead: Boolean,
    )

    @Query("UPDATE bundle_records SET isRead = 1 WHERE destinationEid = :destEid AND isRead = 0")
    suspend fun markAllAsReadForDestination(destEid: String)

    @Query("SELECT COUNT(*) FROM bundle_records WHERE destinationEid = :serviceEid AND isRead = 0")
    fun getUnreadCount(serviceEid: String): Flow<Int>

    @Query("SELECT * FROM bundle_records WHERE (creationTimestamp + lifetimeMs) < :now")
    suspend fun getExpired(now: Long): List<BundleRecord>

    @Query(
        "SELECT * FROM bundle_records WHERE sourceEid = :sourceEid AND creationTimestamp = :creationTime AND sequenceNumber = :seqNo LIMIT 1",
    )
    suspend fun findDuplicate(
        sourceEid: String,
        creationTime: Long,
        seqNo: Long,
    ): BundleRecord?
}

@Dao
interface RoutingRuleDao {
    @Query("SELECT * FROM routing_rules")
    fun getAll(): Flow<List<RoutingRule>>

    @Query("SELECT * FROM routing_rules")
    suspend fun getAllList(): List<RoutingRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RoutingRule)

    @Delete
    suspend fun delete(rule: RoutingRule)
}

@Dao
interface ConvergenceProfileDao {
    @Query("SELECT * FROM convergence_profiles")
    fun getAll(): Flow<List<ConvergenceProfile>>

    @Query("SELECT * FROM convergence_profiles")
    suspend fun getAllList(): List<ConvergenceProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ConvergenceProfile)

    @Delete
    suspend fun delete(profile: ConvergenceProfile)
}

@Dao
interface BpsecKeyDao {
    @Query("SELECT * FROM bpsec_keys")
    fun getAll(): Flow<List<BpsecKey>>

    @Query("SELECT * FROM bpsec_keys")
    suspend fun getAllList(): List<BpsecKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: BpsecKey)

    @Delete
    suspend fun delete(key: BpsecKey)

    @Query("SELECT * FROM bpsec_keys WHERE nodeEid = :nodeEid")
    suspend fun getByKeyId(nodeEid: String): BpsecKey?
}

@Dao
interface SystemLogDao {
    @Query("SELECT * FROM system_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SystemLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: SystemLog)

    @Query("DELETE FROM system_logs")
    suspend fun clearAll()

    @Query("DELETE FROM system_logs WHERE timestamp < :cutoff")
    suspend fun deleteLogsOlderThan(cutoff: Long)

    @Query("SELECT * FROM system_logs ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getOldestLogs(limit: Int): List<SystemLog>
}

@Dao
interface SenmlEntryDao {
    @Query("SELECT * FROM senml_entries WHERE serviceEid = :serviceEid AND isDeleted = 0 ORDER BY displayOrder ASC, name ASC")
    fun getActiveEntries(serviceEid: String): Flow<List<SenmlEntry>>

    @Query("SELECT * FROM senml_entries WHERE serviceEid = :serviceEid AND isDeleted = 0 ORDER BY displayOrder ASC, name ASC")
    suspend fun getActiveEntriesList(serviceEid: String): List<SenmlEntry>

    @Query("SELECT * FROM senml_entries WHERE serviceEid = :serviceEid AND name = :name")
    suspend fun getEntry(serviceEid: String, name: String): SenmlEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entry: SenmlEntry)

    @Query("UPDATE senml_entries SET isDeleted = 1 WHERE serviceEid = :serviceEid AND name = :name")
    suspend fun markDeleted(serviceEid: String, name: String)

    @Query("UPDATE senml_entries SET customLabel = :customLabel WHERE serviceEid = :serviceEid AND name = :name")
    suspend fun updateCustomLabel(serviceEid: String, name: String, customLabel: String?)

    @Query("UPDATE senml_entries SET displayOrder = :order WHERE serviceEid = :serviceEid AND name = :name")
    suspend fun updateOrder(serviceEid: String, name: String, order: Int)

    @Query("SELECT MAX(displayOrder) FROM senml_entries WHERE serviceEid = :serviceEid")
    suspend fun getMaxOrder(serviceEid: String): Int?
}
