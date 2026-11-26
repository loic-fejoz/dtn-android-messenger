package io.github.loic_fejoz.dtn_android_messenger.protocol

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
        val schemeCode =
            when (scheme.lowercase()) {
                "dtn" -> 1
                "ipn" -> 2
                else -> throw IllegalArgumentException("Unsupported EID scheme: $scheme")
            }
        val trimmedSsp = ssp.trim()
        val cbor = CBORObject.NewArray()
        cbor.Add(schemeCode)
        if (schemeCode == 1 && (trimmedSsp.equals("none", ignoreCase = true) || trimmedSsp == "0")) {
            cbor.Add(0)
            return cbor
        }
        if (schemeCode == 2) {
            val ipnParts = trimmedSsp.split(".")
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
        val encodedSsp =
            if (schemeCode == 1) {
                val withSlashes = if (trimmedSsp.startsWith("//")) trimmedSsp else "//$trimmedSsp"
                val nodeAndDemux = withSlashes.substring(2)
                if (nodeAndDemux.isNotEmpty() && !nodeAndDemux.contains('/')) {
                    "$withSlashes/"
                } else {
                    withSlashes
                }
            } else {
                trimmedSsp
            }
        cbor.Add(encodedSsp)
        return cbor
    }

    companion object {
        fun fromCbor(cbor: CBORObject): Eid {
            require(cbor.type == CBORType.Array && cbor.size() >= 2) {
                "Invalid CBOR format for EID: expected array with size >= 2"
            }
            val schemeCode = cbor[0].AsInt32()
            val sspObj = cbor[1]
            val scheme =
                when (schemeCode) {
                    1 -> "dtn"
                    2 -> "ipn"
                    else -> throw IllegalArgumentException("Unsupported EID scheme code: $schemeCode")
                }
            val ssp =
                if (sspObj.type == CBORType.Array) {
                    require(sspObj.size() >= 2) {
                        "Invalid CBOR format for IPN EID: expected array with size >= 2"
                    }
                    "${sspObj[0].AsInt64Value()}.${sspObj[1].AsInt64Value()}"
                } else if (sspObj.isNumber) {
                    sspObj.AsInt64Value().toString()
                } else {
                    sspObj.AsString()
                }
            val uri =
                if (scheme == "dtn") {
                    if (ssp == "none" || ssp == "0") {
                        "dtn:none"
                    } else if (ssp.startsWith("//")) {
                        "$scheme:$ssp"
                    } else {
                        "$scheme://$ssp"
                    }
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
    val crcType: Int = 2,
    val destination: Eid,
    val source: Eid,
    val reportTo: Eid,
    // <dtnTimeMillis, sequenceNumber>
    val creationTimestamp: Pair<Long, Long>,
    val lifetimeMs: Long,
    val crc: ByteArray? = null,
) {
    var rawBytes: ByteArray? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PrimaryBlock
        if (version != other.version) return false
        if (bundleControlFlags != other.bundleControlFlags) return false
        if (crcType != other.crcType) return false
        if (destination != other.destination) return false
        if (source != other.source) return false
        if (reportTo != other.reportTo) return false
        if (creationTimestamp != other.creationTimestamp) return false
        if (lifetimeMs != other.lifetimeMs) return false
        if (crc != null) {
            if (other.crc == null) return false
            if (!crc.contentEquals(other.crc)) return false
        } else if (other.crc != null) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + bundleControlFlags.hashCode()
        result = 31 * result + crcType
        result = 31 * result + destination.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + reportTo.hashCode()
        result = 31 * result + creationTimestamp.hashCode()
        result = 31 * result + lifetimeMs.hashCode()
        result = 31 * result + (crc?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val FLAG_ADMIN_RECORD = 0x02L
    }
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
    // RFC 9172 §3.6: Bit 0 = Security Context Parameters Present (reserved bits MUST be 0)
    val securityContextFlags: Long = 1,
    val securitySource: Eid,
    // HMAC-256-256 = 5
    val variant: Int = 5,
    // all included
    val scopeFlags: Int = 7,
    val signature: ByteArray,
    val signaturesByTarget: Map<Int, ByteArray> = emptyMap(),
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
        if (signaturesByTarget.size != other.signaturesByTarget.size) return false
        if (!signaturesByTarget.all { (k, v) -> other.signaturesByTarget[k]?.contentEquals(v) == true }) return false
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
        val sigMapHash =
            signaturesByTarget.entries
                .sortedBy { it.key }
                .fold(0) { acc, (k, v) -> 31 * (31 * acc + k) + v.contentHashCode() }
        result = 31 * result + sigMapHash
        return result
    }
}

data class Bundle(
    val primaryBlock: PrimaryBlock,
    val payloadBlock: PayloadBlock,
    val hopCountBlock: HopCountBlock? = null,
    val bibBlock: BibBlock? = null,
    val bibBlocks: List<BibBlock> = listOfNotNull(bibBlock),
)

object CrcHelper {
    // CRC-32C (Castagnoli)
    // Poly: 0x1EDC6F41 (reversed: 0x82F63B78)
    // Init: 0xFFFFFFFF, RefIn: true, RefOut: true, XorOut: 0xFFFFFFFF
    private val CRC32C_TABLE =
        IntArray(256) { i ->
            var crc = i
            for (j in 0 until 8) {
                crc =
                    if ((crc and 1) != 0) {
                        (crc ushr 1) xor 0x82F63B78.toInt()
                    } else {
                        crc ushr 1
                    }
            }
            crc
        }

    // CRC-16 (X.25 / CRC-16-IBM-SDLC)
    // Poly: 0x1021 (reversed: 0x8408)
    // Init: 0xFFFF, RefIn: true, RefOut: true, XorOut: 0xFFFF
    private val CRC16_TABLE =
        IntArray(256) { i ->
            var crc = i
            for (j in 0 until 8) {
                crc =
                    if ((crc and 1) != 0) {
                        (crc ushr 1) xor 0x8408
                    } else {
                        crc ushr 1
                    }
            }
            crc and 0xFFFF
        }

    fun computeCrc32c(data: ByteArray): ByteArray {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            val index = (crc xor b.toInt()) and 0xFF
            crc = CRC32C_TABLE[index] xor (crc ushr 8)
        }
        val result = crc xor 0xFFFFFFFF.toInt()
        return byteArrayOf(
            ((result ushr 24) and 0xFF).toByte(),
            ((result ushr 16) and 0xFF).toByte(),
            ((result ushr 8) and 0xFF).toByte(),
            (result and 0xFF).toByte(),
        )
    }

    fun computeCrc16(data: ByteArray): ByteArray {
        var crc = 0xFFFF
        for (b in data) {
            val index = (crc xor b.toInt()) and 0xFF
            crc = CRC16_TABLE[index] xor (crc ushr 8)
        }
        val result = (crc xor 0xFFFF) and 0xFFFF
        return byteArrayOf(
            ((result ushr 8) and 0xFF).toByte(),
            (result and 0xFF).toByte(),
        )
    }
}

