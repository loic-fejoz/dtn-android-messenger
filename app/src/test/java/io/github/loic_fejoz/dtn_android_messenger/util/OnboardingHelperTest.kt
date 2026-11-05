package io.github.loic_fejoz.dtn_android_messenger.util

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.loic_fejoz.dtn_android_messenger.data.dao.ConvergenceProfileDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.LocalServiceDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.ConvergenceProfile
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import io.github.loic_fejoz.dtn_android_messenger.data.model.TriggerType
import io.github.loic_fejoz.dtn_android_messenger.data.model.ViewerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OnboardingHelperTest {

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

    @BeforeEach
    fun setUp() {
        inMemoryPrefsMap.clear()
        servicesList.clear()
        profilesList.clear()
        PreferencesHelper.setSharedPreferencesForTesting(fakePrefs)
    }

    @AfterEach
    fun tearDown() {
        PreferencesHelper.setSharedPreferencesForTesting(null)
    }

    @Test
    fun testNormalizeCallsign() {
        assertEquals("f4jxq", OnboardingHelper.normalizeCallsign(" F4JXQ "))
        assertEquals("n0call-9", OnboardingHelper.normalizeCallsign("N0CALL-9"))
        assertEquals("w1aw", OnboardingHelper.normalizeCallsign("W1AW"))
        assertEquals("my-node", OnboardingHelper.normalizeCallsign("  my-node  "))
    }

    @Test
    fun testConfigureWithCallsign() = runBlocking {
        OnboardingHelper.configureWithCallsign(
            callsign = "N0CALL-9",
            context = fakeContext,
            localServiceDao = fakeServiceDao,
            convergenceProfileDao = fakeProfileDao,
            prefs = fakePrefs,
        )

        // 1. Check local node EID
        assertEquals("dtn://n0call-9", fakePrefs.getString("local_node_name", null))

        // 2. Check standard 3 services
        assertEquals(3, servicesList.size)

        val chatService = fakeServiceDao.getById("dtn://n0call-9/chat")
        assertNotNull(chatService)
        assertEquals("Chat", chatService?.displayName)
        assertEquals(ViewerType.CHAT, chatService?.viewerType)
        assertTrue(chatService!!.isNotificationEnabled)
        assertFalse(chatService.isBroadcast)

        val filesService = fakeServiceDao.getById("dtn://n0call-9/files")
        assertNotNull(filesService)
        assertEquals("Files", filesService?.displayName)
        assertEquals(ViewerType.BUNDLE_LIST, filesService?.viewerType)
        assertTrue(filesService!!.isNotificationEnabled)
        assertFalse(filesService.isBroadcast)

        val senmlService = fakeServiceDao.getById("dtn://n0call-9/senml")
        assertNotNull(senmlService)
        assertEquals("SenML", senmlService?.displayName)
        assertEquals(ViewerType.SENML_LAST, senmlService?.viewerType)
        assertFalse(senmlService!!.isNotificationEnabled)
        assertFalse(senmlService.isBroadcast)

        // 3. Check default Hardy CLA profile
        val profiles = fakeProfileDao.getAllList()
        assertEquals(1, profiles.size)
        assertEquals(OnboardingHelper.DEFAULT_HARDY_PROFILE_ID, profiles[0].profileId)
        assertEquals(TriggerType.PERIODIC_INTERNET, profiles[0].triggerType)
        assertEquals(OnboardingHelper.DEFAULT_HARDY_ADDRESS, profiles[0].targetAddress)

        // 4. Check onboarding marked as completed
        assertTrue(PreferencesHelper.isOnboardingCompleted(fakeContext))
    }

    @Test
    fun testExistingProfilesNotOverwritten() = runBlocking {
        // Pre-existing profile
        fakeProfileDao.insert(
            ConvergenceProfile(
                profileId = "dtn://custom-node",
                name = "Custom",
                triggerType = TriggerType.WIFI_SSID,
                targetAddress = "10.0.0.1:4556",
            )
        )

        OnboardingHelper.configureWithCallsign(
            callsign = "W1AW",
            context = fakeContext,
            localServiceDao = fakeServiceDao,
            convergenceProfileDao = fakeProfileDao,
            prefs = fakePrefs,
        )

        val profiles = fakeProfileDao.getAllList()
        assertEquals(1, profiles.size)
        assertEquals("dtn://custom-node", profiles[0].profileId)
    }
}
