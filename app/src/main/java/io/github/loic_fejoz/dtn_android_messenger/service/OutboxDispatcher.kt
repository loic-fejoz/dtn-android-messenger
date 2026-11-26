package io.github.loic_fejoz.dtn_android_messenger.service

import android.content.Context
import io.github.loic_fejoz.dtn_android_messenger.cla.ConvergenceLayerAdapter
import io.github.loic_fejoz.dtn_android_messenger.cla.FileClaAdapter
import io.github.loic_fejoz.dtn_android_messenger.data.dao.BpsecKeyDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.BundleRecordDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.ConvergenceProfileDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.RoutingRuleDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleRecord
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleState
import io.github.loic_fejoz.dtn_android_messenger.data.model.ConvergenceProfile
import io.github.loic_fejoz.dtn_android_messenger.data.model.TriggerType
import io.github.loic_fejoz.dtn_android_messenger.data.repository.BundleOutboxRepository
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bpv7Parser
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle
import io.github.loic_fejoz.dtn_android_messenger.protocol.Eid
import io.github.loic_fejoz.dtn_android_messenger.protocol.HopCountBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PayloadBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PrimaryBlock
import io.github.loic_fejoz.dtn_android_messenger.util.PayloadFileHelper
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper
import io.github.loic_fejoz.dtn_android_messenger.util.TimeProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal class OutboxDispatcher(
    private val context: Context,
    private val bundleRecordDao: BundleRecordDao,
    private val routingRuleDao: RoutingRuleDao,
    private val convergenceProfileDao: ConvergenceProfileDao,
    private val bpsecKeyDao: BpsecKeyDao,
    private val outboxRepository: BundleOutboxRepository,
    private val timeProvider: TimeProvider,
    private val fileClaAdapter: FileClaAdapter,
    private val adapters: List<ConvergenceLayerAdapter>,
    private val log: (String, String) -> Unit,
    private val onBundleUpdated: suspend () -> Unit,
) {
    private val flushMutex = Mutex()

    private data class OutgoingBatchItem(
        val record: BundleRecord,
        val bundleBytes: ByteArray,
        val profile: ConvergenceProfile,
    )

    suspend fun flushQueue(forceProfileId: String? = null): Boolean =
        flushMutex.withLock {
            val outbox = bundleRecordDao.getByState(BundleState.OUTBOX)
            val transit = bundleRecordDao.getByState(BundleState.TRANSIT)
            val allOutgoing = outbox + transit

            if (allOutgoing.isEmpty()) {
                if (forceProfileId != null) {
                    return@withLock false
                }
                val profiles = convergenceProfileDao.getAllList()
                for (profile in profiles) {
                    if (profile.isPaused) continue
                    if (profile.triggerType == TriggerType.PERIODIC_INTERNET) {
                        val adapter = adapters.find { it.name.lowercase() == "tcpclv4" }
                        if (adapter != null) {
                            log("INFO", "Performing periodic pull connection to ${profile.targetAddress}")
                            adapter.sendBundle(ByteArray(0), profile.targetAddress)
                        }
                    }
                }
                return@withLock false
            }

            log("INFO", "Flushing queue, ${allOutgoing.size} outgoing bundle(s)")
            var anySuccess = false
            val maxSizeBytes = PreferencesHelper.getMaxBundleSizeBytes(context)
            val prefs = PreferencesHelper.getEncryptedSharedPreferences(context)
            val localNodeBase = (prefs.getString("local_node_name", "dtn://my-node") ?: "dtn://my-node").trim()
            val routingRules = routingRuleDao.getAllList()
            val allProfiles = convergenceProfileDao.getAllList()
            val allBpsecKeys = bpsecKeyDao.getAllList()

            val batchItems = mutableListOf<OutgoingBatchItem>()

            for (record in allOutgoing) {
                val current = bundleRecordDao.getById(record.bundleId)
                if (current == null || (current.state != BundleState.OUTBOX && current.state != BundleState.TRANSIT)) {
                    continue
                }

                val file = File(record.payloadFilePath)
                if (!file.exists()) {
                    log("WARN", "Payload file not found for bundle ${record.bundleId}, removing orphaned record from queue")
                    bundleRecordDao.delete(record)
                    continue
                }
                if (file.length() > maxSizeBytes) {
                    val maxMb = maxSizeBytes / (1024 * 1024)
                    log(
                        "WARN",
                        "Bundle ${record.bundleId} payload size (${file.length()} bytes) exceeds maximum configured limit of $maxMb MB. Skipping.",
                    )
                    continue
                }
                val payloadBytes = file.readBytes()

                val isExpired = (record.creationTimestamp + record.lifetimeMs) < timeProvider.currentTimeMillis()
                if (isExpired) {
                    log("INFO", "Bundle ${record.bundleId} expired. Deleting payload and record.")
                    PayloadFileHelper.safeDelete(record.payloadFilePath)
                    bundleRecordDao.delete(record)
                    continue
                }

                if (record.hopCount + 1 >= record.hopLimit) {
                    log("WARN", "Bundle ${record.bundleId} exceeded hop limit (${record.hopLimit}) during forwarding. Discarding.")
                    PayloadFileHelper.safeDelete(record.payloadFilePath)
                    bundleRecordDao.delete(record)
                    continue
                }

                var nextHop = RoutingPolicy.selectNextHop(record.destinationEid, routingRules)
                var useBibe = false
                var bibeGateway: String? = null
                if (nextHop.startsWith("bibe:")) {
                    useBibe = true
                    bibeGateway = nextHop.substring(5)
                    nextHop = bibeGateway
                }

                val profile =
                    RoutingPolicy.findProfileForNextHop(
                        context = context,
                        nextHop = nextHop,
                        profiles = allProfiles,
                        allowPausedProfileId = forceProfileId,
                    )
                if (profile == null) {
                    if (forceProfileId == null) {
                        log("WARN", "No convergence profile found to reach next-hop $nextHop (dest: ${record.destinationEid})")
                    }
                    continue
                }
                if (forceProfileId != null && profile.profileId != forceProfileId) {
                    continue
                }

                val keyRecord =
                    BpsecProcessor.selectBpsecKey(record.destinationEid, allBpsecKeys)
                        ?: BpsecProcessor.selectBpsecKey(nextHop, allBpsecKeys)
                        ?: BpsecProcessor.selectBpsecKey(record.sourceEid, allBpsecKeys)
                        ?: BpsecProcessor.selectBpsecKey(localNodeBase, allBpsecKeys)

                val primary =
                    PrimaryBlock(
                        destination = Eid(record.destinationEid),
                        source = Eid(record.sourceEid),
                        reportTo = Eid(record.sourceEid),
                        creationTimestamp = Pair(record.creationTimestamp - 946684800000L, record.sequenceNumber),
                        lifetimeMs = record.lifetimeMs,
                    )

                val payload = PayloadBlock(data = payloadBytes)
                val hopCount = HopCountBlock(hopLimit = record.hopLimit, hopCount = record.hopCount + 1)

                val bib =
                    keyRecord?.let { key ->
                        BpsecProcessor.signOutgoingBundle(
                            primary = primary,
                            payload = payload,
                            sourceEid = record.sourceEid,
                            keyRecord = key,
                            log = log,
                        )?.also {
                            log("INFO", "Signed bundle ${record.bundleId} using key for EID: ${key.nodeEid}")
                        }
                    }

                val bundle = Bundle(primary, payload, hopCount, bib)
                var bundleBytes = Bpv7Parser.serialize(bundle)

                if (useBibe && bibeGateway != null) {
                    log("INFO", "Encapsulating bundle ${record.bundleId} in a BIBE tunnel via gateway $bibeGateway")
                    val outerBundle =
                        BibeCodec.buildBibeOuterBundle(
                            innerBundleBytes = bundleBytes,
                            innerBundleId = record.bundleId,
                            bibeGateway = bibeGateway,
                            localNodeBase = localNodeBase,
                            creationTimeMillis = timeProvider.currentTimeMillis(),
                            sequenceNumber = outboxRepository.nextSequenceNumber(),
                            lifetimeMs = record.lifetimeMs,
                            hopCount = record.hopCount + 1,
                        )
                    bundleBytes = Bpv7Parser.serialize(outerBundle)
                }

                batchItems.add(OutgoingBatchItem(record, bundleBytes, profile))
            }

            val grouped =
                batchItems.groupBy {
                    Triple(
                        it.profile.triggerType,
                        it.profile.targetAddress,
                        if (it.profile.triggerType == TriggerType.FILE_CLA) it.profile.profileId else "",
                    )
                }

            for ((key, items) in grouped) {
                val (triggerType, targetAddress, _) = key
                if (triggerType == TriggerType.FILE_CLA) {
                    val profileName = items.first().profile.name
                    log("INFO", "Writing ${items.size} bundle(s) via FileCLA '$profileName' to TX")
                    val namedBundles =
                        items.map { item ->
                            FileClaAdapter.NamedBundle(
                                bundleId = item.record.bundleId,
                                bundleBytes = item.bundleBytes,
                                onAcknowledged = {
                                    val nextState = BundleState.DELIVERED
                                    bundleRecordDao.updateState(item.record.bundleId, nextState)
                                    log("INFO", "Bundle ${item.record.bundleId} written to FileCLA '$profileName' TX! State updated to $nextState")
                                    anySuccess = true
                                    onBundleUpdated()
                                },
                            )
                        }
                    if (fileClaAdapter.sendNamedBundles(targetAddress, profileName, namedBundles)) {
                        anySuccess = true
                    }
                    continue
                }

                val adapter =
                    adapters.find {
                        it.name.lowercase() == (if (triggerType == TriggerType.BLUETOOTH_ALWAYS) "bluetooth" else "tcpclv4").lowercase()
                    }
                if (adapter == null) {
                    log("ERROR", "No adapter found for profile type $triggerType")
                    continue
                }

                log("INFO", "Transmitting ${items.size} bundle(s) via ${adapter.name} to $targetAddress in single session")
                val bundlesPayload =
                    items.map { item ->
                        Pair<ByteArray, (suspend () -> Unit)?>(item.bundleBytes) {
                            val nextState = BundleState.DELIVERED
                            bundleRecordDao.updateState(item.record.bundleId, nextState)
                            log("INFO", "Bundle ${item.record.bundleId} acknowledged by peer! State updated immediately to $nextState")
                            anySuccess = true
                            onBundleUpdated()
                        }
                    }

                if (adapter.sendBundles(bundlesPayload, targetAddress)) {
                    anySuccess = true
                }
            }
            return@withLock anySuccess
        }
}