object Bpv7Parser {
    const val DTN_EPOCH_OFFSET_MS = 946684800000L

    fun toDtnTimeMillis(systemMillis: Long): Long = systemMillis - DTN_EPOCH_OFFSET_MS

    fun fromDtnTimeMillis(dtnMillis: Long): Long = dtnMillis + DTN_EPOCH_OFFSET_MS

    fun computeCrc32c(data: ByteArray): ByteArray = CrcHelper.computeCrc32c(data)

    fun computeCrc16(data: ByteArray): ByteArray = CrcHelper.computeCrc16(data)

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

        when (primary.crcType) {
            1 -> {
                val crcBytes =
                    primary.crc ?: run {
                        array.Add(CBORObject.FromObject(ByteArray(2)))
                        computeCrc16(array.EncodeToBytes())
                    }
                if (array.size() > 8) {
                    array.set(8, CBORObject.FromObject(crcBytes))
                } else {
                    array.Add(CBORObject.FromObject(crcBytes))
                }
            }
            2 -> {
                val crcBytes =
                    primary.crc ?: run {
                        array.Add(CBORObject.FromObject(ByteArray(4)))
                        computeCrc32c(array.EncodeToBytes())
                    }
                if (array.size() > 8) {
                    array.set(8, CBORObject.FromObject(crcBytes))
                } else {
                    array.Add(CBORObject.FromObject(crcBytes))
                }
            }
        }

