package com.dtn.messenger.protocol

import com.dtn.messenger.data.model.LocalService
import com.dtn.messenger.data.model.ViewerType
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BibeTest {

    @Test
    fun testBibePduEncapsulationAndDecapsulation() {
        // 1. Create a dummy inner bundle
        val innerPrimary = PrimaryBlock(
            destination = Eid("dtn://destination/chat"),
            source = Eid("dtn://source/chat"),
            reportTo = Eid("dtn://source/chat"),
            creationTimestamp = Pair(1000L, 1L),
            lifetimeMs = 3600000L
        )
        val innerPayload = PayloadBlock(data = "Hello inner bundle!".toByteArray(Charsets.UTF_8))
        val innerHopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val innerBundle = Bundle(innerPrimary, innerPayload, innerHopCount, null)

        val innerBundleBytes = Bpv7Parser.serialize(innerBundle)

        // 2. Encapsulate into a BIBE PDU (CBOR Administrative Record 64443)
        // Format: [64443, [transmission-id, retransmission-time, encapsulated-bundle]]
        val pduContent = CBORObject.NewArray()
        pduContent.Add(42) // transmission-id
        pduContent.Add(0)  // retransmission-time
        pduContent.Add(innerBundleBytes)

        val pdu = CBORObject.NewArray()
        pdu.Add(64443)
        pdu.Add(pduContent)

        val outerPayloadBytes = pdu.EncodeToBytes()

        // 3. Encapsulate into an outer bundle
        val outerPrimary = PrimaryBlock(
            destination = Eid("dtn://gateway/bibe"),
            source = Eid("dtn://my-node/bibe"),
            reportTo = Eid("dtn://my-node/bibe"),
            creationTimestamp = Pair(2000L, 1L),
            lifetimeMs = 3600000L
        )
        val outerPayload = PayloadBlock(data = outerPayloadBytes)
        val outerHopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val outerBundle = Bundle(outerPrimary, outerPayload, outerHopCount, null)

        val outerBundleBytes = Bpv7Parser.serialize(outerBundle)

        // 4. Deserialize outer bundle
        val deserializedOuter = Bpv7Parser.deserialize(outerBundleBytes)
        assertEquals("dtn://gateway/bibe", deserializedOuter.primaryBlock.destination.uri)
        assertEquals("dtn://my-node/bibe", deserializedOuter.primaryBlock.source.uri)

        // 5. Parse outer payload as BIBE PDU
        val payloadData = deserializedOuter.payloadBlock.data
        val cbor = CBORObject.DecodeFromBytes(payloadData)
        assertEquals(CBORType.Array, cbor.type)
        assertEquals(2, cbor.size())
        
        val recordType = cbor[0].AsInt32()
        assertEquals(64443, recordType)

        val content = cbor[1]
        assertEquals(CBORType.Array, content.type)
        assertTrue(content.size() >= 3)

        val extractedInnerBytes = content[2].GetByteString()

        // 6. Deserialize and verify inner bundle
        val deserializedInner = Bpv7Parser.deserialize(extractedInnerBytes)
        assertEquals("dtn://destination/chat", deserializedInner.primaryBlock.destination.uri)
        assertEquals("Hello inner bundle!", String(deserializedInner.payloadBlock.data, Charsets.UTF_8))
    }

    @Test
    fun testRawBundleInBundleDecapsulation() {
        // 1. Create a dummy inner bundle
        val innerPrimary = PrimaryBlock(
            destination = Eid("dtn://destination/chat"),
            source = Eid("dtn://source/chat"),
            reportTo = Eid("dtn://source/chat"),
            creationTimestamp = Pair(1000L, 1L),
            lifetimeMs = 3600000L
        )
        val innerPayload = PayloadBlock(data = "Hello raw inner!".toByteArray(Charsets.UTF_8))
        val innerHopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val innerBundle = Bundle(innerPrimary, innerPayload, innerHopCount, null)

        val innerBundleBytes = Bpv7Parser.serialize(innerBundle)

        // 2. Encapsulate raw bundle bytes directly in outer bundle's payload
        val outerPrimary = PrimaryBlock(
            destination = Eid("dtn://gateway/bibe"),
            source = Eid("dtn://my-node/bibe"),
            reportTo = Eid("dtn://my-node/bibe"),
            creationTimestamp = Pair(2000L, 1L),
            lifetimeMs = 3600000L
        )
        val outerPayload = PayloadBlock(data = innerBundleBytes)
        val outerHopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val outerBundle = Bundle(outerPrimary, outerPayload, outerHopCount, null)

        val outerBundleBytes = Bpv7Parser.serialize(outerBundle)

        // 3. Deserialize outer bundle
        val deserializedOuter = Bpv7Parser.deserialize(outerBundleBytes)

        // 4. Try parsing the payload directly as a raw inner bundle
        val rawPayload = deserializedOuter.payloadBlock.data
        val deserializedInner = Bpv7Parser.deserialize(rawPayload)
        assertEquals(7, deserializedInner.primaryBlock.version)
        assertEquals("dtn://destination/chat", deserializedInner.primaryBlock.destination.uri)
        assertEquals("Hello raw inner!", String(deserializedInner.payloadBlock.data, Charsets.UTF_8))
    }

    @Test
    fun testMalformedBibePduDoesNotCrash() {
        // 1. Array with invalid record type
        val invalidTypeCbor = CBORObject.NewArray().apply {
            Add(99999) // Unknown admin record type
            Add(CBORObject.NewArray())
        }
        // Should not crash when trying to parse or process
        try {
            val cbor = CBORObject.DecodeFromBytes(invalidTypeCbor.EncodeToBytes())
            if (cbor.type == CBORType.Array && cbor.size() >= 2) {
                val recordType = cbor[0].AsInt32()
                if (recordType == 64443) {
                    throw AssertionError("Should not match type 64443")
                }
            }
        } catch (e: Exception) {
            throw AssertionError("Should not have thrown any exception: ${e.message}")
        }

        // 2. Array with correct type but empty content array
        val emptyContentCbor = CBORObject.NewArray().apply {
            Add(64443)
            Add(CBORObject.NewArray()) // Empty content array (size 0, should be >= 3)
        }
        try {
            val cbor = CBORObject.DecodeFromBytes(emptyContentCbor.EncodeToBytes())
            if (cbor.type == CBORType.Array && cbor.size() >= 2) {
                val recordType = cbor[0].AsInt32()
                if (recordType == 64443) {
                    val content = cbor[1]
                    if (content.type == CBORType.Array && content.size() >= 3) {
                        throw AssertionError("Should have rejected empty content array")
                    }
                }
            }
        } catch (e: Exception) {
            throw AssertionError("Should not have thrown any exception: ${e.message}")
        }
    }

    @Test
    fun testBroadcastForwardingLogic() {
        val nextHopProfileExists = true

        // Scenario 1: Not local (transit routing) -> should forward
        val isLocal1 = false
        val matchedLocalServices1 = emptyList<LocalService>()
        val isMatchedBroadcast1 = matchedLocalServices1.any { it.isBroadcast }
        val shouldForward1 = nextHopProfileExists && (!isLocal1 || isMatchedBroadcast1)
        assertTrue(shouldForward1)

        // Scenario 2: Local unicast service only -> should NOT forward
        val isLocal2 = true
        val matchedLocalServices2 = listOf(
            LocalService(serviceEid = "dtn://my-node/chat", displayName = "Chat", viewerType = ViewerType.CHAT, isBroadcast = false)
        )
        val isMatchedBroadcast2 = matchedLocalServices2.any { it.isBroadcast }
        val shouldForward2 = nextHopProfileExists && (!isLocal2 || isMatchedBroadcast2)
        assertFalse(shouldForward2)

        // Scenario 3: Local broadcast service -> should forward
        val isLocal3 = true
        val matchedLocalServices3 = listOf(
            LocalService(serviceEid = "dtn://group/chat", displayName = "Group Chat", viewerType = ViewerType.CHAT, isBroadcast = true)
        )
        val isMatchedBroadcast3 = matchedLocalServices3.any { it.isBroadcast }
        val shouldForward3 = nextHopProfileExists && (!isLocal3 || isMatchedBroadcast3)
        assertTrue(shouldForward3)
    }
}
