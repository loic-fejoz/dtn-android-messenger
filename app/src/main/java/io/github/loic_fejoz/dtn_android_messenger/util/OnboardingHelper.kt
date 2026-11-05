package io.github.loic_fejoz.dtn_android_messenger.util

import android.content.Context
import android.content.SharedPreferences
import io.github.loic_fejoz.dtn_android_messenger.data.dao.ConvergenceProfileDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.LocalServiceDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.ConvergenceProfile
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import io.github.loic_fejoz.dtn_android_messenger.data.model.TriggerType
import io.github.loic_fejoz.dtn_android_messenger.data.model.ViewerType
import java.util.Locale

object OnboardingHelper {
    const val DEFAULT_HARDY_PROFILE_ID = "dtn://f4jxq"
    const val DEFAULT_HARDY_ADDRESS = "44.27.131.233:4556"

    fun normalizeCallsign(rawCallsign: String): String {
        return rawCallsign.trim().lowercase(Locale.ROOT)
    }

    suspend fun configureWithCallsign(
        callsign: String,
        context: Context,
        localServiceDao: LocalServiceDao,
        convergenceProfileDao: ConvergenceProfileDao,
        prefs: SharedPreferences = PreferencesHelper.getEncryptedSharedPreferences(context),
    ) {
        val cleanCallsign = normalizeCallsign(callsign)
        val nodeEid = "dtn://$cleanCallsign"

        // 1. Configure local node EID
        prefs.edit().putString("local_node_name", nodeEid).apply()

        // 2. Configure 3 standard services:
        // - "Chat" (type CHAT, notifications on, broadcast off)
        localServiceDao.insert(
            LocalService(
                serviceEid = "$nodeEid/chat",
                displayName = "Chat",
                viewerType = ViewerType.CHAT,
                isNotificationEnabled = true,
                isBroadcast = false,
            ),
        )

        // - "Files" (type BUNDLE_LIST, notifications on, broadcast off)
        localServiceDao.insert(
            LocalService(
                serviceEid = "$nodeEid/files",
                displayName = "Files",
                viewerType = ViewerType.BUNDLE_LIST,
                isNotificationEnabled = true,
                isBroadcast = false,
            ),
        )

        // - "SenML" (type SENML_LAST, notifications off, broadcast off)
        localServiceDao.insert(
            LocalService(
                serviceEid = "$nodeEid/senml",
                displayName = "SenML",
                viewerType = ViewerType.SENML_LAST,
                isNotificationEnabled = false,
                isBroadcast = false,
            ),
        )

        // 3. Keep default Hardy CLA profile if empty
        val existingProfiles = convergenceProfileDao.getAllList()
        if (existingProfiles.isEmpty()) {
            convergenceProfileDao.insert(
                ConvergenceProfile(
                    profileId = DEFAULT_HARDY_PROFILE_ID,
                    name = "Hardy Instance f4jxq (TCPCL)",
                    triggerType = TriggerType.PERIODIC_INTERNET,
                    targetAddress = DEFAULT_HARDY_ADDRESS,
                    triggerCondition = "120",
                ),
            )
        }

        // 4. Mark onboarding completed
        PreferencesHelper.setOnboardingCompleted(context, true)
    }
}
