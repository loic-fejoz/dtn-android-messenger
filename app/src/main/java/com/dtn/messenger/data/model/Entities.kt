package com.dtn.messenger.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ViewerType {
    CHAT, BUNDLE_LIST, MINIAPP, SENML_GRAPH
}

enum class BundleState {
    RECEIVED, OUTBOX, TRANSIT, DELIVERED
}

enum class BpsecStatus {
    VALID, INVALID, UNVERIFIED
}

enum class TriggerType {
    WIFI_SSID, PERIODIC_INTERNET, BLUETOOTH_ALWAYS
}

@Entity(tableName = "local_services")
data class LocalService(
    @PrimaryKey val serviceEid: String, // ex: "dtn://my-node/chat"
    val displayName: String,
    val viewerType: ViewerType,
    val notificationSoundUri: String? = null,
    val vibrationPatternJson: String? = null,
    val defaultDestinationEid: String? = null
)

@Entity(tableName = "bundle_records")
data class BundleRecord(
    @PrimaryKey val bundleId: String, // Hash or UUID of Primary Block
    val destinationEid: String,
    val sourceEid: String,
    val creationTimestamp: Long,
    val sequenceNumber: Long,
    val lifetimeMs: Long,
    val payloadFilePath: String, // Path to raw payload file
    val state: BundleState,
    val isRead: Boolean,
    val bpsecStatus: BpsecStatus,
    val hopCount: Int
)

@Entity(tableName = "routing_rules")
data class RoutingRule(
    @PrimaryKey val destinationEidPattern: String, // ex: "dtn://node-e/*"
    val nextHopEid: String // ex: "dtn://node-b"
)

@Entity(tableName = "convergence_profiles")
data class ConvergenceProfile(
    @PrimaryKey val profileId: String,
    val name: String,
    val triggerType: TriggerType,
    val targetAddress: String, // IP/Port or Bluetooth MAC
    val triggerCondition: String? = null, // Wifi SSID or interval in mins
    val isPaused: Boolean = false
)

@Entity(tableName = "bpsec_keys")
data class BpsecKey(
    @PrimaryKey val nodeEid: String, // Remote node EID
    val secretKey: ByteArray, // Shared HMAC key
    val algorithm: String = "HmacSHA256"
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
    val level: String, // INFO, WARN, ERROR
    val message: String
)
