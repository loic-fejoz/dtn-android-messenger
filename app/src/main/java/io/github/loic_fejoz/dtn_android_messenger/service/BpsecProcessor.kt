package io.github.loic_fejoz.dtn_android_messenger.service

import io.github.loic_fejoz.dtn_android_messenger.data.model.BpsecKey
import io.github.loic_fejoz.dtn_android_messenger.data.model.BpsecStatus
import io.github.loic_fejoz.dtn_android_messenger.protocol.BibBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bpv7Parser
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle
import io.github.loic_fejoz.dtn_android_messenger.protocol.Eid
import io.github.loic_fejoz.dtn_android_messenger.protocol.PayloadBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PrimaryBlock
import io.github.loic_fejoz.dtn_android_messenger.util.CryptoManager
import io.github.loic_fejoz.dtn_android_messenger.util.PayloadUtils
import java.security.MessageDigest

internal object BpsecProcessor {
    data class VerificationResult(
        val accepted: Boolean,
        val status: BpsecStatus,
    )

    fun selectBpsecKey(
        targetEid: String,
        keys: List<BpsecKey>,
    ): BpsecKey? {
        if (!PayloadUtils.isValidEid(targetEid)) return null
        return keys
            .filter { key ->
                PayloadUtils.isValidEid(key.nodeEid) &&
                    PayloadUtils.isPrefixMatch(key.nodeEid, targetEid)
            }
            .maxByOrNull { it.nodeEid.trim().trimEnd('/').length }
    }

    fun shouldRejectUnsignedBundle(policy: String): Boolean = policy == "strict"

    fun verifyIncomingBib(
        bundle: Bundle,
        policy: String,
        keys: List<BpsecKey>,
        log: (String, String) -> Unit,
    ): VerificationResult {
        val primary = bundle.primaryBlock
        val payload = bundle.payloadBlock
        val bib = bundle.bibBlock

        if (bib == null) {
            if (shouldRejectUnsignedBundle(policy)) {
                log("ERROR", "BPSec signature missing under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                return VerificationResult(accepted = false, status = BpsecStatus.UNVERIFIED)
            }
            val keyRecord = selectBpsecKey(primary.source.uri, keys)
            if (keyRecord != null && policy == "warn") {
                log("WARN", "BPSec signature missing under WARN policy for bundle from ${primary.source.uri}.")
            }
            return VerificationResult(accepted = true, status = BpsecStatus.UNVERIFIED)
        }

        val isSupportedBib =
            bib.securityContext == 1 &&
                bib.variant == 5 &&
                bib.targets.contains(payload.blockNumber)
        if (!isSupportedBib) {
            log(
                "WARN",
                "Unsupported BIB parameters (context=${bib.securityContext}, variant=${bib.variant}, targets=${bib.targets}) from ${primary.source.uri}",
            )
            if (policy == "strict") {
                log("ERROR", "Unsupported BIB block under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                return VerificationResult(accepted = false, status = BpsecStatus.UNVERIFIED)
            }
            return VerificationResult(accepted = true, status = BpsecStatus.UNVERIFIED)
        }

        val keyRecord =
            selectBpsecKey(bib.securitySource.uri, keys)
                ?: selectBpsecKey(primary.source.uri, keys)
        if (keyRecord == null) {
            log("WARN", "No BPSec key found for source ${bib.securitySource.uri} / ${primary.source.uri}, verification skipped")
            if (policy == "strict") {
                log("ERROR", "BPSec key missing under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                return VerificationResult(accepted = false, status = BpsecStatus.UNVERIFIED)
            }
            return VerificationResult(accepted = true, status = BpsecStatus.UNVERIFIED)
        }

        val decryptedKey =
            try {
                CryptoManager.decrypt(keyRecord.secretKey)
            } catch (_: Exception) {
                log("ERROR", "Failed to decrypt BPSec key for ${primary.source.uri}")
                return VerificationResult(accepted = false, status = BpsecStatus.UNVERIFIED)
            }

        val rawPrimaryBytes = primary.rawBytes ?: Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()
        val computedSignature =
            try {
                Bpv7Parser.computeHmac(
                    secretKey = decryptedKey,
                    primaryBlockBytes = rawPrimaryBytes,
                    targetBlockType = 1,
                    targetBlockNumber = payload.blockNumber,
                    targetBlockFlags = payload.blockControlFlags,
                    securityBlockType = 11,
                    securityBlockNumber = bib.blockNumber,
                    securityBlockFlags = bib.blockControlFlags,
                    payloadBytes = payload.data,
                    scopeFlags = bib.scopeFlags,
                )
            } finally {
                decryptedKey.fill(0)
            }

        val status =
            if (MessageDigest.isEqual(computedSignature, bib.signature)) {
                BpsecStatus.VALID
            } else {
                BpsecStatus.INVALID
            }
        log("INFO", "BPSec signature verification: $status")

        if (status == BpsecStatus.INVALID) {
            if (policy == "strict") {
                log("ERROR", "BPSec signature invalid under STRICT policy. Discarding bundle from ${primary.source.uri}.")
                return VerificationResult(accepted = false, status = status)
            } else if (policy == "warn") {
                log("WARN", "BPSec signature invalid under WARN policy for bundle from ${primary.source.uri}.")
            }
        }
        return VerificationResult(accepted = true, status = status)
    }

    fun signOutgoingBundle(
        primary: PrimaryBlock,
        payload: PayloadBlock,
        sourceEid: String,
        keyRecord: BpsecKey,
        log: (String, String) -> Unit,
    ): BibBlock? {
        val decryptedKey =
            try {
                CryptoManager.decrypt(keyRecord.secretKey)
            } catch (_: Exception) {
                log("ERROR", "Failed to decrypt local BPSec key for signature")
                return null
            }

        val bibBlockNumber = 2
        val bibBlockFlags = 0L
        val rawPrimary = Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()
        val signature =
            try {
                Bpv7Parser.computeHmac(
                    secretKey = decryptedKey,
                    primaryBlockBytes = rawPrimary,
                    targetBlockType = 1,
                    targetBlockNumber = payload.blockNumber,
                    targetBlockFlags = payload.blockControlFlags,
                    securityBlockType = 11,
                    securityBlockNumber = bibBlockNumber,
                    securityBlockFlags = bibBlockFlags,
                    payloadBytes = payload.data,
                    scopeFlags = 7,
                )
            } finally {
                decryptedKey.fill(0)
            }
        return BibBlock(
            blockNumber = bibBlockNumber,
            blockControlFlags = bibBlockFlags,
            securitySource = Eid(sourceEid),
            signature = signature,
        )
    }
}
