package io.github.loic_fejoz.dtn_android_messenger.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

object WifiUtils {
    fun getConnectedWifiSsid(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val activeNetwork = cm.activeNetwork ?: return null
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

            val rawSsid =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (capabilities.transportInfo as? WifiInfo)?.ssid
                } else {
                    @Suppress("DEPRECATION")
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    wm?.connectionInfo?.ssid
                }?.trim('"')

            if (rawSsid.isNullOrEmpty() || rawSsid == "<unknown ssid>") null else rawSsid
        } catch (_: Exception) {
            null
        }
    }
}
