package com.dtn.messenger.protocol

import com.code_intelligence.jazzer.junit.FuzzTest

class SenmlParserFuzzTest {

    @FuzzTest(maxDuration = "1m")
    fun fuzzSenmlParser(data: ByteArray) {
        try {
            SenmlParser.parse(data)
        } catch (e: Exception) {
            // Suppress expected parsing exceptions when fuzzing random bytes.
            // Jazzer will report OutOfMemoryError, infinite loops, or unexpected crashes.
        }
    }
}
