package com.dtn.messenger.protocol

import com.code_intelligence.jazzer.junit.FuzzTest

class Bpv7FuzzTest {

    @FuzzTest(maxDuration = "1m")
    fun fuzzDeserializer(data: ByteArray) {
        try {
            // Feed fuzzed bytes into the parser
            Bpv7Parser.deserialize(data)
        } catch (e: Exception) {
            // Suppress standard parser/decoding exceptions as they are expected
            // when parsing random bytes.
            // The fuzzer will still report OutOfMemoryError, infinite loops, or crashes.
        }
    }
}
