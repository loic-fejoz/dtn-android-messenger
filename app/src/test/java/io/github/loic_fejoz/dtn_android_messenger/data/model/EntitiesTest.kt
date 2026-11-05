package io.github.loic_fejoz.dtn_android_messenger.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EntitiesTest {
    @Test
    fun testDefaultLifetimeIs48Hours() {
        val expected48HoursMs = 48L * 3600L * 1000L
        assertEquals(expected48HoursMs, BundleRecord.DEFAULT_LIFETIME_MS)
        assertEquals(172800000L, BundleRecord.DEFAULT_LIFETIME_MS)
    }
}
