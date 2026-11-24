package io.github.loic_fejoz.dtn_android_messenger.util

import android.content.Context
import android.content.SharedPreferences
import io.github.loic_fejoz.dtn_android_messenger.data.dao.BpsecKeyDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.ConvergenceProfileDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.LocalServiceDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.RoutingRuleDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.SenmlEntryDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.BpsecKey
import io.github.loic_fejoz.dtn_android_messenger.data.model.ConvergenceProfile
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import io.github.loic_fejoz.dtn_android_messenger.data.model.RoutingRule
import io.github.loic_fejoz.dtn_android_messenger.data.model.SenmlEntry
import io.github.loic_fejoz.dtn_android_messenger.data.model.TriggerType
import io.github.loic_fejoz.dtn_android_messenger.data.model.ViewerType
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

data class ImportResult(
    val servicesCount: Int = 0,
    val profilesCount: Int = 0,
    val rulesCount: Int = 0,
    val keysCount: Int = 0,
    val senmlCount: Int = 0,
    val preferencesCount: Int = 0,
) {
    val totalCount: Int
        get() = servicesCount + profilesCount + rulesCount + keysCount + senmlCount + preferencesCount

    val summary: String
        get() = "Imported $servicesCount services, $profilesCount profiles, $rulesCount rules, $keysCount BPSec keys, $senmlCount sensor entries, $preferencesCount preferences."
}