        return array
    }

    fun deserializePrimaryBlock(cbor: CBORObject): PrimaryBlock {
        require(cbor.type == CBORType.Array && cbor.size() >= 8) {
            "Invalid PrimaryBlock CBOR: expected array with size >= 8, got size ${if (cbor.type == CBORType.Array) cbor.size() else "non-array"}"
        }
        val version = cbor[0].AsInt32()
        require(version == 7) {
            "Unsupported BP version: $version. Only BPv7 (version 7) is supported."
        }
        val flags = cbor[1].AsInt64Value()
        val crcType = cbor[2].AsInt32()
        val destination = Eid.fromCbor(cbor[3])
        val source = Eid.fromCbor(cbor[4])
        val reportTo = Eid.fromCbor(cbor[5])

        val timestampArray = cbor[6]
        require(timestampArray.type == CBORType.Array && timestampArray.size() >= 2) {
            "Invalid PrimaryBlock timestamp CBOR: expected array with size >= 2"
        }
        val creationTime = timestampArray[0].AsInt64Value()
        val seq = timestampArray[1].AsInt64Value()

        val lifetime = cbor[7].AsInt64Value()

        var crcBytes: ByteArray? = null
        if (crcType != 0) {
            require(crcType in 1..2) {
                "Unsupported CRC type in PrimaryBlock: $crcType"
            }
            require(cbor.size() >= 9) {
                "Missing CRC field in PrimaryBlock: expected 9 elements for crcType $crcType, got ${cbor.size()}"
            }
            crcBytes = cbor[8].GetByteString()
            val expectedLen = if (crcType == 1) 2 else 4
            require(crcBytes.size == expectedLen) {
                "Invalid CRC length in PrimaryBlock: expected $expectedLen bytes, got ${crcBytes.size}"
            }

            val verifyArray = CBORObject.NewArray()
            for (i in 0 until 8) {
                verifyArray.Add(cbor[i])
            }
            if (crcType == 1) {
                verifyArray.Add(CBORObject.FromObject(ByteArray(2)))
                val computed = computeCrc16(verifyArray.EncodeToBytes())
                if (!computed.contentEquals(crcBytes)) {
                    throw IllegalArgumentException(
                        "PrimaryBlock CRC-16 mismatch: expected ${io.github.loic_fejoz.dtn_android_messenger.util.HexUtils.bytesToHex(crcBytes)}, " +
                            "computed ${io.github.loic_fejoz.dtn_android_messenger.util.HexUtils.bytesToHex(computed)}",
                    )
                }
            } else if (crcType == 2) {
                verifyArray.Add(CBORObject.FromObject(ByteArray(4)))
                val computed = computeCrc32c(verifyArray.EncodeToBytes())
                if (!computed.contentEquals(crcBytes)) {
                    throw IllegalArgumentException(
                        "PrimaryBlock CRC-32C mismatch: expected ${io.github.loic_fejoz.dtn_android_messenger.util.HexUtils.bytesToHex(crcBytes)}, " +
                            "computed ${io.github.loic_fejoz.dtn_android_messenger.util.HexUtils.bytesToHex(computed)}",
                    )
                }
            }
        }

        return PrimaryBlock(version, flags, crcType, destination, source, reportTo, Pair(creationTime, seq), lifetime, crcBytes).apply {
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
        require(cbor.type == CBORType.Array && cbor.size() >= 5) {
            "Invalid HopCountBlock CBOR: expected array with size >= 5"
        }
        val number = cbor[1].AsInt32()
        val flags = cbor[2].AsInt64Value()
        val dataBytes = cbor[4].GetByteString()
        val hcData = CBORObject.DecodeFromBytes(dataBytes)
        require(hcData.type == CBORType.Array && hcData.size() >= 2) {
            "Invalid HopCountBlock data CBOR: expected array with size >= 2"
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
        for (target in block.targets) {
            val targetSig = block.signaturesByTarget[target] ?: block.signature
            val r1 = CBORObject.NewArray()
            r1.Add(1) // Result ID 1: HMAC Value
            r1.Add(CBORObject.FromObject(targetSig))
            val targetResults = CBORObject.NewArray()
            targetResults.Add(r1)
            resultsArray.Add(targetResults)
        }
        resultsArray.WriteTo(stream)

        val blockDataBytes = stream.toByteArray()
        return serializeCanonicalBlock(11, block.blockNumber, block.blockControlFlags, 0, blockDataBytes)
    }

    private fun readRequiredCbor(
        stream: java.io.ByteArrayInputStream,
        fieldName: String,
    ): CBORObject {
        return try {
            CBORObject.Read(stream)
                ?: throw IllegalArgumentException("Truncated BibBlock CBOR while reading $fieldName")
        } catch (e: com.upokecenter.cbor.CBORException) {
            throw IllegalArgumentException("Truncated or malformed BibBlock CBOR while reading $fieldName", e)
        }
    }

    private fun verifyCanonicalBlockCrc(blockCbor: CBORObject) {
        val crcType = blockCbor[3].AsInt32()
        if (crcType == 0) return
        require(crcType in 1..2) {
            "Unsupported CRC type in canonical block: $crcType"
        }
        require(blockCbor.size() >= 6) {
            "Missing CRC field in canonical block for crcType $crcType"
        }
        val crcBytes = blockCbor[5].GetByteString()
        val expectedLen = if (crcType == 1) 2 else 4
        require(crcBytes.size == expectedLen) {
            "Invalid CRC length in canonical block: expected $expectedLen bytes, got ${crcBytes.size}"
        }
        val verifyArray = CBORObject.NewArray()
        for (i in 0 until 5) {
            verifyArray.Add(blockCbor[i])
        }
        verifyArray.Add(CBORObject.FromObject(ByteArray(expectedLen)))
        val computed = if (crcType == 1) computeCrc16(verifyArray.EncodeToBytes()) else computeCrc32c(verifyArray.EncodeToBytes())
        require(computed.contentEquals(crcBytes)) {
            "Canonical block CRC mismatch for crcType $crcType"
        }
    }

    fun deserializeBibBlock(cbor: CBORObject): BibBlock {
        require(cbor.type == CBORType.Array && cbor.size() >= 5) {
            "Invalid BibBlock CBOR: expected array with size >= 5"
        }
        val number = cbor[1].AsInt32()
        val flags = cbor[2].AsInt64Value()
        val dataBytes = cbor[4].GetByteString()

        val stream = java.io.ByteArrayInputStream(dataBytes)

        // 1. Targets
        val targetsArray = readRequiredCbor(stream, "targets")
        val targets = mutableListOf<Int>()
        if (targetsArray.type == CBORType.Array) {
            for (i in 0 until targetsArray.size()) {
                targets.add(targetsArray[i].AsInt32())
            }
        }

        // 2. Security Context
        val securityContext = readRequiredCbor(stream, "securityContext").AsInt32()

        // 3. Security Context Flags
        val securityContextFlags = readRequiredCbor(stream, "securityContextFlags").AsInt64Value()

        // 4. Security Source (always present)
        val securitySource = Eid.fromCbor(readRequiredCbor(stream, "securitySource"))

        var variant = 5
        var scopeFlags = 7

        // 5. Parameters (present if bit 0 of flags is set)
        if ((securityContextFlags and 1L) != 0L) {
            val paramsArray = readRequiredCbor(stream, "parameters")
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
        val resultsArray = readRequiredCbor(stream, "results")
        val signaturesByTarget = LinkedHashMap<Int, ByteArray>()
        if (resultsArray.type == CBORType.Array && resultsArray.size() > 0) {
            for (idx in 0 until resultsArray.size()) {
                val targetBlockNum = targets.getOrNull(idx) ?: continue
                val targetResults = resultsArray[idx]
                if (targetResults.type == CBORType.Array) {
                    for (i in 0 until targetResults.size()) {
                        val r = targetResults[i]
                        if (r.type == CBORType.Array && r.size() >= 2 && r[0].AsInt32() == 1) {
                            signaturesByTarget[targetBlockNum] = r[1].GetByteString()
                        }
                    }
                }
            }
        }
        val signature = signaturesByTarget[1] ?: signaturesByTarget[targets.firstOrNull()] ?: ByteArray(0)

        return BibBlock(
            blockNumber = number,
            blockControlFlags = flags,
            targets = targets,
            securityContext = securityContext,
            securityContextFlags = securityContextFlags,
            securitySource = securitySource,
            variant = variant,
            scopeFlags = scopeFlags,
            signature = signature,
            signaturesByTarget = signaturesByTarget,
        )
    }

    fun serialize(bundle: Bundle): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x9F) // RFC 9171 §4.2.1: CBOR indefinite-length array start
        out.write(serializePrimaryBlock(bundle.primaryBlock).EncodeToBytes())
        bundle.hopCountBlock?.let { out.write(serializeHopCountBlock(it).EncodeToBytes()) }
        bundle.bibBlocks.forEach { out.write(serializeBibBlock(it).EncodeToBytes()) }
        // RFC 9171 §4.3.1: Payload block MUST be the last block in the bundle
        out.write(
            serializeCanonicalBlock(
                1,
                bundle.payloadBlock.blockNumber,
                bundle.payloadBlock.blockControlFlags,
                0,
                bundle.payloadBlock.data,
            ).EncodeToBytes(),
        )
        out.write(0xFF) // CBOR break stop code
        return out.toByteArray()
    }

    fun deserialize(bytes: ByteArray): Bundle {
        val array = CBORObject.DecodeFromBytes(bytes)
        require(array.type == CBORType.Array && array.size() >= 2) {
            "Invalid Bundle CBOR: expected array with size >= 2"
        }
        val primary = deserializePrimaryBlock(array[0])
        var payload: PayloadBlock? = null
        var hopCount: HopCountBlock? = null
        val bibBlocks = mutableListOf<BibBlock>()

        for (i in 1 until array.size()) {
            val blockCbor = array[i]
            require(blockCbor.type == CBORType.Array && blockCbor.size() >= 5) {
                "Invalid canonical block CBOR at index $i"
            }
            verifyCanonicalBlockCrc(blockCbor)
            val type = blockCbor[0].AsInt32()
            val blockFlags = blockCbor[2].AsInt64Value()
            when (type) {
                1 -> {
                    val number = blockCbor[1].AsInt32()
                    val data = blockCbor[4].GetByteString()
                    payload =
                        PayloadBlock(number, blockFlags, data).apply {
                            rawBytes = blockCbor.EncodeToBytes()
                        }
                }
                10 -> {
                    hopCount = deserializeHopCountBlock(blockCbor)
                }
                11 -> {
                    bibBlocks.add(
                        deserializeBibBlock(blockCbor).apply {
                            rawBytes = blockCbor.EncodeToBytes()
                        },
                    )
                }
                12 -> {
                    throw IllegalArgumentException(
                        "BPSec BCB (Block Type 12) is excluded for amateur radio cleartext compliance (ITU Article 25)",
                    )
                }
                else -> {
                    require((blockFlags and 0x04L) == 0L) {
                        "Unknown canonical block type $type requires bundle deletion (block flag 0x04)"
                    }
                }
            }
        }

        val resolvedPayload = payload ?: throw IllegalArgumentException("Missing Payload Block in bundle")
        val payloadBib =
            bibBlocks.firstOrNull { it.targets.contains(resolvedPayload.blockNumber) }
                ?: bibBlocks.firstOrNull()

        return Bundle(
            primaryBlock = primary,
            payloadBlock = resolvedPayload,
            hopCountBlock = hopCount,
            bibBlock = payloadBib,
            bibBlocks = bibBlocks,
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
