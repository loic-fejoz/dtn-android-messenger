package io.github.loic_fejoz.dtn_android_messenger.routing

import io.github.loic_fejoz.dtn_android_messenger.cla.FileClaAdapter
import io.github.loic_fejoz.dtn_android_messenger.cla.LocalFileClaDirectoryFactory
import io.github.loic_fejoz.dtn_android_messenger.data.model.BpsecStatus
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleRecord
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleState
import io.github.loic_fejoz.dtn_android_messenger.data.model.ConvergenceProfile
import io.github.loic_fejoz.dtn_android_messenger.data.model.RoutingRule
import io.github.loic_fejoz.dtn_android_messenger.data.model.TriggerType
import io.github.loic_fejoz.dtn_android_messenger.protocol.BibBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bpv7Parser
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle
import io.github.loic_fejoz.dtn_android_messenger.protocol.Eid
import io.github.loic_fejoz.dtn_android_messenger.protocol.HopCountBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PayloadBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PrimaryBlock
import io.github.loic_fejoz.dtn_android_messenger.service.DtnEngineService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class FileClaIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createAdapter(maxBytes: Long = 10L * 1024L * 1024L): FileClaAdapter =
        FileClaAdapter(
            directoryFactory = LocalFileClaDirectoryFactory(),
            maxBundleSizeBytesProvider = { maxBytes },
            logger = { _, _ -> },
        )

    @Test
    fun endToEnd_routingToFileCla_signsBundle_writesTx_andPeerVerifiesHmac() =
        runBlocking {
            val baseDir = tempDir.toAbsolutePath().toString()
            val rules =
                listOf(
                    RoutingRule(destinationEidPattern = "dtn://usb-peer/*", nextHopEid = "dtn://usb-peer"),
                )
            val profile =
                ConvergenceProfile(
                    profileId = "dtn://usb-peer",
                    name = "Foo",
                    triggerType = TriggerType.FILE_CLA,
                    targetAddress = baseDir,
                    triggerCondition = "300",
                )

            // 1. Verify routing selects the File-CLA profile
            val nextHop = DtnEngineService.selectNextHop("dtn://usb-peer/chat", rules)
            assertEquals(profile.profileId, nextHop)

            // 2. Build & BPSec-sign outgoing bundle
            val secretKey = "shared-usb-hmac-key".toByteArray(Charsets.UTF_8)
            val primary =
                PrimaryBlock(
                    destination = Eid("dtn://usb-peer/chat"),
                    source = Eid("dtn://local-node/chat"),
                    reportTo = Eid("dtn://local-node/chat"),
                    creationTimestamp = Pair(123456L, 7L),
                    lifetimeMs = 3600_000L,
                )
            val payloadBytes = "Signed File-CLA payload".toByteArray(Charsets.UTF_8)
            val payload = PayloadBlock(data = payloadBytes)
            val hopCount = HopCountBlock(hopLimit = 64, hopCount = 1)
            val rawPrimary = Bpv7Parser.serializePrimaryBlock(primary).EncodeToBytes()
            val sig =
                Bpv7Parser.computeHmac(
                    secretKey = secretKey,
                    primaryBlockBytes = rawPrimary,
                    targetBlockType = 1,
                    targetBlockNumber = payload.blockNumber,
                    targetBlockFlags = payload.blockControlFlags,
                    securityBlockType = 11,
                    securityBlockNumber = 2,
                    securityBlockFlags = 0L,
                    payloadBytes = payloadBytes,
                    scopeFlags = 7,
                )
            val bib =
                BibBlock(
                    blockNumber = 2,
                    blockControlFlags = 0L,
                    securitySource = Eid("dtn://local-node/chat"),
                    signature = sig,
                )
            val bundle = Bundle(primary, payload, hopCount, bib)
            val serialized = Bpv7Parser.serialize(bundle)

            // 3. Flush via FileClaAdapter and assert state transitions to DELIVERED
            val recordId = "bundle-e2e-tx-001"
            var recordState = BundleState.OUTBOX
            val adapter = createAdapter()
            val sent =
                adapter.sendNamedBundles(
                    baseTreeUri = profile.targetAddress,
                    claName = profile.name,
                    bundles =
                        listOf(
                            FileClaAdapter.NamedBundle(
                                bundleId = recordId,
                                bundleBytes = serialized,
                                onAcknowledged = { recordState = BundleState.DELIVERED },
                            ),
                        ),
                )
            assertTrue(sent)
            assertEquals(BundleState.DELIVERED, recordState)

            // 4. Peer reads Foo/TX/<bundleId>.bundle, deserializes, and verifies HMAC-SHA256
            val txFile = File(tempDir.toFile(), "Foo/TX/$recordId.bundle")
            assertTrue(txFile.exists())
            val decoded = Bpv7Parser.deserialize(txFile.readBytes())
            assertEquals("dtn://usb-peer/chat", decoded.primaryBlock.destination.uri)
            assertEquals("dtn://local-node/chat", decoded.primaryBlock.source.uri)
            assertEquals("Signed File-CLA payload", String(decoded.payloadBlock.data, Charsets.UTF_8))
            assertNotNull(decoded.bibBlock)

            val decodedRawPrimary = Bpv7Parser.serializePrimaryBlock(decoded.primaryBlock).EncodeToBytes()
            val expectedSig =
                Bpv7Parser.computeHmac(
                    secretKey = secretKey,
                    primaryBlockBytes = decodedRawPrimary,
                    targetBlockType = 1,
                    targetBlockNumber = decoded.payloadBlock.blockNumber,
                    targetBlockFlags = decoded.payloadBlock.blockControlFlags,
                    securityBlockType = 11,
                    securityBlockNumber = decoded.bibBlock!!.blockNumber,
                    securityBlockFlags = decoded.bibBlock!!.blockControlFlags,
                    payloadBytes = decoded.payloadBlock.data,
                    scopeFlags = 7,
                )
            assertArrayEquals(expectedSig, decoded.bibBlock!!.signature)
        }

    @Test
    fun endToEnd_rxBundleIngestion_persistsPayload_andRespectsResponsibilityTransfer() =
        runBlocking {
            val baseDir = tempDir.toAbsolutePath().toString()
            val adapter = createAdapter()
            assertTrue(adapter.ensureClaFolders(baseDir, "Foo"))

            val rxDir = File(tempDir.toFile(), "Foo/RX")
            val payloadsDir = File(tempDir.toFile(), "app_payloads").apply { mkdirs() }

            val incomingBundle =
                Bundle(
                    primaryBlock =
                        PrimaryBlock(
                            destination = Eid("dtn://local-node/chat"),
                            source = Eid("dtn://usb-peer/chat"),
                            reportTo = Eid("dtn://usb-peer/chat"),
                            creationTimestamp = Pair(555555L, 9L),
                            lifetimeMs = 3600_000L,
                        ),
                    payloadBlock = PayloadBlock(data = "From external Rattlechan/USB".toByteArray(Charsets.UTF_8)),
                    hopCountBlock = HopCountBlock(hopLimit = 64, hopCount = 0),
                    bibBlock = null,
                )
            val bundleBytes = Bpv7Parser.serialize(incomingBundle)

            // 1. Simulate DB failure -> RX file must remain untouched
            val rxFailedFile = File(rxDir, "ext_msg_1.bundle").apply { writeBytes(bundleBytes) }
            val countOnFailure = adapter.scanIncoming(baseDir, "Foo") { false }
            assertEquals(0, countOnFailure)
            assertTrue(rxFailedFile.exists())

            // 2. Simulate full ingestion: deserialize, write payload to persistent disk, record in DB, return true
            val rxAcceptedFile = File(rxDir, "ext_msg_2.bundle").apply { writeBytes(bundleBytes) }
            val persistedRecords = mutableListOf<BundleRecord>()
            val countOnSuccess =
                adapter.scanIncoming(baseDir, "Foo") { bytes ->
                    val parsed = Bpv7Parser.deserialize(bytes)
                    val payloadOut = File(payloadsDir, "ingested.bin")
                    payloadOut.writeBytes(parsed.payloadBlock.data)
                    persistedRecords.add(
                        BundleRecord(
                            bundleId = "rx-e2e-002",
                            destinationEid = parsed.primaryBlock.destination.uri,
                            sourceEid = parsed.primaryBlock.source.uri,
                            creationTimestamp = parsed.primaryBlock.creationTimestamp.first,
                            sequenceNumber = parsed.primaryBlock.creationTimestamp.second,
                            lifetimeMs = parsed.primaryBlock.lifetimeMs,
                            payloadFilePath = payloadOut.absolutePath,
                            state = BundleState.RECEIVED,
                            isRead = false,
                            bpsecStatus = BpsecStatus.UNVERIFIED,
                            hopCount = parsed.hopCountBlock?.hopCount ?: 0,
                        ),
                    )
                    true
                }

            assertEquals(1, countOnSuccess)
            assertFalse(rxAcceptedFile.exists())
            assertEquals(1, persistedRecords.size)
            assertEquals("From external Rattlechan/USB", File(persistedRecords[0].payloadFilePath).readText(Charsets.UTF_8))
        }
}
