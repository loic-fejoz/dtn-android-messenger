package io.github.loic_fejoz.dtn_android_messenger.service

import android.content.Context
import io.github.loic_fejoz.dtn_android_messenger.data.model.ConvergenceProfile
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import io.github.loic_fejoz.dtn_android_messenger.data.model.RoutingRule
import io.github.loic_fejoz.dtn_android_messenger.data.model.TriggerType
import io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils
import io.github.loic_fejoz.dtn_android_messenger.util.WifiUtils

internal object RoutingPolicy {
    fun selectNextHop(
        destination: String,
        rules: List<RoutingRule>,
    ): String {
        val sortedRules =
            rules.sortedByDescending {
                it.destinationEidPattern.replace("*", "").trimEnd('/').length
            }
        for (rule in sortedRules) {
            val pattern = rule.destinationEidPattern.replace("*", "").trimEnd('/')
            if (PayloadUtils.isPrefixMatch(pattern, destination)) {
                return rule.nextHopEid
            }
        }
        return destination
    }

    fun isLocalDestination(
        destinationEid: String,
        localNodeBase: String,
        localServices: List<LocalService>,
    ): Pair<Boolean, List<LocalService>> {
        if (!PayloadUtils.isValidEid(destinationEid)) {
            return Pair(false, emptyList())
        }
        val isLocalNode =
            PayloadUtils.isValidEid(localNodeBase) &&
                PayloadUtils.isPrefixMatch(localNodeBase, destinationEid)
        val matchedLocalServices =
            localServices.filter { service ->
                PayloadUtils.isValidEid(service.serviceEid) &&
                    PayloadUtils.isPrefixMatch(service.serviceEid, destinationEid)
            }
        return Pair(isLocalNode || matchedLocalServices.isNotEmpty(), matchedLocalServices)
    }

    fun isProfileEligible(
        context: Context,
        profile: ConvergenceProfile,
    ): Boolean {
        if (profile.isPaused) return false
        if (profile.triggerType != TriggerType.WIFI_SSID) return true
        return try {
            val wifiInfo = WifiUtils.getConnectedWifiInfo(context)
            if (!wifiInfo.isWifiActive) {
                return false
            }
            val requiredSsid = profile.triggerCondition?.trim().orEmpty()
            if (requiredSsid.isEmpty()) return true
            val currentSsid = wifiInfo.ssid
            if (currentSsid == null) {
                true
            } else {
                currentSsid.equals(requiredSsid, ignoreCase = true)
            }
        } catch (_: Exception) {
            true
        }
    }

    fun findProfileForNextHop(
        context: Context,
        nextHop: String,
        profiles: List<ConvergenceProfile>,
        allowPausedProfileId: String? = null,
    ): ConvergenceProfile? {
        fun isEligible(profile: ConvergenceProfile): Boolean =
            (allowPausedProfileId != null && profile.profileId == allowPausedProfileId) ||
                isProfileEligible(context, profile)

        val directMatch = profiles.find { (it.profileId == nextHop || it.name == nextHop) && isEligible(it) }
        if (directMatch != null) return directMatch

        val nextHopNode =
            when {
                nextHop.startsWith("dtn://") -> "dtn://" + nextHop.substring(6).substringBefore("/")
                nextHop.startsWith("ipn:") -> "ipn:" + nextHop.substring(4).substringBefore("/")
                else -> nextHop
            }
        return profiles.find { (it.profileId == nextHopNode || it.name == nextHopNode) && isEligible(it) }
    }
}
