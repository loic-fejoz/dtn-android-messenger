package io.github.loic_fejoz.dtn_android_messenger.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

object WifiUtils {
    data class WifiConnectionInfo(
        val isWifiActive: Boolean,
        val ssid: String?,
    )

    fun getConnectedWifiInfo(context: Context): WifiConnectionInfo {
        return try {
            val cm =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return WifiConnectionInfo(isWifiActive = false, ssid = null)
            val activeNetwork = cm.activeNetwork ?: return WifiConnectionInfo(isWifiActive = false, ssid = null)
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return WifiConnectionInfo(isWifiActive = false, ssid = null)
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return WifiConnectionInfo(isWifiActive = false, ssid = null)
            }

            val rawSsid =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (capabilities.transportInfo as? WifiInfo)?.ssid
                } else {
                    @Suppress("DEPRECATION")
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    wm?.connectionInfo?.ssid
                }?.trim('"')

            val cleanSsid = if (rawSsid.isNullOrEmpty() || rawSsid == "<unknown ssid>") null else rawSsid
            WifiConnectionInfo(isWifiActive = true, ssid = cleanSsid)
        } catch (_: Exception) {
            WifiConnectionInfo(isWifiActive = false, ssid = null)
        }
    }

    fun getConnectedWifiSsid(context: Context): String? = getConnectedWifiInfo(context).ssid
}
