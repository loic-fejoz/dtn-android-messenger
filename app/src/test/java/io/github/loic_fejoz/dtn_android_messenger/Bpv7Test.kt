package io.github.loic_fejoz.dtn_android_messenger

import io.github.loic_fejoz.dtn_android_messenger.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

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
        val primary =
            PrimaryBlock(
                version = 7,
                bundleControlFlags = 64L,
                crcType = 0,
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn://node-a/chat"),
                creationTimestamp = Pair(12345678L, 99L),
                lifetimeMs = 3600000L,
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
        assertEquals(8, cbor.size())
    }

    @Test
    fun testPrimaryBlockSerializationDefaultCrc32c() {
        val primary =
            PrimaryBlock(
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn://node-a/chat"),
                creationTimestamp = Pair(12345678L, 99L),
                lifetimeMs = 3600000L,
            )

        // Default crcType must be 2 (CRC-32C Castagnoli) per RFC 9171 §4.3.1
        assertEquals(2, primary.crcType)

        val cbor = Bpv7Parser.serializePrimaryBlock(primary)
        assertEquals(9, cbor.size(), "CBOR array must have 9 elements when crcType is 2")

        val crcElement = cbor[8]
        assertEquals(com.upokecenter.cbor.CBORType.ByteString, crcElement.type)
        assertEquals(4, crcElement.GetByteString().size, "CRC-32C must be 4 bytes")

        val deserialized = Bpv7Parser.deserializePrimaryBlock(cbor)
        assertEquals(2, deserialized.crcType)
        assertNotNull(deserialized.crc)
        assertArrayEquals(crcElement.GetByteString(), deserialized.crc)
        assertEquals(primary.destination.uri, deserialized.destination.uri)
    }

    @Test
    fun testPrimaryBlockSerializationCrc16() {
        val primary =
            PrimaryBlock(
                // CRC-16 (X.25)
                crcType = 1,
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn://node-a/chat"),
                creationTimestamp = Pair(12345678L, 99L),
                lifetimeMs = 3600000L,
            )

        val cbor = Bpv7Parser.serializePrimaryBlock(primary)
        assertEquals(9, cbor.size(), "CBOR array must have 9 elements when crcType is 1")

        val crcElement = cbor[8]
        assertEquals(com.upokecenter.cbor.CBORType.ByteString, crcElement.type)
        assertEquals(2, crcElement.GetByteString().size, "CRC-16 must be 2 bytes")

        val deserialized = Bpv7Parser.deserializePrimaryBlock(cbor)
        assertEquals(1, deserialized.crcType)
        assertNotNull(deserialized.crc)
        assertArrayEquals(crcElement.GetByteString(), deserialized.crc)
    }

    @Test
    fun testPrimaryBlockCorruptedCrcThrows() {
        val primary =
            PrimaryBlock(
                crcType = 2,
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn://node-a/chat"),
                creationTimestamp = Pair(12345678L, 99L),
                lifetimeMs = 3600000L,
            )

        val cbor = Bpv7Parser.serializePrimaryBlock(primary)
        val validCrc = cbor[8].GetByteString()
        val corruptCrc = validCrc.clone().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
        cbor[8] = com.upokecenter.cbor.CBORObject.FromObject(corruptCrc)

        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                Bpv7Parser.deserializePrimaryBlock(cbor)
            }
        assertTrue(ex.message!!.contains("CRC-32C mismatch"))
    }

    @Test
    fun testPrimaryBlockMissingCrcThrows() {
        // Construct 8-element array with crcType = 2 (missing 9th element)
        val array = com.upokecenter.cbor.CBORObject.NewArray()
        array.Add(7)
        array.Add(0L)
        array.Add(2) // crcType = 2
        array.Add(Eid("dtn://node-b/chat").toCbor())
        array.Add(Eid("dtn://node-a/chat").toCbor())
        array.Add(Eid("dtn://node-a/chat").toCbor())
        val ts = com.upokecenter.cbor.CBORObject.NewArray()
        ts.Add(100L)
        ts.Add(1L)
        array.Add(ts)
        array.Add(1000L)

        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                Bpv7Parser.deserializePrimaryBlock(array)
            }
        assertTrue(ex.message!!.contains("Missing CRC field in PrimaryBlock"))
    }

    @Test
    fun testHopCountBlockSerialization() {
        val hopCount =
            HopCountBlock(
                blockNumber = 10,
                blockControlFlags = 0,
                hopLimit = 64,
                hopCount = 5,
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
        val bib =
            BibBlock(
                blockNumber = 2,
                blockControlFlags = 0,
                targets = listOf(1),
                securitySource = Eid("dtn://node-a/chat"),
                signature = byteArrayOf(1, 2, 3, 4, 5),
            )

        val cbor = Bpv7Parser.serializeBibBlock(bib)
        val deserialized = Bpv7Parser.deserializeBibBlock(cbor)

        assertEquals(bib.blockNumber, deserialized.blockNumber)
        assertEquals(bib.blockControlFlags, deserialized.blockControlFlags)
        assertEquals(1L, deserialized.securityContextFlags)
        assertEquals(bib.targets, deserialized.targets)
        assertEquals(bib.securitySource.uri, deserialized.securitySource.uri)
        assertEquals(bib.variant, deserialized.variant)
        assertEquals(bib.scopeFlags, deserialized.scopeFlags)
        assertArrayEquals(bib.signature, deserialized.signature)
    }

    @Test
    fun testBundleSerialization() {
        val primary =
            PrimaryBlock(
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn://node-a/chat"),
                creationTimestamp = Pair(12345678L, 99L),
                lifetimeMs = 3600000L,
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
        val sig1 =
            Bpv7Parser.computeHmac(
                secretKey = secretKey,
                primaryBlockBytes = primaryBytes,
                targetBlockType = 1,
                targetBlockNumber = 1,
                targetBlockFlags = 0L,
                securityBlockType = 11,
                securityBlockNumber = 2,
                securityBlockFlags = 0L,
                payloadBytes = payloadBytes,
                scopeFlags = 7,
            )

        // Verify with matching parameters
        val sig2 =
            Bpv7Parser.computeHmac(
                secretKey = secretKey,
                primaryBlockBytes = primaryBytes,
                targetBlockType = 1,
                targetBlockNumber = 1,
                targetBlockFlags = 0L,
                securityBlockType = 11,
                securityBlockNumber = 2,
                securityBlockFlags = 0L,
                payloadBytes = payloadBytes,
                scopeFlags = 7,
            )
        assertArrayEquals(sig1, sig2)

        // Verify with different key (should fail)
        val wrongKey = "wrong_secret_key".toByteArray(Charsets.UTF_8)
        val sigWrongKey =
            Bpv7Parser.computeHmac(
                secretKey = wrongKey,
                primaryBlockBytes = primaryBytes,
                targetBlockType = 1,
                targetBlockNumber = 1,
                targetBlockFlags = 0L,
                securityBlockType = 11,
                securityBlockNumber = 2,
                securityBlockFlags = 0L,
                payloadBytes = payloadBytes,
                scopeFlags = 7,
            )
        assertFalse(sig1.contentEquals(sigWrongKey))

        // Verify with modified payload (should fail)
        val sigModifiedPayload =
            Bpv7Parser.computeHmac(
                secretKey = secretKey,
                primaryBlockBytes = primaryBytes,
                targetBlockType = 1,
                targetBlockNumber = 1,
                targetBlockFlags = 0L,
                securityBlockType = 11,
                securityBlockNumber = 2,
                securityBlockFlags = 0L,
                payloadBytes = "DTN Secure Message!".toByteArray(Charsets.UTF_8),
                scopeFlags = 7,
            )
        assertFalse(sig1.contentEquals(sigModifiedPayload))
    }

    @Test
    fun testHardySignedBundleVerification() {
        val hex = "9f890700028201692f2f626561636f6e2f82016d2f2f4e3043414c4c2f6368617482016d2f2f4e3043414c4c2f63686174821b000000c3724c49f41a0009bc571a0036ee80440c9ffc9a850b020000583e8101010182016d2f2f4e3043414c4c2f6368617481820105818182015820f95b5e95493c5e24bbbd71e06ee8fc4c4fdb6957cc1c5da51028f6fe33a19e508501010400581d4e3043414c4c207369676e656420626561636f6e2072616469616e740aff"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val bundle = Bpv7Parser.deserialize(bytes)

        val key =
            byteArrayOf(
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
                0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
            )

        val bib = bundle.bibBlock!!
        val rawPrimaryBytes = bundle.primaryBlock.rawBytes ?: Bpv7Parser.serializePrimaryBlock(bundle.primaryBlock).EncodeToBytes()

        val computedSignature =
            Bpv7Parser.computeHmac(
                secretKey = key,
                primaryBlockBytes = rawPrimaryBytes,
                targetBlockType = 1,
                targetBlockNumber = bundle.payloadBlock.blockNumber,
                targetBlockFlags = bundle.payloadBlock.blockControlFlags,
                securityBlockType = 11,
                securityBlockNumber = bib.blockNumber,
                securityBlockFlags = bib.blockControlFlags,
                payloadBytes = bundle.payloadBlock.data,
                scopeFlags = bib.scopeFlags,
            )

        assertArrayEquals(bib.signature, computedSignature)
    }

    @Test
    fun testMalformedCborThrowsGracefully() {
        // Empty array
        val emptyArrayBytes = com.upokecenter.cbor.CBORObject.NewArray().EncodeToBytes()
        assertThrows(IllegalArgumentException::class.java) {
            Bpv7Parser.deserialize(emptyArrayBytes)
        }

        // Single element array (missing payload block)
        val primaryOnly = com.upokecenter.cbor.CBORObject.NewArray()
        primaryOnly.Add(
            Bpv7Parser.serializePrimaryBlock(
                PrimaryBlock(
                    destination = Eid("dtn://dest"),
                    source = Eid("dtn://src"),
                    reportTo = Eid("dtn://src"),
                    creationTimestamp = Pair(100L, 1L),
                    lifetimeMs = 1000L,
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            Bpv7Parser.deserialize(primaryOnly.EncodeToBytes())
        }

        // Truncated / malformed EID
        val malformedEid = com.upokecenter.cbor.CBORObject.NewArray()
        malformedEid.Add(1) // only 1 element
        assertThrows(IllegalArgumentException::class.java) {
            Eid.fromCbor(malformedEid)
        }

        // Malformed PrimaryBlock (insufficient elements)
        val malformedPrimary = com.upokecenter.cbor.CBORObject.NewArray()
        malformedPrimary.Add(7)
        malformedPrimary.Add(0L)
        assertThrows(IllegalArgumentException::class.java) {
            Bpv7Parser.deserializePrimaryBlock(malformedPrimary)
        }
    }

    @Test
    fun testRfc9173HmacScopeFlagsVariability() {
        val secretKey = "shared_secret_key_456".toByteArray(Charsets.UTF_8)
        val payloadBytes = "RFC 9173 Scope Flags Test".toByteArray(Charsets.UTF_8)

        val primary =
            PrimaryBlock(
                destination = Eid("dtn://node-b/app"),
                source = Eid("dtn://node-a/app"),
                reportTo = Eid("dtn://node-a/app"),
                creationTimestamp = Pair(100000L, 14L),
                lifetimeMs = 600000L,
            )
        val primaryBytes = Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()

        // RFC 9173 Section 3.2: Scope flags mask scopeFlags = 7 (all included) vs scopeFlags = 1 (primary + payload)
        val sigFullScope =
            Bpv7Parser.computeHmac(
                secretKey = secretKey,
                primaryBlockBytes = primaryBytes,
                targetBlockType = 1,
                targetBlockNumber = 1,
                targetBlockFlags = 0L,
                securityBlockType = 11,
                securityBlockNumber = 2,
                securityBlockFlags = 0L,
                payloadBytes = payloadBytes,
                scopeFlags = 7,
            )

        val sigPrimaryOnlyScope =
            Bpv7Parser.computeHmac(
                secretKey = secretKey,
                primaryBlockBytes = primaryBytes,
                targetBlockType = 1,
                targetBlockNumber = 1,
                targetBlockFlags = 0L,
                securityBlockType = 11,
                securityBlockNumber = 2,
                securityBlockFlags = 0L,
                payloadBytes = payloadBytes,
                scopeFlags = 1,
            )

        // Both signatures are valid for their respective RFC 9173 scope flag configurations
        assertTrue(sigFullScope.isNotEmpty())
        assertTrue(sigPrimaryOnlyScope.isNotEmpty())
        assertFalse(sigFullScope.contentEquals(sigPrimaryOnlyScope), "Signatures with different RFC 9173 scope flags MUST differ")
    }

    @Test
    fun testRfc9171IndefiniteArrayPayloadLastAndDtnNone() {
        val primary =
            PrimaryBlock(
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn:none"),
                creationTimestamp = Pair(12345678L, 99L),
                lifetimeMs = 3600000L,
            )
        val payload = PayloadBlock(data = "Hello RFC 9171!".toByteArray(Charsets.UTF_8))
        val hopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
        val bib = BibBlock(securitySource = Eid("dtn://node-a/chat"), signature = byteArrayOf(9, 9, 9))

        val bundle = Bundle(primary, payload, hopCount, bib)
        val serializedBytes = Bpv7Parser.serialize(bundle)

        assertTrue(serializedBytes.isNotEmpty())
        assertEquals(0x9F.toByte(), serializedBytes.first(), "Bundle must start with CBOR indefinite-length array (0x9F)")
        assertEquals(0xFF.toByte(), serializedBytes.last(), "Bundle must end with CBOR break code (0xFF)")

        val cbor = com.upokecenter.cbor.CBORObject.DecodeFromBytes(serializedBytes)
        assertEquals(4, cbor.size())
        assertEquals(10, cbor[1][0].AsInt32(), "Hop Count block should precede Payload block")
        assertEquals(11, cbor[2][0].AsInt32(), "BIB block should precede Payload block")
        assertEquals(1, cbor[3][0].AsInt32(), "Payload block must be last per RFC 9171 §4.3.1")

        val nullEidBytes = Eid("dtn:none").toCbor().EncodeToBytes()
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x01, 0x00), nullEidBytes, "dtn:none must encode as [1, 0] per RFC 9171 §4.2.5.1.1")
        val parsedNullEid = Eid.fromCbor(com.upokecenter.cbor.CBORObject.DecodeFromBytes(nullEidBytes))
        assertEquals("dtn:none", parsedNullEid.uri)
    }

    @Test
    fun testSignedBibRoundTripVerification() {
        val secretKey = "test_bpsec_hmac_key_32_bytes_ok!".toByteArray(Charsets.UTF_8)
        val payloadData = "RFC 9171 + RFC 9172/9173 round-trip signed payload".toByteArray(Charsets.UTF_8)
        val primary =
            PrimaryBlock(
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn:none"),
                creationTimestamp = Pair(12345678L, 42L),
                lifetimeMs = 3600000L,
            )
        val payload = PayloadBlock(data = payloadData)
        val hopCount = HopCountBlock(hopLimit = 64, hopCount = 1)

        val rawPrimaryBytes = Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()
        val signature =
            Bpv7Parser.computeHmac(
                secretKey = secretKey,
                primaryBlockBytes = rawPrimaryBytes,
                targetBlockType = 1,
                targetBlockNumber = payload.blockNumber,
                targetBlockFlags = payload.blockControlFlags,
                securityBlockType = 11,
                securityBlockNumber = 2,
                securityBlockFlags = 0L,
                payloadBytes = payloadData,
                scopeFlags = 7,
            )
        val bib =
            BibBlock(
                blockNumber = 2,
                blockControlFlags = 0L,
                securitySource = Eid("dtn://node-a/chat"),
                signature = signature,
            )
        assertEquals(1L, bib.securityContextFlags, "RFC 9172 §3.6: only bit 0 (parameters present) should be set")

        val bundle = Bundle(primary, payload, hopCount, bib)
        val serializedBytes = Bpv7Parser.serialize(bundle)

        assertEquals(0x9F.toByte(), serializedBytes.first(), "Bundle must start with 0x9F")
        assertEquals(0xFF.toByte(), serializedBytes.last(), "Bundle must end with 0xFF")

        val cbor = com.upokecenter.cbor.CBORObject.DecodeFromBytes(serializedBytes)
        assertEquals(4, cbor.size())
        assertEquals(9, cbor[0].size(), "Primary block must have 9 elements including CRC-32C")
        assertEquals(4, cbor[0][8].GetByteString().size, "CRC-32C must be 4 bytes")
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x01, 0x00), cbor[0][5].EncodeToBytes(), "reportTo dtn:none must encode as [1, 0]")
        assertEquals(10, cbor[1][0].AsInt32(), "Hop Count block must precede BIB and Payload")
        assertEquals(11, cbor[2][0].AsInt32(), "BIB block must precede Payload")
        assertEquals(0L, cbor[2][2].AsInt64Value(), "BIB blockControlFlags must be 0")
        assertEquals(1, cbor[3][0].AsInt32(), "Payload block must be last")

        val parsedBundle = Bpv7Parser.deserialize(serializedBytes)
        val parsedBib = parsedBundle.bibBlock!!
        assertEquals(1L, parsedBib.securityContextFlags)
        val verifiedSignature =
            Bpv7Parser.computeHmac(
                secretKey = secretKey,
                primaryBlockBytes = parsedBundle.primaryBlock.rawBytes!!,
                targetBlockType = 1,
                targetBlockNumber = parsedBundle.payloadBlock.blockNumber,
                targetBlockFlags = parsedBundle.payloadBlock.blockControlFlags,
                securityBlockType = 11,
                securityBlockNumber = parsedBib.blockNumber,
                securityBlockFlags = parsedBib.blockControlFlags,
                payloadBytes = parsedBundle.payloadBlock.data,
                scopeFlags = parsedBib.scopeFlags,
            )
        assertArrayEquals(parsedBib.signature, verifiedSignature, "Recomputed BIB HMAC must match serialized signature")

        val tamperedPayload = payloadData.clone().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        val tamperedSignature =
            Bpv7Parser.computeHmac(
                secretKey = secretKey,
                primaryBlockBytes = parsedBundle.primaryBlock.rawBytes!!,
                targetBlockType = 1,
                targetBlockNumber = parsedBundle.payloadBlock.blockNumber,
                targetBlockFlags = parsedBundle.payloadBlock.blockControlFlags,
                securityBlockType = 11,
                securityBlockNumber = parsedBib.blockNumber,
                securityBlockFlags = parsedBib.blockControlFlags,
                payloadBytes = tamperedPayload,
                scopeFlags = parsedBib.scopeFlags,
            )
        assertFalse(parsedBib.signature.contentEquals(tamperedSignature), "Tampered payload must not verify")
    }

    @Test
    fun testDtnNodeEidTrailingSlashNormalization() {
        val nodeNoSlash = Eid("dtn://node-a").toCbor()
        assertEquals(1, nodeNoSlash[0].AsInt32())
        assertEquals("//node-a/", nodeNoSlash[1].AsString())
        assertEquals("dtn://node-a/", Eid.fromCbor(nodeNoSlash).uri)

        val nodeWithSlash = Eid("dtn://node-a/").toCbor()
        assertArrayEquals(nodeNoSlash.EncodeToBytes(), nodeWithSlash.EncodeToBytes())

        val serviceEid = Eid("dtn://node-a/chat").toCbor()
        assertEquals("//node-a/chat", serviceEid[1].AsString())

        val nullLower = Eid("dtn:none").toCbor().EncodeToBytes()
        val nullUpper = Eid("DTN:NONE").toCbor().EncodeToBytes()
        val expectedNull = byteArrayOf(0x82.toByte(), 0x01, 0x00)
        assertArrayEquals(expectedNull, nullLower)
        assertArrayEquals(expectedNull, nullUpper)
        assertEquals("dtn:none", Eid.fromCbor(com.upokecenter.cbor.CBORObject.DecodeFromBytes(nullLower)).uri)

        val ipnBytes = Eid("ipn:1.2").toCbor().EncodeToBytes()
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x02, 0x82.toByte(), 0x01, 0x02), ipnBytes)
    }

    @Test
    fun testBcbType12Rejected() {
        val primary =
            PrimaryBlock(
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn:none"),
                creationTimestamp = Pair(1000L, 1L),
                lifetimeMs = 3600000L,
            )
        val bcbBlock = Bpv7Parser.serializeCanonicalBlock(12, 2, 0L, 0, byteArrayOf(1, 2, 3))
        val payloadBlock = Bpv7Parser.serializeCanonicalBlock(1, 1, 0L, 0, "cipher".toByteArray())
        val out = java.io.ByteArrayOutputStream()
        out.write(0x9F)
        out.write(Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes())
        out.write(bcbBlock.EncodeToBytes())
        out.write(payloadBlock.EncodeToBytes())
        out.write(0xFF)

        assertThrows(IllegalArgumentException::class.java) {
            Bpv7Parser.deserialize(out.toByteArray())
        }
    }

    @Test
    fun testCanonicalBlockCrcVerificationAndUnsupportedTypes() {
        val primary =
            PrimaryBlock(
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn:none"),
                creationTimestamp = Pair(1000L, 1L),
                lifetimeMs = 3600000L,
            )

        // Build payload canonical block with valid CRC-32C (crcType = 2)
        val blockNoCrc = Bpv7Parser.serializeCanonicalBlock(1, 1, 0L, 2, "hello".toByteArray())
        blockNoCrc.Add(com.upokecenter.cbor.CBORObject.FromObject(ByteArray(4)))
        val validCrc = Bpv7Parser.computeCrc32c(blockNoCrc.EncodeToBytes())
        blockNoCrc[5] = com.upokecenter.cbor.CBORObject.FromObject(validCrc)

        val outValid = java.io.ByteArrayOutputStream()
        outValid.write(0x9F)
        outValid.write(Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes())
        outValid.write(blockNoCrc.EncodeToBytes())
        outValid.write(0xFF)

        val parsed = Bpv7Parser.deserialize(outValid.toByteArray())
        assertEquals("hello", String(parsed.payloadBlock.data))

        // Corrupt canonical block CRC
        val badCrc = validCrc.clone().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
        blockNoCrc[5] = com.upokecenter.cbor.CBORObject.FromObject(badCrc)
        val outBad = java.io.ByteArrayOutputStream()
        outBad.write(0x9F)
        outBad.write(Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes())
        outBad.write(blockNoCrc.EncodeToBytes())
        outBad.write(0xFF)
        assertThrows(IllegalArgumentException::class.java) {
            Bpv7Parser.deserialize(outBad.toByteArray())
        }

        // Unsupported crcType = 3 on primary block
        val badPrimaryCbor = Bpv7Parser.serializePrimaryBlock(primary)
        badPrimaryCbor[2] = com.upokecenter.cbor.CBORObject.FromObject(3)
        assertThrows(IllegalArgumentException::class.java) {
            Bpv7Parser.deserializePrimaryBlock(badPrimaryCbor)
        }
    }

    @Test
    fun testTruncatedBibAndUnsupportedEidSchemesThrowIllegalArgument() {
        val truncatedBib = Bpv7Parser.serializeCanonicalBlock(11, 2, 0L, 0, byteArrayOf(0x81.toByte(), 0x01))
        assertThrows(IllegalArgumentException::class.java) {
            Bpv7Parser.deserializeBibBlock(truncatedBib)
        }

        assertThrows(IllegalArgumentException::class.java) {
            Eid("http://example.com").toCbor()
        }

        val unknownSchemeCbor =
            com.upokecenter.cbor.CBORObject.NewArray().apply {
                Add(99)
                Add("foo")
            }
        assertThrows(IllegalArgumentException::class.java) {
            Eid.fromCbor(unknownSchemeCbor)
        }
    }

    @Test
    fun testMalformedCanonicalBlockAndUnknownDeleteFlag() {
        val primary =
            PrimaryBlock(
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn:none"),
                creationTimestamp = Pair(1000L, 1L),
                lifetimeMs = 3600000L,
            )
        val payload = Bpv7Parser.serializeCanonicalBlock(1, 1, 0L, 0, "ok".toByteArray())

        // Malformed canonical block (non-array) must throw
        val malformedOut = java.io.ByteArrayOutputStream()
        malformedOut.write(0x9F)
        malformedOut.write(Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes())
        malformedOut.write(com.upokecenter.cbor.CBORObject.FromObject(42).EncodeToBytes())
        malformedOut.write(payload.EncodeToBytes())
        malformedOut.write(0xFF)
        assertThrows(IllegalArgumentException::class.java) {
            Bpv7Parser.deserialize(malformedOut.toByteArray())
        }

        // Unknown block WITH flag 0x04 ("delete bundle if unprocessable") must throw
        val unknownFatal = Bpv7Parser.serializeCanonicalBlock(99, 3, 0x04L, 0, byteArrayOf(1, 2))
        val fatalOut = java.io.ByteArrayOutputStream()
        fatalOut.write(0x9F)
        fatalOut.write(Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes())
        fatalOut.write(unknownFatal.EncodeToBytes())
        fatalOut.write(payload.EncodeToBytes())
        fatalOut.write(0xFF)
        assertThrows(IllegalArgumentException::class.java) {
            Bpv7Parser.deserialize(fatalOut.toByteArray())
        }

        // Unknown block WITHOUT flag 0x04 should be ignored gracefully
        val unknownSafe = Bpv7Parser.serializeCanonicalBlock(99, 3, 0L, 0, byteArrayOf(1, 2))
        val safeOut = java.io.ByteArrayOutputStream()
        safeOut.write(0x9F)
        safeOut.write(Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes())
        safeOut.write(unknownSafe.EncodeToBytes())
        safeOut.write(payload.EncodeToBytes())
        safeOut.write(0xFF)
        val parsed = Bpv7Parser.deserialize(safeOut.toByteArray())
        assertEquals("ok", String(parsed.payloadBlock.data))
    }

    @Test
    fun testMultiTargetBibAndMultipleBibBlocks() {
        val stream = java.io.ByteArrayOutputStream()
        val targets =
            com.upokecenter.cbor.CBORObject.NewArray().apply {
                Add(10)
                Add(1)
            }
        targets.WriteTo(stream)
        com.upokecenter.cbor.CBORObject.FromObject(1).WriteTo(stream)
        com.upokecenter.cbor.CBORObject.FromObject(0L).WriteTo(stream)
        Eid("dtn://node-a/chat").toCbor().WriteTo(stream)

        val hopSig = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val payloadSig = byteArrayOf(0x11, 0x22, 0x33)
        val r0 =
            com.upokecenter.cbor.CBORObject.NewArray().apply {
                Add(
                    com.upokecenter.cbor.CBORObject.NewArray().apply {
                        Add(1)
                        Add(hopSig)
                    },
                )
            }
        val r1 =
            com.upokecenter.cbor.CBORObject.NewArray().apply {
                Add(
                    com.upokecenter.cbor.CBORObject.NewArray().apply {
                        Add(1)
                        Add(payloadSig)
                    },
                )
            }
        com.upokecenter.cbor.CBORObject.NewArray().apply {
            Add(r0)
            Add(r1)
        }.WriteTo(stream)

        val bibCbor = Bpv7Parser.serializeCanonicalBlock(11, 2, 0L, 0, stream.toByteArray())
        val deserializedBib = Bpv7Parser.deserializeBibBlock(bibCbor)
        assertArrayEquals(payloadSig, deserializedBib.signature)
        assertArrayEquals(hopSig, deserializedBib.signaturesByTarget[10])
        assertArrayEquals(payloadSig, deserializedBib.signaturesByTarget[1])

        // Bundle with two BIB blocks: payload BIB (targets [1]) + hop-count BIB (targets [10])
        val primary =
            PrimaryBlock(
                destination = Eid("dtn://node-b/chat"),
                source = Eid("dtn://node-a/chat"),
                reportTo = Eid("dtn:none"),
                creationTimestamp = Pair(1000L, 1L),
                lifetimeMs = 3600000L,
            )
        val payloadBib = BibBlock(blockNumber = 2, targets = listOf(1), securitySource = Eid("dtn://node-a/chat"), signature = payloadSig)
        val hopBib = BibBlock(blockNumber = 3, targets = listOf(10), securitySource = Eid("dtn://node-a/chat"), signature = hopSig)
        val bundle = Bundle(primary, PayloadBlock(data = "hello".toByteArray()), HopCountBlock(hopLimit = 64, hopCount = 1), payloadBib, listOf(payloadBib, hopBib))
        val parsedBundle = Bpv7Parser.deserialize(Bpv7Parser.serialize(bundle))
        assertEquals(2, parsedBundle.bibBlocks.size)
        assertArrayEquals(payloadSig, parsedBundle.bibBlock?.signature)
    }
}
