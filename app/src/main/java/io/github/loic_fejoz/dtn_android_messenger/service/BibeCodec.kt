package io.github.loic_fejoz.dtn_android_messenger.service

import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bpv7Parser
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle
import io.github.loic_fejoz.dtn_android_messenger.protocol.Eid
import io.github.loic_fejoz.dtn_android_messenger.protocol.HopCountBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PayloadBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PrimaryBlock

internal object BibeCodec {
    private const val BIBE_ADMIN_RECORD_TYPE = 64443

    fun buildBibeOuterBundle(
        innerBundleBytes: ByteArray,
        innerBundleId: String,
        bibeGateway: String,
        localNodeBase: String,
        creationTimeMillis: Long,
        sequenceNumber: Long,
        lifetimeMs: Long,
        hopCount: Int,
    ): Bundle {
        val pduContent =
            CBORObject.NewArray().apply {
                Add(innerBundleId.hashCode() and 0x7FFFFFFF) // transmission-id
                Add(0) // retransmission-time
                Add(innerBundleBytes) // encapsulated-bundle
            }
        val pdu =
            CBORObject.NewArray().apply {
                Add(BIBE_ADMIN_RECORD_TYPE)
                Add(pduContent)
            }
        val outerPayloadBytes = pdu.EncodeToBytes()

        val cleanGateway = bibeGateway.trim().trimEnd('/')
        val gatewayBibeEid = if (cleanGateway.endsWith("/bibe")) cleanGateway else "$cleanGateway/bibe"
        val localBibeEid = "${localNodeBase.trimEnd('/')}/bibe"

        val outerPrimary =
            PrimaryBlock(
                bundleControlFlags = PrimaryBlock.FLAG_ADMIN_RECORD,
                destination = Eid(gatewayBibeEid),
                source = Eid(localBibeEid),
                reportTo = Eid(localBibeEid),
                creationTimestamp = Pair(creationTimeMillis - 946684800000L, sequenceNumber),
                lifetimeMs = lifetimeMs,
            )
        val outerPayload = PayloadBlock(data = outerPayloadBytes)
        val outerHopCount = HopCountBlock(hopLimit = 64, hopCount = hopCount)
        return Bundle(outerPrimary, outerPayload, outerHopCount, null)
    }

    fun extractInnerBundleBytes(
        payloadData: ByteArray,
        log: (String, String) -> Unit,
    ): ByteArray? {
        try {
            val cbor = CBORObject.DecodeFromBytes(payloadData)
            if (cbor.type == CBORType.Array && cbor.size() >= 2 && cbor[0].AsInt32() == BIBE_ADMIN_RECORD_TYPE) {
                val content = cbor[1]
                if (content.type == CBORType.Array && content.size() >= 3) {
                    log("INFO", "Decapsulated BIBE PDU (type $BIBE_ADMIN_RECORD_TYPE). Re-processing inner bundle.")
                    return content[2].GetByteString()
                }
            }
        } catch (_: Exception) {
            // Not a CBOR administrative record
        }

        try {
            val innerBundle = Bpv7Parser.deserialize(payloadData)
            if (innerBundle.primaryBlock.version == 7) {
                log("INFO", "Decapsulated raw Bundle-in-Bundle. Re-processing inner bundle.")
                return payloadData
            }
        } catch (_: Exception) {
            // Not a raw bundle
        }
        return null
    }
}
