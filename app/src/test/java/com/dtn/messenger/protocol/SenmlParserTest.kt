package com.dtn.messenger.protocol

import com.upokecenter.cbor.CBORObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SenmlParserTest {

    @Test
    fun testParseJsonSimple() {
        val json = """
            [
              {"bn": "urn:dev:ow:10e2073a01080063:", "n": "voltage", "u": "V", "v": 230.1, "t": 1700000000},
              {"n": "current", "u": "A", "v": 10.5, "t": 1700000005}
            ]
        """.trimIndent()

        val records = SenmlParser.parse(json.toByteArray(Charsets.UTF_8))
        assertEquals(2, records.size)

        assertEquals("urn:dev:ow:10e2073a01080063:voltage", records[0].name)
        assertEquals("230.1", records[0].value)
        assertEquals("V", records[0].unit)
        assertEquals(1700000000000L, records[0].timestamp)

        assertEquals("urn:dev:ow:10e2073a01080063:current", records[1].name)
        assertEquals("10.5", records[1].value)
        assertEquals("A", records[1].unit)
        assertEquals(1700000005000L, records[1].timestamp)
    }

    @Test
    fun testParseJsonWithTypesAndBaseFields() {
        val json = """
            [
              {"bn": "sensor1/", "bt": 1700000000, "bu": "C"},
              {"n": "temp", "v": 21},
              {"n": "status", "vs": "OK"},
              {"n": "alarm", "vb": true}
            ]
        """.trimIndent()

        val records = SenmlParser.parse(json.toByteArray(Charsets.UTF_8))
        assertEquals(3, records.size)

        assertEquals("sensor1/temp", records[0].name)
        assertEquals("21", records[0].value)
        assertEquals("C", records[0].unit)
        assertEquals(1700000000000L, records[0].timestamp)

        assertEquals("sensor1/status", records[1].name)
        assertEquals("OK", records[1].value)

        assertEquals("sensor1/alarm", records[2].name)
        assertEquals("true", records[2].value)
    }

    @Test
    fun testParseCborIntegerKeys() {
        val array = CBORObject.NewArray()

        val rec1 = CBORObject.NewMap()
        rec1.set(CBORObject.FromObject(-2), CBORObject.FromObject("dev/1/")) // bn
        rec1.set(CBORObject.FromObject(0), CBORObject.FromObject("temp"))     // n
        rec1.set(CBORObject.FromObject(1), CBORObject.FromObject("Cel"))      // u
        rec1.set(CBORObject.FromObject(2), CBORObject.FromObject(25.4))       // v
        rec1.set(CBORObject.FromObject(6), CBORObject.FromObject(1700000010)) // t

        array.Add(rec1)

        val bytes = array.EncodeToBytes()
        val records = SenmlParser.parse(bytes)

        assertEquals(1, records.size)
        assertEquals("dev/1/temp", records[0].name)
        assertEquals("25.4", records[0].value)
        assertEquals("Cel", records[0].unit)
        assertEquals(1700000010000L, records[0].timestamp)
    }

    @Test
    fun testParseCborStringKeys() {
        val array = CBORObject.NewArray()

        val rec1 = CBORObject.NewMap()
        rec1.set(CBORObject.FromObject("bn"), CBORObject.FromObject("dev/2/"))
        rec1.set(CBORObject.FromObject("n"), CBORObject.FromObject("humidity"))
        rec1.set(CBORObject.FromObject("u"), CBORObject.FromObject("%RH"))
        rec1.set(CBORObject.FromObject("v"), CBORObject.FromObject(65))

        array.Add(rec1)

        val bytes = array.EncodeToBytes()
        val records = SenmlParser.parse(bytes, fallbackTimestampMs = 123456789L)

        assertEquals(1, records.size)
        assertEquals("dev/2/humidity", records[0].name)
        assertEquals("65", records[0].value)
        assertEquals("%RH", records[0].unit)
        assertEquals(123456789L, records[0].timestamp)
    }
}
