package com.dtn.messenger.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ViewerType {
    CHAT,
    BUNDLE_LIST,
    MINIAPP,
    SENML_GRAPH,
    SENML_LAST,
}

@Entity(
    tableName = "senml_entries",
    primaryKeys = ["serviceEid", "name"],
)
data class SenmlEntry(
    val serviceEid: String,
    // Reconstructed full name (bn + n)
    val name: String,
    val customLabel: String? = null,
    val value: String,
    val unit: String,
    val timestamp: Long,
    val displayOrder: Int = 0,
    val isDeleted: Boolean = false,
)

enum class BundleState {
    RECEIVED,
    OUTBOX,
    TRANSIT,
    DELIVERED,
}

enum class BpsecStatus {
    VALID,
    INVALID,
    UNVERIFIED,
}

enum class TriggerType {
    WIFI_SSID,
    PERIODIC_INTERNET,
    BLUETOOTH_ALWAYS,
}

@Entity(tableName = "local_services")
data class LocalService(
    // ex: "dtn://my-node/chat"
    @PrimaryKey val serviceEid: String,
    val displayName: String,
    val viewerType: ViewerType,
    val notificationSoundUri: String? = null,
    val vibrationPatternJson: String? = null,
    val defaultDestinationEid: String? = null,
    val isBroadcast: Boolean = false,
    val isNotificationEnabled: Boolean = true,
)

@Entity(tableName = "bundle_records")
data class BundleRecord(
    // Hash or UUID of Primary Block
    @PrimaryKey val bundleId: String,
    val destinationEid: String,
    val sourceEid: String,
    val creationTimestamp: Long,
    val sequenceNumber: Long,
    val lifetimeMs: Long,
    // Path to raw payload file
    val payloadFilePath: String,
    val state: BundleState,
    val isRead: Boolean,
    val bpsecStatus: BpsecStatus,
    val hopCount: Int,
)

@Entity(tableName = "routing_rules")
data class RoutingRule(
    // ex: "dtn://node-e/*"
    @PrimaryKey val destinationEidPattern: String,
    // ex: "dtn://node-b"
    val nextHopEid: String,
)

@Entity(tableName = "convergence_profiles")
data class ConvergenceProfile(
    @PrimaryKey val profileId: String,
    val name: String,
    val triggerType: TriggerType,
    // IP/Port or Bluetooth MAC
    val targetAddress: String,
    // Wifi SSID or interval in mins
    val triggerCondition: String? = null,
    val isPaused: Boolean = false,
)

@Entity(tableName = "bpsec_keys")
data class BpsecKey(
    // Remote node EID
    @PrimaryKey val nodeEid: String,
    // Shared HMAC key
    val secretKey: ByteArray,
    val algorithm: String = "HmacSHA256",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BpsecKey
        if (nodeEid != other.nodeEid) return false
        if (!secretKey.contentEquals(other.secretKey)) return false
        if (algorithm != other.algorithm) return false
        return true
    }

    override fun hashCode(): Int {
        var result = nodeEid.hashCode()
        result = 31 * result + secretKey.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        return result
    }
}

@Entity(tableName = "system_logs")
data class SystemLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    // INFO, WARN, ERROR
    val level: String,
    val message: String,
)
