package io.github.loic_fejoz.dtn_android_messenger.util

import android.content.Context
import android.content.SharedPreferences
import io.github.loic_fejoz.dtn_android_messenger.BuildConfig
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
    const val ENCRYPTED_BACKUP_VERSION = 2
    private const val PBKDF2_ITERATIONS = 120_000
    private const val MIN_PBKDF2_ITERATIONS = 10_000
    private const val MAX_PBKDF2_ITERATIONS = 1_000_000
    const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun bytesToHex(bytes: ByteArray): String = HexUtils.bytesToHex(bytes)

    fun hexToBytes(hex: String): ByteArray = HexUtils.hexToBytes(hex)

    private fun base64Decode(encoded: String): ByteArray = Base64Utils.decode(encoded)

    private fun deriveKey(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
    ): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val keyBytes =
            try {
                factory.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        return try {
            SecretKeySpec(keyBytes, "AES")
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun encryptEnvelope(
        plaintextJson: String,
        passphrase: String,
    ): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintextJson.toByteArray(Charsets.UTF_8))

        val envelope = JSONObject()
        envelope.put("version", ENCRYPTED_BACKUP_VERSION)
        envelope.put("encrypted", true)
        envelope.put("kdf", "PBKDF2WithHmacSHA256")
        envelope.put("iterations", PBKDF2_ITERATIONS)
        envelope.put("salt", PayloadUtils.base64Encode(salt))
        envelope.put("iv", PayloadUtils.base64Encode(iv))
        envelope.put("ciphertext", PayloadUtils.base64Encode(ciphertext))
        return envelope.toString(2)
    }

    private fun decryptEnvelope(
        envelope: JSONObject,
        passphrase: String,
    ): String {
        val iterations = envelope.optInt("iterations", PBKDF2_ITERATIONS)
        require(iterations in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
            "Unsupported PBKDF2 iteration count: $iterations"
        }
        val salt = base64Decode(envelope.getString("salt"))
        val iv = base64Decode(envelope.getString("iv"))
        require(salt.size == SALT_BYTES) { "Invalid backup salt length: ${salt.size}" }
        require(iv.size == IV_BYTES) { "Invalid backup IV length: ${iv.size}" }
        val ciphertext = base64Decode(envelope.getString("ciphertext"))
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return try {
            String(plaintext, Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    fun isEncryptedBackup(jsonString: String): Boolean {
        return try {
            JSONObject(jsonString).optBoolean("encrypted", false)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun exportConfigJson(
        context: Context,
        localServiceDao: LocalServiceDao,
        convergenceProfileDao: ConvergenceProfileDao,
        routingRuleDao: RoutingRuleDao,
        bpsecKeyDao: BpsecKeyDao,
        senmlEntryDao: SenmlEntryDao,
        prefs: SharedPreferences = PreferencesHelper.getEncryptedSharedPreferences(context),
        passphrase: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val root = JSONObject()
            root.put("version", CURRENT_BACKUP_VERSION)
            root.put("appVersion", BuildConfig.VERSION_NAME)
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
                        throw IllegalStateException("Failed to decrypt BPSec key for ${k.nodeEid}: ${e.message}", e)
                    }
                try {
                    obj.put("secretKeyHex", bytesToHex(decryptedBytes))
                } finally {
                    decryptedBytes.fill(0)
                }
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

            val plaintextJson = root.toString(2)
            if (!passphrase.isNullOrEmpty()) {
                encryptEnvelope(plaintextJson, passphrase)
            } else {
                plaintextJson
            }
        }

    private data class ParsedBackup(
        val preferences: Map<String, Any>,
        val services: List<LocalService>,
        val profiles: List<ConvergenceProfile>,
        val rules: List<RoutingRule>,
        val keys: List<BpsecKey>,
        val senmlEntries: List<SenmlEntry>,
    )

    private fun parseAndValidateBackup(
        jsonString: String,
        passphrase: String?,
    ): ParsedBackup {
        val outerRoot = JSONObject(jsonString)
        val root =
            if (outerRoot.optBoolean("encrypted", false)) {
                require(!passphrase.isNullOrEmpty()) { "Backup is encrypted; passphrase is required" }
                val decryptedJson =
                    try {
                        decryptEnvelope(outerRoot, passphrase)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Failed to decrypt backup (wrong passphrase or corrupted file)", e)
                    }
                JSONObject(decryptedJson)
            } else {
                outerRoot
            }

        val parsedPrefs = mutableMapOf<String, Any>()
        if (root.has("preferences")) {
            val prefsObj = root.getJSONObject("preferences")
            val keys = prefsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = prefsObj.get(key)
                if (value is Boolean || value is Int || value is Long || value is Double || value is String) {
                    parsedPrefs[key] = value
                }
            }
        }

        val parsedServices = mutableListOf<LocalService>()
        if (root.has("localServices")) {
            val arr = root.getJSONArray("localServices")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val viewerTypeName = obj.optString("viewerType", ViewerType.CHAT.name)
                val viewerType = runCatching { ViewerType.valueOf(viewerTypeName) }.getOrDefault(ViewerType.CHAT)
                parsedServices.add(
                    LocalService(
                        serviceEid = obj.getString("serviceEid"),
                        displayName = obj.getString("displayName"),
                        viewerType = viewerType,
                        notificationSoundUri = if (obj.has("notificationSoundUri") && !obj.isNull("notificationSoundUri")) obj.getString("notificationSoundUri") else null,
                        vibrationPatternJson = if (obj.has("vibrationPatternJson") && !obj.isNull("vibrationPatternJson")) obj.getString("vibrationPatternJson") else null,
                        defaultDestinationEid = if (obj.has("defaultDestinationEid") && !obj.isNull("defaultDestinationEid")) obj.getString("defaultDestinationEid") else null,
                        isBroadcast = obj.optBoolean("isBroadcast", false),
                        isNotificationEnabled = obj.optBoolean("isNotificationEnabled", true),
                    ),
                )
            }
        }

        val parsedProfiles = mutableListOf<ConvergenceProfile>()
        if (root.has("convergenceProfiles")) {
            val arr = root.getJSONArray("convergenceProfiles")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val triggerTypeName = obj.optString("triggerType", TriggerType.WIFI_SSID.name)
                val triggerType = runCatching { TriggerType.valueOf(triggerTypeName) }.getOrDefault(TriggerType.WIFI_SSID)
                parsedProfiles.add(
                    ConvergenceProfile(
                        profileId = obj.getString("profileId"),
                        name = obj.getString("name"),
                        triggerType = triggerType,
                        targetAddress = obj.getString("targetAddress"),
                        triggerCondition = if (obj.has("triggerCondition") && !obj.isNull("triggerCondition")) obj.getString("triggerCondition") else null,
                        isPaused = obj.optBoolean("isPaused", false),
                    ),
                )
            }
        }

        val parsedRules = mutableListOf<RoutingRule>()
        if (root.has("routingRules")) {
            val arr = root.getJSONArray("routingRules")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                parsedRules.add(
                    RoutingRule(
                        destinationEidPattern = obj.getString("destinationEidPattern"),
                        nextHopEid = obj.getString("nextHopEid"),
                    ),
                )
            }
        }

        val parsedKeys = mutableListOf<BpsecKey>()
        if (root.has("bpsecKeys")) {
            val arr = root.getJSONArray("bpsecKeys")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val nodeEid = obj.getString("nodeEid")
                val secretKeyHex = obj.getString("secretKeyHex")
                val algorithm = obj.optString("algorithm", "HmacSHA256")
                val plainBytes = hexToBytes(secretKeyHex)
                val encryptedKey =
                    try {
                        CryptoManager.encrypt(plainBytes)
                    } finally {
                        plainBytes.fill(0)
                    }
                parsedKeys.add(
                    BpsecKey(
                        nodeEid = nodeEid,
                        secretKey = encryptedKey,
                        algorithm = algorithm,
                    ),
                )
            }
        }

        val parsedSenml = mutableListOf<SenmlEntry>()
        if (root.has("senmlEntries")) {
            val arr = root.getJSONArray("senmlEntries")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                parsedSenml.add(
                    SenmlEntry(
                        serviceEid = obj.getString("serviceEid"),
                        name = obj.getString("name"),
                        customLabel = if (obj.has("customLabel") && !obj.isNull("customLabel")) obj.getString("customLabel") else null,
                        value = obj.getString("value"),
                        unit = obj.getString("unit"),
                        timestamp = obj.getLong("timestamp"),
                        displayOrder = obj.optInt("displayOrder", 0),
                        isDeleted = obj.optBoolean("isDeleted", false),
                    ),
                )
            }
        }

        return ParsedBackup(
            preferences = parsedPrefs,
            services = parsedServices,
            profiles = parsedProfiles,
            rules = parsedRules,
            keys = parsedKeys,
            senmlEntries = parsedSenml,
        )
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
        passphrase: String? = null,
        runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    ): ImportResult =
        withContext(Dispatchers.IO) {
            // Parse & validate all entities before any destructive clear or DB mutation
            val parsed = parseAndValidateBackup(jsonString, passphrase)

            runInTransaction {
                if (clearExisting) {
                    localServiceDao.deleteAll()
                    convergenceProfileDao.deleteAll()
                    routingRuleDao.deleteAll()
                    bpsecKeyDao.deleteAll()
                    senmlEntryDao.deleteAll()
                }

                parsed.services.forEach { localServiceDao.insert(it) }
                parsed.profiles.forEach { convergenceProfileDao.insert(it) }
                parsed.rules.forEach { routingRuleDao.insert(it) }
                parsed.keys.forEach { bpsecKeyDao.insert(it) }
                parsed.senmlEntries.forEach { senmlEntryDao.insertOrUpdate(it) }
            }

            if (parsed.preferences.isNotEmpty()) {
                val editor = prefs.edit()
                for ((key, value) in parsed.preferences) {
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is String -> editor.putString(key, value)
                    }
                }
                editor.apply()
            }

            ImportResult(
                servicesCount = parsed.services.size,
                profilesCount = parsed.profiles.size,
                rulesCount = parsed.rules.size,
                keysCount = parsed.keys.size,
                senmlCount = parsed.senmlEntries.size,
                preferencesCount = parsed.preferences.size,
            )
        }

    fun readBoundedUtf8(
        inputStream: InputStream,
        maxBytes: Int = MAX_BACKUP_BYTES,
    ): String {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            total += read
            require(total <= maxBytes) {
                "Backup file exceeds maximum allowed size ($maxBytes bytes)"
            }
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
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
        passphrase: String? = null,
    ) = withContext(Dispatchers.IO) {
        val json = exportConfigJson(context, localServiceDao, convergenceProfileDao, routingRuleDao, bpsecKeyDao, senmlEntryDao, prefs, passphrase)
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
        passphrase: String? = null,
        runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val json = readBoundedUtf8(inputStream)
            importConfigJson(json, context, localServiceDao, convergenceProfileDao, routingRuleDao, bpsecKeyDao, senmlEntryDao, prefs, clearExisting, passphrase, runInTransaction)
        }
}
