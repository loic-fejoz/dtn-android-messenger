package com.dtn.messenger.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoManager {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALIAS = "bpsec_db_key"
    
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun getKey(): SecretKey {
        val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return existingKey?.secretKey ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(bytes)
        
        // Pack IV and ciphertext together: [IV length (1 byte)][IV bytes (12 bytes)][Ciphertext...]
        val result = ByteArray(1 + iv.size + encrypted.size)
        result[0] = iv.size.toByte()
        System.arraycopy(iv, 0, result, 1, iv.size)
        System.arraycopy(encrypted, 0, result, 1 + iv.size, encrypted.size)
        return result
    }

    fun decrypt(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return ByteArray(0)
        
        val ivSize = bytes[0].toInt() and 0xFF
        val iv = ByteArray(ivSize)
        System.arraycopy(bytes, 1, iv, 0, ivSize)
        
        val encryptedSize = bytes.size - 1 - ivSize
        val encrypted = ByteArray(encryptedSize)
        System.arraycopy(bytes, 1 + ivSize, encrypted, 0, encryptedSize)

        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getKey(), spec)
        return cipher.doFinal(encrypted)
    }
}
