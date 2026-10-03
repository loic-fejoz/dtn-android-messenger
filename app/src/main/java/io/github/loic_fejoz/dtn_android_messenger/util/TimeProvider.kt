package io.github.loic_fejoz.dtn_android_messenger.util

interface TimeProvider {
    fun currentTimeMillis(): Long
}

object SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

class TestTimeProvider(var initialTimeMs: Long = System.currentTimeMillis()) : TimeProvider {
    override fun currentTimeMillis(): Long = initialTimeMs

    fun advanceTimeBy(ms: Long) {
        initialTimeMs += ms
    }
}
