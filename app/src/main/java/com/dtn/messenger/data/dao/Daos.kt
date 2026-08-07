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
    suspend fun updateState(id: String, state: BundleState)

    @Query("UPDATE bundle_records SET isRead = :isRead WHERE bundleId = :id")
    suspend fun markAsRead(id: String, isRead: Boolean)

    @Query("SELECT COUNT(*) FROM bundle_records WHERE destinationEid = :serviceEid AND isRead = 0")
    fun getUnreadCount(serviceEid: String): Flow<Int>

    @Query("SELECT * FROM bundle_records WHERE (creationTimestamp + lifetimeMs) < :now")
    suspend fun getExpired(now: Long): List<BundleRecord>

    @Query("SELECT * FROM bundle_records WHERE sourceEid = :sourceEid AND creationTimestamp = :creationTime AND sequenceNumber = :seqNo LIMIT 1")
    suspend fun findDuplicate(sourceEid: String, creationTime: Long, seqNo: Long): BundleRecord?
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

    @Query("SELECT * FROM system_logs ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getOldestLogs(limit: Int): List<SystemLog>
}
