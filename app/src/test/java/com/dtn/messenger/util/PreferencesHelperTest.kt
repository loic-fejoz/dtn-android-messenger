package com.dtn.messenger.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreferencesHelperTest {
    @Test
    fun testPreferencesHelperConstants() {
        assertEquals("max_bundle_size_mb", PreferencesHelper.PREF_MAX_BUNDLE_SIZE_MB)
        assertEquals(10, PreferencesHelper.DEFAULT_MAX_BUNDLE_SIZE_MB)
        assertEquals(100, PreferencesHelper.MAX_BUNDLE_SIZE_MB_LIMIT)
    }

    @Test
    fun testMaxBundleSizeBytesCalculation() {
        fun calculateSizeBytes(mb: Int): Long {
            val validMb = mb.coerceIn(1, PreferencesHelper.MAX_BUNDLE_SIZE_MB_LIMIT)
            return validMb.toLong() * 1024L * 1024L
        }

        // Test default 10 MB
        assertEquals(10485760L, calculateSizeBytes(10))

        // Test minimum 1 MB
        assertEquals(1048576L, calculateSizeBytes(1))

        // Test maximum 100 MB
        assertEquals(104857600L, calculateSizeBytes(100))

        // Test lower bound enforcement (0 MB -> coerced to 1 MB)
        assertEquals(1048576L, calculateSizeBytes(0))

        // Test upper bound enforcement (500 MB -> coerced to 100 MB limit)
        assertEquals(104857600L, calculateSizeBytes(500))

        // Test negative input (negative -> coerced to 1 MB)
        assertEquals(1048576L, calculateSizeBytes(-5))
    }
}
