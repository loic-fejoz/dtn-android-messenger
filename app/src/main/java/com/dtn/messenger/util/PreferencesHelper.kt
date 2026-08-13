package com.dtn.messenger.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object PreferencesHelper {
    private var securePrefs: SharedPreferences? = null

    fun getEncryptedSharedPreferences(context: Context): SharedPreferences {
        return securePrefs ?: synchronized(this) {
            securePrefs ?: createSecurePrefs(context).also { securePrefs = it }
        }
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