object ConfigBackupManager {
    const val CURRENT_BACKUP_VERSION = 1

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.isEmpty()) return ByteArray(0)
        require(clean.length % 2 == 0) { "Hex string length must be even" }
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    suspend fun exportConfigJson(
        context: Context,
        localServiceDao: LocalServiceDao,
        convergenceProfileDao: ConvergenceProfileDao,
        routingRuleDao: RoutingRuleDao,
        bpsecKeyDao: BpsecKeyDao,
        senmlEntryDao: SenmlEntryDao,
        prefs: SharedPreferences = PreferencesHelper.getEncryptedSharedPreferences(context),
    ): String {
        val root = JSONObject()
        root.put("version", CURRENT_BACKUP_VERSION)
        root.put("appVersion", "1.0")
        root.put("exportedAt", System.currentTimeMillis())

        // 1. Preferences
        val prefsObj = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                is Boolean -> prefsObj.put(key, value)
                is Int -> prefsObj.put(key, value)
                is Long -> prefsObj.put(key, value)
                is Float -> prefsObj.put(key, value.toDouble())
                is Double -> prefsObj.put(key, value)
                is String -> prefsObj.put(key, value)
            }
        }
        root.put("preferences", prefsObj)

        // 2. Local Services
        val services = localServiceDao.getAllList()
        val servicesArr = JSONArray()
        for (s in services) {
            val obj = JSONObject()
            obj.put("serviceEid", s.serviceEid)
            obj.put("displayName", s.displayName)
            obj.put("viewerType", s.viewerType.name)
            obj.put("notificationSoundUri", s.notificationSoundUri ?: JSONObject.NULL)
            obj.put("vibrationPatternJson", s.vibrationPatternJson ?: JSONObject.NULL)
            obj.put("defaultDestinationEid", s.defaultDestinationEid ?: JSONObject.NULL)
            obj.put("isBroadcast", s.isBroadcast)
            obj.put("isNotificationEnabled", s.isNotificationEnabled)
            servicesArr.put(obj)
        }
        root.put("localServices", servicesArr)

        // 3. Convergence Profiles
        val profiles = convergenceProfileDao.getAllList()
        val profilesArr = JSONArray()
        for (p in profiles) {
            val obj = JSONObject()
            obj.put("profileId", p.profileId)
            obj.put("name", p.name)
            obj.put("triggerType", p.triggerType.name)
            obj.put("targetAddress", p.targetAddress)
            obj.put("triggerCondition", p.triggerCondition ?: JSONObject.NULL)
            obj.put("isPaused", p.isPaused)
            profilesArr.put(obj)
        }
        root.put("convergenceProfiles", profilesArr)

        // 4. Routing Rules
        val rules = routingRuleDao.getAllList()
        val rulesArr = JSONArray()
        for (r in rules) {
            val obj = JSONObject()
            obj.put("destinationEidPattern", r.destinationEidPattern)
            obj.put("nextHopEid", r.nextHopEid)
            rulesArr.put(obj)
        }
        root.put("routingRules", rulesArr)

        // 5. BPSec Keys (decrypted to plain hex so it can be re-encrypted upon restore)
        val keys = bpsecKeyDao.getAllList()
        val keysArr = JSONArray()
        for (k in keys) {
            val obj = JSONObject()
            obj.put("nodeEid", k.nodeEid)
            val decryptedBytes =
                try {
                    CryptoManager.decrypt(k.secretKey)
                } catch (e: Exception) {
                    k.secretKey
                }
            obj.put("secretKeyHex", bytesToHex(decryptedBytes))
            obj.put("algorithm", k.algorithm)
            keysArr.put(obj)
        }
        root.put("bpsecKeys", keysArr)

        // 6. SenML Sensor Entries
        val senmlList = senmlEntryDao.getAllList()
        val senmlArr = JSONArray()
        for (entry in senmlList) {
            val obj = JSONObject()
            obj.put("serviceEid", entry.serviceEid)
            obj.put("name", entry.name)
            obj.put("customLabel", entry.customLabel ?: JSONObject.NULL)
            obj.put("value", entry.value)
            obj.put("unit", entry.unit)
            obj.put("timestamp", entry.timestamp)
            obj.put("displayOrder", entry.displayOrder)
            obj.put("isDeleted", entry.isDeleted)
            senmlArr.put(obj)
        }
        root.put("senmlEntries", senmlArr)

        return root.toString(2)
    }

    suspend fun importConfigJson(
        jsonString: String,
        context: Context,
        localServiceDao: LocalServiceDao,
        convergenceProfileDao: ConvergenceProfileDao,
        routingRuleDao: RoutingRuleDao,
        bpsecKeyDao: BpsecKeyDao,
        senmlEntryDao: SenmlEntryDao,
        prefs: SharedPreferences = PreferencesHelper.getEncryptedSharedPreferences(context),
        clearExisting: Boolean = false,
    ): ImportResult {
        val root = JSONObject(jsonString)

        if (clearExisting) {
            localServiceDao.deleteAll()
            convergenceProfileDao.deleteAll()
            routingRuleDao.deleteAll()
            bpsecKeyDao.deleteAll()
            senmlEntryDao.deleteAll()
        }

        var prefCount = 0
        if (root.has("preferences")) {
            val prefsObj = root.getJSONObject("preferences")
            val editor = prefs.edit()
            val keys = prefsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = prefsObj.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    is String -> editor.putString(key, value)
                }
                prefCount++
            }
            editor.apply()
        }

        var servicesCount = 0
        if (root.has("localServices")) {
            val arr = root.getJSONArray("localServices")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val viewerTypeName = obj.optString("viewerType", ViewerType.CHAT.name)
                val viewerType = runCatching { ViewerType.valueOf(viewerTypeName) }.getOrDefault(ViewerType.CHAT)
                val service =
                    LocalService(
                        serviceEid = obj.getString("serviceEid"),
                        displayName = obj.getString("displayName"),
                        viewerType = viewerType,
                        notificationSoundUri = if (obj.has("notificationSoundUri") && !obj.isNull("notificationSoundUri")) obj.getString("notificationSoundUri") else null,
                        vibrationPatternJson = if (obj.has("vibrationPatternJson") && !obj.isNull("vibrationPatternJson")) obj.getString("vibrationPatternJson") else null,
                        defaultDestinationEid = if (obj.has("defaultDestinationEid") && !obj.isNull("defaultDestinationEid")) obj.getString("defaultDestinationEid") else null,
                        isBroadcast = obj.optBoolean("isBroadcast", false),
                        isNotificationEnabled = obj.optBoolean("isNotificationEnabled", true),
                    )
                localServiceDao.insert(service)
                servicesCount++
            }
        }

        var profilesCount = 0
        if (root.has("convergenceProfiles")) {
            val arr = root.getJSONArray("convergenceProfiles")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val triggerTypeName = obj.optString("triggerType", TriggerType.WIFI_SSID.name)
                val triggerType = runCatching { TriggerType.valueOf(triggerTypeName) }.getOrDefault(TriggerType.WIFI_SSID)
                val profile =
                    ConvergenceProfile(
                        profileId = obj.getString("profileId"),
                        name = obj.getString("name"),
                        triggerType = triggerType,
                        targetAddress = obj.getString("targetAddress"),
                        triggerCondition = if (obj.has("triggerCondition") && !obj.isNull("triggerCondition")) obj.getString("triggerCondition") else null,
                        isPaused = obj.optBoolean("isPaused", false),
                    )
                convergenceProfileDao.insert(profile)
                profilesCount++
            }
        }

        var rulesCount = 0
        if (root.has("routingRules")) {
            val arr = root.getJSONArray("routingRules")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rule =
                    RoutingRule(
                        destinationEidPattern = obj.getString("destinationEidPattern"),
                        nextHopEid = obj.getString("nextHopEid"),
                    )
                routingRuleDao.insert(rule)
                rulesCount++
            }
        }

        var keysCount = 0
        if (root.has("bpsecKeys")) {
            val arr = root.getJSONArray("bpsecKeys")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val nodeEid = obj.getString("nodeEid")
                val secretKeyHex = obj.getString("secretKeyHex")
                val algorithm = obj.optString("algorithm", "HmacSHA256")
                val plainBytes = hexToBytes(secretKeyHex)
                val encryptedKey = CryptoManager.encrypt(plainBytes)
                val key =
                    BpsecKey(
                        nodeEid = nodeEid,
                        secretKey = encryptedKey,
                        algorithm = algorithm,
                    )
                bpsecKeyDao.insert(key)
                keysCount++
            }
        }

        var senmlCount = 0
        if (root.has("senmlEntries")) {
            val arr = root.getJSONArray("senmlEntries")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val entry =
                    SenmlEntry(
                        serviceEid = obj.getString("serviceEid"),
                        name = obj.getString("name"),
                        customLabel = if (obj.has("customLabel") && !obj.isNull("customLabel")) obj.getString("customLabel") else null,
                        value = obj.getString("value"),
                        unit = obj.getString("unit"),
                        timestamp = obj.getLong("timestamp"),
                        displayOrder = obj.optInt("displayOrder", 0),
                        isDeleted = obj.optBoolean("isDeleted", false),
                    )
                senmlEntryDao.insertOrUpdate(entry)
                senmlCount++
            }
        }

        return ImportResult(
            servicesCount = servicesCount,
            profilesCount = profilesCount,
            rulesCount = rulesCount,
            keysCount = keysCount,
            senmlCount = senmlCount,
            preferencesCount = prefCount,
        )
    }

    suspend fun exportToStream(
        outputStream: OutputStream,
        context: Context,
        localServiceDao: LocalServiceDao,
        convergenceProfileDao: ConvergenceProfileDao,
        routingRuleDao: RoutingRuleDao,
        bpsecKeyDao: BpsecKeyDao,
        senmlEntryDao: SenmlEntryDao,
        prefs: SharedPreferences = PreferencesHelper.getEncryptedSharedPreferences(context),
    ) {
        val json = exportConfigJson(context, localServiceDao, convergenceProfileDao, routingRuleDao, bpsecKeyDao, senmlEntryDao, prefs)
        outputStream.write(json.toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    suspend fun importFromStream(
        inputStream: InputStream,
        context: Context,
        localServiceDao: LocalServiceDao,
        convergenceProfileDao: ConvergenceProfileDao,
        routingRuleDao: RoutingRuleDao,
        bpsecKeyDao: BpsecKeyDao,
        senmlEntryDao: SenmlEntryDao,
        prefs: SharedPreferences = PreferencesHelper.getEncryptedSharedPreferences(context),
        clearExisting: Boolean = false,
    ): ImportResult {
        val json = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return importConfigJson(json, context, localServiceDao, convergenceProfileDao, routingRuleDao, bpsecKeyDao, senmlEntryDao, prefs, clearExisting)
    }
}
