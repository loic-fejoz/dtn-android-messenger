package io.github.loic_fejoz.dtn_android_messenger.util

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.loic_fejoz.dtn_android_messenger.data.dao.*
import io.github.loic_fejoz.dtn_android_messenger.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ConfigBackupManagerTest {

    private val inMemoryPrefsMap = mutableMapOf<String, Any>()

    private val fakePrefs = object : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = inMemoryPrefsMap.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (inMemoryPrefsMap[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (inMemoryPrefsMap[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (inMemoryPrefsMap[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (inMemoryPrefsMap[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (inMemoryPrefsMap[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = inMemoryPrefsMap.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null && value != null) inMemoryPrefsMap[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) inMemoryPrefsMap[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) inMemoryPrefsMap[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) inMemoryPrefsMap[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) inMemoryPrefsMap[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) inMemoryPrefsMap.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                inMemoryPrefsMap.clear()
                return this
            }
            override fun apply() {}
            override fun commit(): Boolean = true
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private val fakeContext: Context = object : ContextWrapper(null) {}

    // In-memory DAOs
    private val servicesList = mutableListOf<LocalService>()
    private val fakeServiceDao = object : LocalServiceDao {
        override fun getAll(): Flow<List<LocalService>> = emptyFlow()
        override suspend fun getAllList(): List<LocalService> = servicesList.toList()
        override suspend fun insert(service: LocalService) {
            servicesList.removeAll { it.serviceEid == service.serviceEid }
            servicesList.add(service)
        }
        override suspend fun delete(service: LocalService) {
            servicesList.remove(service)
        }
        override suspend fun getById(serviceEid: String): LocalService? = servicesList.find { it.serviceEid == serviceEid }
        override suspend fun deleteAll() {
            servicesList.clear()
        }
    }

    private val profilesList = mutableListOf<ConvergenceProfile>()
    private val fakeProfileDao = object : ConvergenceProfileDao {
        override fun getAll(): Flow<List<ConvergenceProfile>> = emptyFlow()
        override suspend fun getAllList(): List<ConvergenceProfile> = profilesList.toList()
        override suspend fun insert(profile: ConvergenceProfile) {
            profilesList.removeAll { it.profileId == profile.profileId }
            profilesList.add(profile)
        }
        override suspend fun delete(profile: ConvergenceProfile) {
            profilesList.remove(profile)
        }
        override suspend fun deleteAll() {
            profilesList.clear()
        }
    }

    private val rulesList = mutableListOf<RoutingRule>()
    private val fakeRuleDao = object : RoutingRuleDao {
        override fun getAll(): Flow<List<RoutingRule>> = emptyFlow()
        override suspend fun getAllList(): List<RoutingRule> = rulesList.toList()
        override suspend fun insert(rule: RoutingRule) {
            rulesList.removeAll { it.destinationEidPattern == rule.destinationEidPattern }
            rulesList.add(rule)
        }
        override suspend fun delete(rule: RoutingRule) {
            rulesList.remove(rule)
        }
        override suspend fun deleteAll() {
            rulesList.clear()
        }
    }

    private val keysList = mutableListOf<BpsecKey>()
    private val fakeKeyDao = object : BpsecKeyDao {
        override fun getAll(): Flow<List<BpsecKey>> = emptyFlow()
        override suspend fun getAllList(): List<BpsecKey> = keysList.toList()
        override suspend fun insert(key: BpsecKey) {
            keysList.removeAll { it.nodeEid == key.nodeEid }
            keysList.add(key)
        }
        override suspend fun delete(key: BpsecKey) {
            keysList.remove(key)
        }
        override suspend fun getByKeyId(nodeEid: String): BpsecKey? = keysList.find { it.nodeEid == nodeEid }
        override suspend fun deleteAll() {
            keysList.clear()
        }
    }

    private val senmlList = mutableListOf<SenmlEntry>()
    private val fakeSenmlDao = object : SenmlEntryDao {
        override fun getActiveEntries(serviceEid: String): Flow<List<SenmlEntry>> = emptyFlow()
        override suspend fun getActiveEntriesList(serviceEid: String): List<SenmlEntry> =
            senmlList.filter { it.serviceEid == serviceEid && !it.isDeleted }
        override suspend fun getAllEntriesForService(serviceEid: String): List<SenmlEntry> =
            senmlList.filter { it.serviceEid == serviceEid }
        override suspend fun getAllList(): List<SenmlEntry> = senmlList.toList()
        override suspend fun getEntry(serviceEid: String, name: String): SenmlEntry? =
            senmlList.find { it.serviceEid == serviceEid && it.name == name }
        override suspend fun insertOrUpdate(entry: SenmlEntry) {
            senmlList.removeAll { it.serviceEid == entry.serviceEid && it.name == entry.name }
            senmlList.add(entry)
        }
        override suspend fun deleteEntry(serviceEid: String, name: String) {
            senmlList.removeAll { it.serviceEid == serviceEid && it.name == name }
        }
        override suspend fun deleteAllForService(serviceEid: String) {
            senmlList.removeAll { it.serviceEid == serviceEid }
        }
        override suspend fun deleteAll() {
            senmlList.clear()
        }
        override suspend fun markDeleted(serviceEid: String, name: String) {}
        override suspend fun updateCustomLabel(serviceEid: String, name: String, customLabel: String?) {}
        override suspend fun updateOrder(serviceEid: String, name: String, order: Int) {}
        override suspend fun getMaxOrder(serviceEid: String): Int? = senmlList.maxOfOrNull { it.displayOrder }
    }

    @BeforeEach
    fun setUp() {
        inMemoryPrefsMap.clear()
        servicesList.clear()
        profilesList.clear()
        rulesList.clear()
        keysList.clear()
        senmlList.clear()

        // Mock CryptoManager with reversible pseudo-cipher for tests
        CryptoManager.encryptOverride = { bytes -> byteArrayOf(0xEE.toByte()) + bytes }
        CryptoManager.decryptOverride = { bytes ->
            if (bytes.isNotEmpty() && bytes[0] == 0xEE.toByte()) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        }
    }

    @AfterEach
    fun tearDown() {
        CryptoManager.encryptOverride = null
        CryptoManager.decryptOverride = null
    }

    @Test
    fun testHexConversion() {
        val testBytes = byteArrayOf(0x00, 0x01, 0x0A, 0xFF.toByte())
        val hex = ConfigBackupManager.bytesToHex(testBytes)
        assertEquals("00010aff", hex)

        val restored = ConfigBackupManager.hexToBytes(hex)
        assertArrayEquals(testBytes, restored)

        assertEquals(0, ConfigBackupManager.hexToBytes("").size)
        assertThrows(IllegalArgumentException::class.java) {
            ConfigBackupManager.hexToBytes("abc")
        }
    }

    @Test
    fun testExportAndImportRoundtrip() = runBlocking {
        // Setup initial state
        fakePrefs.edit()
            .putString("local_node_name", "dtn://my-custom-node")
            .putInt(PreferencesHelper.PREF_MAX_BUNDLE_SIZE_MB, 25)
            .putString("bpsec_policy", "required")
            .apply()

        fakeServiceDao.insert(
            LocalService(
                serviceEid = "dtn://my-custom-node/chat",
                displayName = "Custom Chat",
                viewerType = ViewerType.CHAT,
                isBroadcast = false,
                isNotificationEnabled = true,
            )
        )
        fakeProfileDao.insert(
            ConvergenceProfile(
                profileId = "dtn://remote-1",
                name = "Remote Node 1",
                triggerType = TriggerType.PERIODIC_INTERNET,
                targetAddress = "192.168.1.100:4556",
                triggerCondition = "30",
            )
        )
        fakeRuleDao.insert(
            RoutingRule(
                destinationEidPattern = "dtn://station-b/*",
                nextHopEid = "dtn://remote-1",
            )
        )
        // Insert key encrypted with test cipher
        val rawKey = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val encryptedKey = CryptoManager.encrypt(rawKey)
        fakeKeyDao.insert(
            BpsecKey(
                nodeEid = "dtn://remote-1",
                secretKey = encryptedKey,
                algorithm = "HmacSHA256",
            )
        )
        fakeSenmlDao.insertOrUpdate(
            SenmlEntry(
                serviceEid = "dtn://my-custom-node/sensors",
                name = "temp",
                customLabel = "Inside Temp",
                value = "23.4",
                unit = "Cel",
                timestamp = 123456789L,
            )
        )

        // 1. Export
        val json = ConfigBackupManager.exportConfigJson(
            context = fakeContext,
            localServiceDao = fakeServiceDao,
            convergenceProfileDao = fakeProfileDao,
            routingRuleDao = fakeRuleDao,
            bpsecKeyDao = fakeKeyDao,
            senmlEntryDao = fakeSenmlDao,
            prefs = fakePrefs,
        )

        val root = JSONObject(json)
        assertEquals(1, root.getInt("version"))
        assertEquals("dtn://my-custom-node", root.getJSONObject("preferences").getString("local_node_name"))
        assertEquals(1, root.getJSONArray("localServices").length())
        assertEquals(1, root.getJSONArray("convergenceProfiles").length())
        assertEquals(1, root.getJSONArray("routingRules").length())
        assertEquals(1, root.getJSONArray("bpsecKeys").length())
        assertEquals("cafebabe", root.getJSONArray("bpsecKeys").getJSONObject(0).getString("secretKeyHex"))
        assertEquals(1, root.getJSONArray("senmlEntries").length())

        // 2. Wipe everything to simulate fresh install
        fakePrefs.edit().clear().apply()
        fakeServiceDao.deleteAll()
        fakeProfileDao.deleteAll()
        fakeRuleDao.deleteAll()
        fakeKeyDao.deleteAll()
        fakeSenmlDao.deleteAll()

        assertEquals(0, servicesList.size)
        assertEquals(0, profilesList.size)
        assertEquals(0, rulesList.size)
        assertEquals(0, keysList.size)
        assertEquals(0, senmlList.size)
        assertFalse(fakePrefs.contains("local_node_name"))

        // 3. Import
        val result = ConfigBackupManager.importConfigJson(
            jsonString = json,
            context = fakeContext,
            localServiceDao = fakeServiceDao,
            convergenceProfileDao = fakeProfileDao,
            routingRuleDao = fakeRuleDao,
            bpsecKeyDao = fakeKeyDao,
            senmlEntryDao = fakeSenmlDao,
            prefs = fakePrefs,
            clearExisting = false,
        )

        assertEquals(1, result.servicesCount)
        assertEquals(1, result.profilesCount)
        assertEquals(1, result.rulesCount)
        assertEquals(1, result.keysCount)
        assertEquals(1, result.senmlCount)
        assertTrue(result.preferencesCount >= 3)

        // 4. Verify restored state
        assertEquals("dtn://my-custom-node", fakePrefs.getString("local_node_name", null))
        assertEquals(25, fakePrefs.getInt(PreferencesHelper.PREF_MAX_BUNDLE_SIZE_MB, 0))
        assertEquals("required", fakePrefs.getString("bpsec_policy", null))

        val restoredService = fakeServiceDao.getById("dtn://my-custom-node/chat")
        assertNotNull(restoredService)
        assertEquals("Custom Chat", restoredService?.displayName)
        assertEquals(ViewerType.CHAT, restoredService?.viewerType)

        val restoredProfile = fakeProfileDao.getAllList().firstOrNull()
        assertNotNull(restoredProfile)
        assertEquals("Remote Node 1", restoredProfile?.name)
        assertEquals("192.168.1.100:4556", restoredProfile?.targetAddress)

        val restoredRule = fakeRuleDao.getAllList().firstOrNull()
        assertNotNull(restoredRule)
        assertEquals("dtn://station-b/*", restoredRule?.destinationEidPattern)
        assertEquals("dtn://remote-1", restoredRule?.nextHopEid)

        val restoredKey = fakeKeyDao.getByKeyId("dtn://remote-1")
        assertNotNull(restoredKey)
        val decryptedRestored = CryptoManager.decrypt(restoredKey!!.secretKey)
        assertArrayEquals(rawKey, decryptedRestored)

        val restoredSenml = fakeSenmlDao.getEntry("dtn://my-custom-node/sensors", "temp")
        assertNotNull(restoredSenml)
        assertEquals("Inside Temp", restoredSenml?.customLabel)
        assertEquals("23.4", restoredSenml?.value)
    }

    @Test
    fun testStreamExportAndImport() = runBlocking {
        fakePrefs.edit().putString("local_node_name", "dtn://stream-test").apply()
        fakeServiceDao.insert(
            LocalService(
                serviceEid = "dtn://stream-test/files",
                displayName = "Files",
                viewerType = ViewerType.BUNDLE_LIST,
            )
        )

        val outStream = ByteArrayOutputStream()
        ConfigBackupManager.exportToStream(
            outputStream = outStream,
            context = fakeContext,
            localServiceDao = fakeServiceDao,
            convergenceProfileDao = fakeProfileDao,
            routingRuleDao = fakeRuleDao,
            bpsecKeyDao = fakeKeyDao,
            senmlEntryDao = fakeSenmlDao,
            prefs = fakePrefs,
        )

        val bytes = outStream.toByteArray()
        assertTrue(bytes.isNotEmpty())

        // Clear
        fakeServiceDao.deleteAll()
        fakePrefs.edit().clear().apply()

        val inStream = ByteArrayInputStream(bytes)
        val importResult = ConfigBackupManager.importFromStream(
            inputStream = inStream,
            context = fakeContext,
            localServiceDao = fakeServiceDao,
            convergenceProfileDao = fakeProfileDao,
            routingRuleDao = fakeRuleDao,
            bpsecKeyDao = fakeKeyDao,
            senmlEntryDao = fakeSenmlDao,
            prefs = fakePrefs,
        )

        assertEquals(1, importResult.servicesCount)
        assertEquals("dtn://stream-test", fakePrefs.getString("local_node_name", null))
        assertNotNull(fakeServiceDao.getById("dtn://stream-test/files"))
    }

    @Test
    fun testClearExistingFlag() = runBlocking {
        // Populate dummy initial state
        fakeServiceDao.insert(
            LocalService(
                serviceEid = "dtn://old/chat",
                displayName = "Old Chat",
                viewerType = ViewerType.CHAT,
            )
        )
        fakeProfileDao.insert(
            ConvergenceProfile(
                profileId = "dtn://old-profile",
                name = "Old Profile",
                triggerType = TriggerType.WIFI_SSID,
                targetAddress = "10.0.0.1:4556",
            )
        )

        val newBackupJson = """
            {
                "version": 1,
                "localServices": [
                    {
                        "serviceEid": "dtn://new/chat",
                        "displayName": "New Chat",
                        "viewerType": "CHAT"
                    }
                ]
            }
        """.trimIndent()

        // Import with clearExisting = true
        ConfigBackupManager.importConfigJson(
            jsonString = newBackupJson,
            context = fakeContext,
            localServiceDao = fakeServiceDao,
            convergenceProfileDao = fakeProfileDao,
            routingRuleDao = fakeRuleDao,
            bpsecKeyDao = fakeKeyDao,
            senmlEntryDao = fakeSenmlDao,
            prefs = fakePrefs,
            clearExisting = true,
        )

        assertNull(fakeServiceDao.getById("dtn://old/chat"))
        assertNotNull(fakeServiceDao.getById("dtn://new/chat"))
        assertEquals(0, profilesList.size)
    }
}
