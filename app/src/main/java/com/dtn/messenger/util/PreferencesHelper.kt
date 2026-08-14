package com.dtn.messenger.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object PreferencesHelper {
    const val PREF_MAX_BUNDLE_SIZE_MB = "max_bundle_size_mb"
    const val DEFAULT_MAX_BUNDLE_SIZE_MB = 10
    const val MAX_BUNDLE_SIZE_MB_LIMIT = 100

    private var securePrefs: SharedPreferences? = null

    fun getEncryptedSharedPreferences(context: Context): SharedPreferences {
        return securePrefs ?: synchronized(this) {
            securePrefs ?: createSecurePrefs(context).also { securePrefs = it }
        }
    }

    fun getMaxBundleSizeBytes(context: Context): Long {
        val prefs = getEncryptedSharedPreferences(context)
        val mb = prefs.getInt(PREF_MAX_BUNDLE_SIZE_MB, DEFAULT_MAX_BUNDLE_SIZE_MB).coerceIn(1, MAX_BUNDLE_SIZE_MB_LIMIT)
        return mb.toLong() * 1024L * 1024L
    }

    private fun createSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "dtn_prefs_secure",
                masterKeyAlias,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Fallback to standard preferences if keystore initialization fails
            context.applicationContext.getSharedPreferences("dtn_prefs", Context.MODE_PRIVATE)
        }
    }
}
