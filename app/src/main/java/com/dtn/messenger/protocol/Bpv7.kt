package com.dtn.messenger.protocol

import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class Eid(val uri: String) {
    val scheme: String
    val ssp: String

    init {
        val parts = uri.split(":", limit = 2)
        scheme = parts[0]
        ssp = parts.getOrNull(1) ?: ""
    }

    fun toCbor(): CBORObject {
        val schemeCode = if (scheme.lowercase() == "dtn") 1 else 2
        val cbor = CBORObject.NewArray()
        cbor.Add(schemeCode)
        if (schemeCode == 2) {
            val ipnParts = ssp.split(".")
            if (ipnParts.size == 2) {
                val node = ipnParts[0].toLongOrNull()
                val service = ipnParts[1].toLongOrNull()
                if (node != null && service != null) {
                    val ipnArray = CBORObject.NewArray()
                    ipnArray.Add(node)
                    ipnArray.Add(service)
                    cbor.Add(ipnArray)
                    return cbor
                }
            }
        }
        cbor.Add(ssp)
        return cbor
    }

    companion object {
        fun fromCbor(cbor: CBORObject): Eid {
            if (cbor.type != CBORType.Array || cbor.size() < 2) {
                throw IllegalArgumentException("Invalid CBOR format for EID: expected array with size >= 2")
            }
            val schemeCode = cbor[0].AsInt32()
            val sspObj = cbor[1]
            val scheme = if (schemeCode == 1) "dtn" else "ipn"
            val ssp =
                if (sspObj.type == CBORType.Array) {
                    if (sspObj.size() < 2) {
                        throw IllegalArgumentException("Invalid CBOR format for IPN EID: expected array with size >= 2")
                    }
                    "${sspObj[0].AsInt64Value()}.${sspObj[1].AsInt64Value()}"
                } else {
                    sspObj.AsString()
                }
            val uri =
                if (scheme == "dtn") {
                    if (ssp.startsWith("//")) "$scheme:$ssp" else "$scheme://$ssp"
                } else {
                    "$scheme:$ssp"
                }
            return Eid(uri)
        }
    }
}

data class PrimaryBlock(
    val version: Int = 7,
    val bundleControlFlags: Long = 0,
    val crcType: Int = 0,
    val destination: Eid,
    val source: Eid,
    val reportTo: Eid,
    // <dtnTimeSeconds, sequenceNumber>
    val creationTimestamp: Pair<Long, Long>,
    val lifetimeMs: Long,
) {
    var rawBytes: ByteArray? = null
}

