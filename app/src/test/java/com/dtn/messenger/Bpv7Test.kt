package com.dtn.messenger

import com.dtn.messenger.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class Bpv7Test {

    @Test
    fun testEidSerialization() {
        val dtnEid = Eid("dtn://node-a/chat")
        val dtnCbor = dtnEid.toCbor()
        val parsedDtn = Eid.fromCbor(dtnCbor)
        assertEquals("dtn", parsedDtn.scheme)
        assertEquals("//node-a/chat", parsedDtn.ssp)
        assertEquals("dtn://node-a/chat", parsedDtn.uri)

        val ipnEid = Eid("ipn:1.2")
        val ipnCbor = ipnEid.toCbor()
        val parsedIpn = Eid.fromCbor(ipnCbor)
        assertEquals("ipn", parsedIpn.scheme)
        assertEquals("1.2", parsedIpn.ssp)
        assertEquals("ipn:1.2", parsedIpn.uri)
    }

    @Test
    fun testPrimaryBlockSerialization() {
        val primary = PrimaryBlock(
            version = 7,
            bundleControlFlags = 64L,
            crcType = 0,
            destination = Eid("dtn://node-b/chat"),
            source = Eid("dtn://node-a/chat"),
            reportTo = Eid("dtn://node-a/chat"),
            creationTimestamp = Pair(12345678L, 99L),
            lifetimeMs = 3600000L
        )

        val cbor = Bpv7Parser.serializePrimaryBlock(primary)
        val deserialized = Bpv7Parser.deserializePrimaryBlock(cbor)

        assertEquals(primary.version, deserialized.version)
        assertEquals(primary.bundleControlFlags, deserialized.bundleControlFlags)
        assertEquals(primary.crcType, deserialized.crcType)
        assertEquals(primary.destination.uri, deserialized.destination.uri)
        assertEquals(primary.source.uri, deserialized.source.uri)
        assertEquals(primary.reportTo.uri, deserialized.reportTo.uri)
        assertEquals(primary.creationTimestamp, deserialized.creationTimestamp)
        assertEquals(primary.lifetimeMs, deserialized.lifetimeMs)
    }

    @Test
    fun testHopCountBlockSerialization() {
        val hopCount = HopCountBlock(
            blockNumber = 10,
            blockControlFlags = 0,
            hopLimit = 64,
            hopCount = 5
        )

        val cbor = Bpv7Parser.serializeHopCountBlock(hopCount)
        val deserialized = Bpv7Parser.deserializeHopCountBlock(cbor)

        assertEquals(hopCount.blockNumber, deserialized.blockNumber)
        assertEquals(hopCount.blockControlFlags, deserialized.blockControlFlags)
        assertEquals(hopCount.hopLimit, deserialized.hopLimit)
        assertEquals(hopCount.hopCount, deserialized.hopCount)
    }

    @Test
    fun testBibBlockSerialization() {
        val bib = BibBlock(
            blockNumber = 2,
            blockControlFlags = 0,
            targets = listOf(1),
            securitySource = Eid("dtn://node-a/chat"),
            signature = byteArrayOf(1, 2, 3, 4, 5)
        )

        val cbor = Bpv7Parser.serializeBibBlock(bib)
        val deserialized = Bpv7Parser.deserializeBibBlock(cbor)

        assertEquals(bib.blockNumber, deserialized.blockNumber)
        assertEquals(bib.blockControlFlags, deserialized.blockControlFlags)
        assertEquals(bib.targets, deserialized.targets)
        assertEquals(bib.securitySource.uri, deserialized.securitySource.uri)
        assertEquals(bib.variant, deserialized.variant)
        assertEquals(bib.scopeFlags, deserialized.scopeFlags)
        assertArrayEquals(bib.signature, deserialized.signature)
    }

    @Test
    fun testBundleSerialization() {
        val primary = PrimaryBlock(
            destination = Eid("dtn://node-b/chat"),
            source = Eid("dtn://node-a/chat"),
            reportTo = Eid("dtn://node-a/chat"),
            creationTimestamp = Pair(12345678L, 99L),
            lifetimeMs = 3600000L
        )
        val payload = PayloadBlock(data = "Hello DTN!".toByteArray(Charsets.UTF_8))
        val hopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val bib = BibBlock(securitySource = Eid("dtn://node-a/chat"), signature = byteArrayOf(9, 9, 9))

        val bundle = Bundle(primary, payload, hopCount, bib)
        val serializedBytes = Bpv7Parser.serialize(bundle)
        val parsedBundle = Bpv7Parser.deserialize(serializedBytes)

        assertEquals(bundle.primaryBlock.destination.uri, parsedBundle.primaryBlock.destination.uri)
        assertArrayEquals(bundle.payloadBlock.data, parsedBundle.payloadBlock.data)
        assertEquals(bundle.hopCountBlock?.hopLimit, parsedBundle.hopCountBlock?.hopLimit)
        assertEquals(bundle.hopCountBlock?.hopCount, parsedBundle.hopCountBlock?.hopCount)
        assertArrayEquals(bundle.bibBlock?.signature, parsedBundle.bibBlock?.signature)
    }

    @Test
    fun testBsecIntegrityHmac() {
        val secretKey = "shared_secret_key".toByteArray(Charsets.UTF_8)
        val payloadBytes = "DTN Secure Message".toByteArray(Charsets.UTF_8)
        val primaryBytes = byteArrayOf(0, 1, 2, 3, 4)

        // Compute HMAC signature
        val sig1 = Bpv7Parser.computeHmac(
            secretKey = secretKey,
            primaryBlockBytes = primaryBytes,
            targetBlockType = 1,
            targetBlockNumber = 1,
            targetBlockFlags = 0L,
            securityBlockType = 11,
            securityBlockNumber = 2,
            securityBlockFlags = 3L,
            payloadBytes = payloadBytes,
            scopeFlags = 7
        )

        // Verify with matching parameters
        val sig2 = Bpv7Parser.computeHmac(
            secretKey = secretKey,
            primaryBlockBytes = primaryBytes,
            targetBlockType = 1,
            targetBlockNumber = 1,
            targetBlockFlags = 0L,
            securityBlockType = 11,
            securityBlockNumber = 2,
            securityBlockFlags = 3L,
            payloadBytes = payloadBytes,
            scopeFlags = 7
        )
        assertArrayEquals(sig1, sig2)

        // Verify with different key (should fail)
        val wrongKey = "wrong_secret_key".toByteArray(Charsets.UTF_8)
        val sigWrongKey = Bpv7Parser.computeHmac(
            secretKey = wrongKey,
            primaryBlockBytes = primaryBytes,
            targetBlockType = 1,
            targetBlockNumber = 1,
            targetBlockFlags = 0L,
            securityBlockType = 11,
            securityBlockNumber = 2,
            securityBlockFlags = 3L,
            payloadBytes = payloadBytes,
            scopeFlags = 7
        )
        assertFalse(sig1.contentEquals(sigWrongKey))

        // Verify with modified payload (should fail)
        val sigModifiedPayload = Bpv7Parser.computeHmac(
            secretKey = secretKey,
            primaryBlockBytes = primaryBytes,
            targetBlockType = 1,
            targetBlockNumber = 1,
            targetBlockFlags = 0L,
            securityBlockType = 11,
            securityBlockNumber = 2,
            securityBlockFlags = 3L,
            payloadBytes = "DTN Secure Message!".toByteArray(Charsets.UTF_8),
            scopeFlags = 7
        )
        assertFalse(sig1.contentEquals(sigModifiedPayload))
    }

    @Test
    fun testHardySignedBundleVerification() {
        val hex = "9f890700028201692f2f626561636f6e2f82016d2f2f4e3043414c4c2f6368617482016d2f2f4e3043414c4c2f63686174821b000000c3724c49f41a0009bc571a0036ee80440c9ffc9a850b020000583e8101010182016d2f2f4e3043414c4c2f6368617481820105818182015820f95b5e95493c5e24bbbd71e06ee8fc4c4fdb6957cc1c5da51028f6fe33a19e508501010400581d4e3043414c4c207369676e656420626561636f6e2072616469616e740aff"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val bundle = Bpv7Parser.deserialize(bytes)

        val key = byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
        )

        val bib = bundle.bibBlock!!
        val rawPrimaryBytes = bundle.primaryBlock.rawBytes ?: Bpv7Parser.serializePrimaryBlock(bundle.primaryBlock).EncodeToBytes()

        val computedSignature = Bpv7Parser.computeHmac(
            secretKey = key,
            primaryBlockBytes = rawPrimaryBytes,
            targetBlockType = 1,
            targetBlockNumber = bundle.payloadBlock.blockNumber,
            targetBlockFlags = bundle.payloadBlock.blockControlFlags,
            securityBlockType = 11,
            securityBlockNumber = bib.blockNumber,
            securityBlockFlags = bib.blockControlFlags,
            payloadBytes = bundle.payloadBlock.data,
            scopeFlags = bib.scopeFlags
        )

        assertArrayEquals(bib.signature, computedSignature)
    }
}