data class PayloadBlock(
    val blockNumber: Int = 1,
    val blockControlFlags: Long = 0,
    val data: ByteArray,
) {
    var rawBytes: ByteArray? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PayloadBlock
        if (blockNumber != other.blockNumber) return false
        if (blockControlFlags != other.blockControlFlags) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = blockNumber
        result = 31 * result + blockControlFlags.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class HopCountBlock(
    val blockNumber: Int = 10,
    val blockControlFlags: Long = 0,
    val hopLimit: Int,
    val hopCount: Int,
)

data class BibBlock(
    val blockNumber: Int = 2,
    val blockControlFlags: Long = 0,
    // usually targets payload block (1)
    val targets: List<Int> = listOf(1),
    // BIB_HMAC_SHA2 = 1
    val securityContext: Int = 1,
    // source + parameters present
    val securityContextFlags: Long = 3,
    val securitySource: Eid,
    // HMAC-256-256 = 5
    val variant: Int = 5,
    // all included
    val scopeFlags: Int = 7,
    val signature: ByteArray,
) {
    var rawBytes: ByteArray? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BibBlock
        if (blockNumber != other.blockNumber) return false
        if (blockControlFlags != other.blockControlFlags) return false
        if (targets != other.targets) return false
        if (securityContext != other.securityContext) return false
        if (securityContextFlags != other.securityContextFlags) return false
        if (securitySource != other.securitySource) return false
        if (variant != other.variant) return false
        if (scopeFlags != other.scopeFlags) return false
        if (!signature.contentEquals(other.signature)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = blockNumber
        result = 31 * result + blockControlFlags.hashCode()
        result = 31 * result + targets.hashCode()
        result = 31 * result + securityContext
        result = 31 * result + securityContextFlags.hashCode()
        result = 31 * result + securitySource.hashCode()
        result = 31 * result + variant
        result = 31 * result + scopeFlags
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

data class Bundle(
    val primaryBlock: PrimaryBlock,
    val payloadBlock: PayloadBlock,
    val hopCountBlock: HopCountBlock? = null,
    val bibBlock: BibBlock? = null,
)

object Bpv7Parser {
    fun serializePrimaryBlock(primary: PrimaryBlock): CBORObject {
        val array = CBORObject.NewArray()
        array.Add(primary.version)
        array.Add(primary.bundleControlFlags)
        array.Add(primary.crcType)
        array.Add(primary.destination.toCbor())
        array.Add(primary.source.toCbor())
        array.Add(primary.reportTo.toCbor())

        val timestamp = CBORObject.NewArray()
        timestamp.Add(primary.creationTimestamp.first)
        timestamp.Add(primary.creationTimestamp.second)
        array.Add(timestamp)

        array.Add(primary.lifetimeMs)
        return array
    }

    fun deserializePrimaryBlock(cbor: CBORObject): PrimaryBlock {
        if (cbor.type != CBORType.Array || cbor.size() < 8) {
            throw IllegalArgumentException("Invalid PrimaryBlock CBOR: expected array with size >= 8, got size ${if (cbor.type == CBORType.Array) cbor.size() else "non-array"}")
        }
        val version = cbor[0].AsInt32()
        val flags = cbor[1].AsInt64Value()
        val crcType = cbor[2].AsInt32()
        val destination = Eid.fromCbor(cbor[3])
        val source = Eid.fromCbor(cbor[4])
        val reportTo = Eid.fromCbor(cbor[5])

        val timestampArray = cbor[6]
        if (timestampArray.type != CBORType.Array || timestampArray.size() < 2) {
            throw IllegalArgumentException("Invalid PrimaryBlock timestamp CBOR: expected array with size >= 2")
        }
        val creationTime = timestampArray[0].AsInt64Value()
        val seq = timestampArray[1].AsInt64Value()

        val lifetime = cbor[7].AsInt64Value()

        return PrimaryBlock(version, flags, crcType, destination, source, reportTo, Pair(creationTime, seq), lifetime).apply {
            rawBytes = cbor.EncodeToBytes()
        }
    }

    fun serializeCanonicalBlock(
        type: Int,
        number: Int,
        flags: Long,
        crcType: Int,
        blockData: ByteArray,
    ): CBORObject {
        val array = CBORObject.NewArray()
        array.Add(type)
        array.Add(number)
        array.Add(flags)
        array.Add(crcType)
        array.Add(CBORObject.FromObject(blockData))
        return array
    }

    fun serializeHopCountBlock(block: HopCountBlock): CBORObject {
        val hcData = CBORObject.NewArray()
        hcData.Add(block.hopLimit)
        hcData.Add(block.hopCount)
        val blockDataBytes = hcData.EncodeToBytes()
        return serializeCanonicalBlock(10, block.blockNumber, block.blockControlFlags, 0, blockDataBytes)
    }

    fun deserializeHopCountBlock(cbor: CBORObject): HopCountBlock {
        if (cbor.type != CBORType.Array || cbor.size() < 5) {
            throw IllegalArgumentException("Invalid HopCountBlock CBOR: expected array with size >= 5")
        }
        val number = cbor[1].AsInt32()
        val flags = cbor[2].AsInt64Value()
        val dataBytes = cbor[4].GetByteString()
        val hcData = CBORObject.DecodeFromBytes(dataBytes)
        if (hcData.type != CBORType.Array || hcData.size() < 2) {
            throw IllegalArgumentException("Invalid HopCountBlock data CBOR: expected array with size >= 2")
        }
        val hopLimit = hcData[0].AsInt32()
        val hopCount = hcData[1].AsInt32()
        return HopCountBlock(number, flags, hopLimit, hopCount)
    }

    fun serializeBibBlock(block: BibBlock): CBORObject {
        val stream = java.io.ByteArrayOutputStream()

        // 1. Targets
        val targetsArray = CBORObject.NewArray()
        block.targets.forEach { targetsArray.Add(it) }
        targetsArray.WriteTo(stream)

        // 2. Security Context
        CBORObject.FromObject(block.securityContext).WriteTo(stream)

        // 3. Security Context Flags
        CBORObject.FromObject(block.securityContextFlags).WriteTo(stream)

        // 4. Security Source (always present)
        block.securitySource.toCbor().WriteTo(stream)

        // 5. Parameters (if flag bit 0 is set)
        if ((block.securityContextFlags and 1L) != 0L) {
            val paramsArray = CBORObject.NewArray()

            val p1 = CBORObject.NewArray()
            p1.Add(1)
            p1.Add(block.variant)
            paramsArray.Add(p1)

            val p3 = CBORObject.NewArray()
            p3.Add(3)
            p3.Add(block.scopeFlags)
            paramsArray.Add(p3)

            paramsArray.WriteTo(stream)
        }

        // 6. Results
        val resultsArray = CBORObject.NewArray()
        val targetResults = CBORObject.NewArray()
        val r1 = CBORObject.NewArray()
        r1.Add(1)
        r1.Add(CBORObject.FromObject(block.signature))
        targetResults.Add(r1)
        resultsArray.Add(targetResults)
        resultsArray.WriteTo(stream)

        val blockDataBytes = stream.toByteArray()
        return serializeCanonicalBlock(11, block.blockNumber, block.blockControlFlags, 0, blockDataBytes)
    }

    fun deserializeBibBlock(cbor: CBORObject): BibBlock {
        if (cbor.type != CBORType.Array || cbor.size() < 5) {
            throw IllegalArgumentException("Invalid BibBlock CBOR: expected array with size >= 5")
        }
        val number = cbor[1].AsInt32()
        val flags = cbor[2].AsInt64Value()
        val dataBytes = cbor[4].GetByteString()

        val stream = java.io.ByteArrayInputStream(dataBytes)

        // 1. Targets
        val targetsArray = CBORObject.Read(stream)
        val targets = mutableListOf<Int>()
        if (targetsArray.type == CBORType.Array) {
            for (i in 0 until targetsArray.size()) {
                targets.add(targetsArray[i].AsInt32())
            }
        }

        // 2. Security Context
        val securityContext = CBORObject.Read(stream).AsInt32()

        // 3. Security Context Flags
        val securityContextFlags = CBORObject.Read(stream).AsInt64Value()

        // 4. Security Source (always present)
        val securitySource = Eid.fromCbor(CBORObject.Read(stream))

        var variant = 5
        var scopeFlags = 7
        var signature = ByteArray(0)

        // 5. Parameters (present if bit 0 of flags is set)
        if ((securityContextFlags and 1L) != 0L) {
            val paramsArray = CBORObject.Read(stream)
            if (paramsArray.type == CBORType.Array) {
                for (i in 0 until paramsArray.size()) {
                    val param = paramsArray[i]
                    if (param.type == CBORType.Array && param.size() >= 2) {
                        val paramId = param[0].AsInt32()
                        val paramVal = param[1].AsInt32()
                        if (paramId == 1) variant = paramVal
                        if (paramId == 3) scopeFlags = paramVal
                    }
                }
            }
        }

        // 6. Results
        val resultsArray = CBORObject.Read(stream)
        if (resultsArray.type == CBORType.Array && resultsArray.size() > 0) {
            val targetResults = resultsArray[0]
            if (targetResults.type == CBORType.Array) {
                for (i in 0 until targetResults.size()) {
                    val r = targetResults[i]
                    if (r.type == CBORType.Array && r.size() >= 2) {
                        val rId = r[0].AsInt32()
                        if (rId == 1) {
                            signature = r[1].GetByteString()
                        }
                    }
                }
            }
        }

        return BibBlock(number, flags, targets, securityContext, securityContextFlags, securitySource, variant, scopeFlags, signature)
    }

    fun serialize(bundle: Bundle): ByteArray {
        val array = CBORObject.NewArray()
        array.Add(serializePrimaryBlock(bundle.primaryBlock))
        bundle.hopCountBlock?.let { array.Add(serializeHopCountBlock(it)) }
        // Payload block
        array.Add(
            serializeCanonicalBlock(1, bundle.payloadBlock.blockNumber, bundle.payloadBlock.blockControlFlags, 0, bundle.payloadBlock.data),
        )
        bundle.bibBlock?.let { array.Add(serializeBibBlock(it)) }
        return array.EncodeToBytes()
    }

    fun deserialize(bytes: ByteArray): Bundle {
        val array = CBORObject.DecodeFromBytes(bytes)
        if (array.type != CBORType.Array || array.size() < 2) {
            throw IllegalArgumentException("Invalid Bundle CBOR: expected array with size >= 2")
        }
        val primary = deserializePrimaryBlock(array[0])
        var payload: PayloadBlock? = null
        var hopCount: HopCountBlock? = null
        var bib: BibBlock? = null

        for (i in 1 until array.size()) {
            val blockCbor = array[i]
            if (blockCbor.type != CBORType.Array || blockCbor.size() < 5) {
                continue
            }
            val type = blockCbor[0].AsInt32()
            when (type) {
                1 -> {
                    val number = blockCbor[1].AsInt32()
                    val flags = blockCbor[2].AsInt64Value()
                    val data = blockCbor[4].GetByteString()
                    payload =
                        PayloadBlock(number, flags, data).apply {
                            rawBytes = blockCbor.EncodeToBytes()
                        }
                }
                10 -> {
                    hopCount = deserializeHopCountBlock(blockCbor)
                }
                11 -> {
                    bib =
                        deserializeBibBlock(blockCbor).apply {
                            rawBytes = blockCbor.EncodeToBytes()
                        }
                }
            }
        }

        return Bundle(
            primaryBlock = primary,
            payloadBlock = payload ?: throw IllegalArgumentException("Missing Payload Block in bundle"),
            hopCountBlock = hopCount,
            bibBlock = bib,
        )
    }

    fun computeHmac(
        secretKey: ByteArray,
        primaryBlockBytes: ByteArray,
        targetBlockType: Int,
        targetBlockNumber: Int,
        targetBlockFlags: Long,
        securityBlockType: Int,
        securityBlockNumber: Int,
        securityBlockFlags: Long,
        payloadBytes: ByteArray,
        scopeFlags: Int = 7,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secretKey, "HmacSHA256")
        mac.init(keySpec)

        mac.update(CBORObject.FromObject(scopeFlags).EncodeToBytes())

        if ((scopeFlags and 1) != 0) {
            mac.update(primaryBlockBytes)
        }

        if ((scopeFlags and 2) != 0) {
            mac.update(CBORObject.FromObject(targetBlockType).EncodeToBytes())
            mac.update(CBORObject.FromObject(targetBlockNumber).EncodeToBytes())
            mac.update(CBORObject.FromObject(targetBlockFlags).EncodeToBytes())
        }

        if ((scopeFlags and 4) != 0) {
            mac.update(CBORObject.FromObject(securityBlockType).EncodeToBytes())
            mac.update(CBORObject.FromObject(securityBlockNumber).EncodeToBytes())
            mac.update(CBORObject.FromObject(securityBlockFlags).EncodeToBytes())
        }

        mac.update(CBORObject.FromObject(payloadBytes).EncodeToBytes())

        return mac.doFinal()
    }
}
