package io.github.loic_fejoz.dtn_android_messenger

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.loic_fejoz.dtn_android_messenger.cla.FileClaAdapter
import io.github.loic_fejoz.dtn_android_messenger.data.dao.BundleRecordDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.ConvergenceProfileDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.LocalServiceDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.RoutingRuleDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleState
import io.github.loic_fejoz.dtn_android_messenger.data.model.ConvergenceProfile
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import io.github.loic_fejoz.dtn_android_messenger.data.model.RoutingRule
import io.github.loic_fejoz.dtn_android_messenger.data.model.TriggerType
import io.github.loic_fejoz.dtn_android_messenger.data.model.ViewerType
import io.github.loic_fejoz.dtn_android_messenger.data.repository.BundleOutboxRepository
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bpv7Parser
import io.github.loic_fejoz.dtn_android_messenger.protocol.Bundle
import io.github.loic_fejoz.dtn_android_messenger.protocol.Eid
import io.github.loic_fejoz.dtn_android_messenger.protocol.HopCountBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PayloadBlock
import io.github.loic_fejoz.dtn_android_messenger.protocol.PrimaryBlock
import io.github.loic_fejoz.dtn_android_messenger.service.DtnEngineService
import io.github.loic_fejoz.dtn_android_messenger.ui.MainActivity
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class FileClaEndToEndTest {
    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

    @Test
    fun testFileClaRoundTrip_RxAndTx() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)

        // Prepare external SAF target directory via shell
        device.executeShellCommand("rm -rf /sdcard/Documents/DtnExchange/Foo")
        device.executeShellCommand("mkdir -p /sdcard/Documents/DtnExchange")

        val koin = GlobalContext.get()
        val localServiceDao: LocalServiceDao = koin.get()
        val bundleRecordDao: BundleRecordDao = koin.get()
        val convergenceProfileDao: ConvergenceProfileDao = koin.get()
        val routingRuleDao: RoutingRuleDao = koin.get()
        val outboxRepository: BundleOutboxRepository = koin.get()

        val localNodeEid = "dtn://f4test"
        val localChatEid = "$localNodeEid/chat"
        val peerProfileId = "dtn://usb-peer"
        val peerChatEid = "$peerProfileId/chat"

        runBlocking {
            val prefs = PreferencesHelper.getEncryptedSharedPreferences(context)
            prefs.edit().putString("local_node_name", localNodeEid).apply()
            PreferencesHelper.setOnboardingCompleted(context, true)

            localServiceDao.insert(
                LocalService(
                    serviceEid = localChatEid,
                    displayName = "E2E Chat",
                    viewerType = ViewerType.CHAT,
                    defaultDestinationEid = peerChatEid,
                ),
            )
            for (profile in convergenceProfileDao.getAllList()) {
                if (profile.profileId == peerProfileId) {
                    convergenceProfileDao.delete(profile)
                }
            }
            for (state in BundleState.values()) {
                for (record in bundleRecordDao.getByState(state)) {
                    if (record.sourceEid == peerChatEid || record.destinationEid == peerChatEid) {
                        bundleRecordDao.delete(record)
                    }
                }
            }
            routingRuleDao.insert(
                RoutingRule(
                    destinationEidPattern = "$peerProfileId/*",
                    nextHopEid = peerProfileId,
                ),
            )
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // 1. Navigate to Settings -> CLA Profiles -> Select FILE_CLA -> Pick SAF base folder
            val settingsBtn = device.wait(Until.findObject(By.desc("Settings")), 10_000)
            assertNotNull("Settings icon should be visible", settingsBtn)
            settingsBtn.click()

            val selectTypeBtn = device.wait(Until.findObject(By.desc("Select type")), 5_000)
            assertNotNull("Select type dropdown should be visible", selectTypeBtn)
            selectTypeBtn.click()

            val fileClaItem = device.wait(Until.findObject(By.text("FILE_CLA")), 5_000)
            assertNotNull("FILE_CLA option should be visible", fileClaItem)
            fileClaItem.click()

            val pickFolderBtn =
                device.wait(
                    Until.findObject(By.text(Pattern.compile("PICK BASE FOLDER", Pattern.CASE_INSENSITIVE))),
                    5_000,
                )
            assertNotNull("PICK BASE FOLDER button should be visible", pickFolderBtn)
            pickFolderBtn.click()

            // Drive Android SAF DocumentsUI: Documents -> DtnExchange -> USE THIS FOLDER -> ALLOW
            selectSafFolder(device, "Documents", "DtnExchange")

            device.wait(Until.hasObject(By.pkg(context.packageName)), 5_000)
            val selectedLabel = device.wait(Until.findObject(By.textContains("DtnExchange")), 5_000)
            assertNotNull("Selected base folder DtnExchange should be shown in UI", selectedLabel)

            // 2. Verify persistable SAF URI grant & create Foo/RX + Foo/TX just as SAVE PROFILE does
            val grantedUri =
                waitFor(5_000) {
                    context.contentResolver.persistedUriPermissions
                        .firstOrNull {
                            it.isReadPermission && it.isWritePermission && it.uri.toString().contains("DtnExchange")
                        }?.uri
                        ?.toString()
                }
            assertNotNull("Persistable SAF URI permission for DtnExchange should be granted", grantedUri)

            assertTrue(
                "Foo/RX and Foo/TX should be created via SAF",
                FileClaAdapter.ensureClaFolders(context, grantedUri!!, "Foo"),
            )
            val profile =
                ConvergenceProfile(
                    profileId = peerProfileId,
                    name = "Foo",
                    triggerType = TriggerType.FILE_CLA,
                    targetAddress = grantedUri,
                    triggerCondition = "300",
                    isPaused = false,
                )
            runBlocking { convergenceProfileDao.insert(profile) }

            val rxDirCheck = device.executeShellCommand("ls -ld /sdcard/Documents/DtnExchange/Foo/RX")
            val txDirCheck = device.executeShellCommand("ls -ld /sdcard/Documents/DtnExchange/Foo/TX")
            assertTrue("Foo/RX should exist: $rxDirCheck", rxDirCheck.contains("Foo/RX"))
            assertTrue("Foo/TX should exist: $txDirCheck", txDirCheck.contains("Foo/TX"))

            // 3. Inbound (RX): Drop a valid BPv7 bundle into Foo/RX and trigger SYNC_FILE_CLA
            val inboundText = "Hello from USB File-CLA!"
            val inboundBundle =
                Bundle(
                    primaryBlock =
                        PrimaryBlock(
                            destination = Eid(localChatEid),
                            source = Eid(peerChatEid),
                            reportTo = Eid(peerChatEid),
                            creationTimestamp = Pair(System.currentTimeMillis() - 946684800000L, 42L),
                            lifetimeMs = 3600_000L,
                        ),
                    payloadBlock = PayloadBlock(data = inboundText.toByteArray(Charsets.UTF_8)),
                    hopCountBlock = HopCountBlock(hopLimit = 64, hopCount = 0),
                    bibBlock = null,
                )
            val inboundBytes = Bpv7Parser.serialize(inboundBundle)
            val baseSafDoc = DocumentFile.fromTreeUri(context, Uri.parse(grantedUri))
            assertNotNull("Base SAF DocumentFile should be accessible", baseSafDoc)
            val rxSafDoc = baseSafDoc!!.findFile("Foo")?.findFile("RX")
            assertNotNull("Foo/RX SAF DocumentFile should exist", rxSafDoc)
            val rxFileDoc = rxSafDoc!!.createFile(FileClaAdapter.BUNDLE_MIME_TYPE, "rx_e2e.bundle")
            assertNotNull("Should create rx_e2e.bundle in Foo/RX", rxFileDoc)
            context.contentResolver.openOutputStream(rxFileDoc!!.uri, "wt")!!.use { out ->
                out.write(inboundBytes)
                out.flush()
            }
            val rxCreated = device.executeShellCommand("ls -la /sdcard/Documents/DtnExchange/Foo/RX/rx_e2e.bundle")
            assertTrue("rx_e2e.bundle should exist in Foo/RX before sync: $rxCreated", rxCreated.contains("rx_e2e.bundle"))

            val syncIntent =
                Intent(context, DtnEngineService::class.java).apply {
                    action = "SYNC_FILE_CLA"
                    putExtra("profile_id", peerProfileId)
                }
            ContextCompat.startForegroundService(context, syncIntent)

            val receivedRecord =
                waitFor(12_000) {
                    runBlocking {
                        bundleRecordDao.getByState(BundleState.RECEIVED).find {
                            it.sourceEid == peerChatEid
                        }
                    }
                }
            assertNotNull("Inbound bundle should be recorded as RECEIVED", receivedRecord)
            val receivedPayload = File(receivedRecord!!.payloadFilePath)
            assertTrue("Received payload file should exist", receivedPayload.exists())
            assertEquals(inboundText, receivedPayload.readText(Charsets.UTF_8))

            // Verify RX file deleted after Responsibility Transfer
            val rxAfter = device.executeShellCommand("ls -A /sdcard/Documents/DtnExchange/Foo/RX")
            assertFalse("rx_e2e.bundle should be deleted from RX after ingestion: $rxAfter", rxAfter.contains("rx_e2e.bundle"))

            // 4. Outbound (TX): Enqueue outgoing bundle and flush to Foo/TX
            val outboundText = "Hello back from Pixel 7!"
            val outRecord =
                runBlocking {
                    outboxRepository.enqueueTextBundle(
                        sourceEid = localChatEid,
                        destinationEid = peerChatEid,
                        text = outboundText,
                    )
                }
            outboxRepository.triggerQueueFlush()

            val deliveredRecord =
                waitFor(12_000) {
                    runBlocking {
                        bundleRecordDao.getById(outRecord.bundleId)?.takeIf { it.state == BundleState.DELIVERED }
                    }
                }
            assertNotNull("Outgoing bundle should transition to DELIVERED", deliveredRecord)

            val txListing = device.executeShellCommand("ls -A /sdcard/Documents/DtnExchange/Foo/TX")
            val expectedTxFile = "${outRecord.bundleId}.bundle"
            assertTrue("TX folder should contain $expectedTxFile, got: $txListing", txListing.contains(expectedTxFile))
            assertFalse("TX folder should not contain leftover .tmp files: $txListing", txListing.contains(".tmp"))

            val txDoc = baseSafDoc.findFile("Foo")?.findFile("TX")?.findFile(expectedTxFile)
            assertNotNull("SAF TX file should be readable", txDoc)
            val txBytes = context.contentResolver.openInputStream(txDoc!!.uri)!!.use { it.readBytes() }
            val decoded = Bpv7Parser.deserialize(txBytes)
            assertEquals(peerChatEid, decoded.primaryBlock.destination.uri)
            assertEquals(localChatEid, decoded.primaryBlock.source.uri)
            assertEquals(outboundText, String(decoded.payloadBlock.data, Charsets.UTF_8))

            // 5. Paused File-CLA Manual Sync: remains OUTBOX on normal flush, delivers on SYNC_FILE_CLA
            runBlocking { convergenceProfileDao.insert(profile.copy(isPaused = true)) }
            Thread.sleep(300)

            val pausedText = "Manual sync while paused!"
            val pausedRecord =
                runBlocking {
                    outboxRepository.enqueueTextBundle(
                        sourceEid = localChatEid,
                        destinationEid = peerChatEid,
                        text = pausedText,
                    )
                }
            outboxRepository.triggerQueueFlush()
            Thread.sleep(1500)
            val stillOutbox = runBlocking { bundleRecordDao.getById(pausedRecord.bundleId) }
            assertEquals("Paused File-CLA must not flush automatically", BundleState.OUTBOX, stillOutbox?.state)

            ContextCompat.startForegroundService(context, syncIntent)
            val manuallyDelivered =
                waitFor(12_000) {
                    runBlocking {
                        bundleRecordDao.getById(pausedRecord.bundleId)?.takeIf { it.state == BundleState.DELIVERED }
                    }
                }
            assertNotNull("Manual SYNC_FILE_CLA should flush paused File-CLA bundle to DELIVERED", manuallyDelivered)

            val pausedTxFile = "${pausedRecord.bundleId}.bundle"
            val pausedTxDoc = baseSafDoc.findFile("Foo")?.findFile("TX")?.findFile(pausedTxFile)
            assertNotNull("Paused manual sync TX file should exist", pausedTxDoc)
            val pausedDecoded =
                Bpv7Parser.deserialize(
                    context.contentResolver.openInputStream(pausedTxDoc!!.uri)!!.use { it.readBytes() },
                )
            assertEquals(pausedText, String(pausedDecoded.payloadBlock.data, Charsets.UTF_8))

            // Restore active state for live host-phone verification
            runBlocking { convergenceProfileDao.insert(profile.copy(isPaused = false)) }
        } finally {
            scenario.close()
            runBlocking {
                convergenceProfileDao.getAllList()
                    .filter { it.profileId == peerProfileId }
                    .forEach { convergenceProfileDao.delete(it) }
                for (state in BundleState.values()) {
                    for (record in bundleRecordDao.getByState(state)) {
                        if (record.sourceEid == peerChatEid || record.destinationEid == peerChatEid) {
                            File(record.payloadFilePath).delete()
                            bundleRecordDao.delete(record)
                        }
                    }
                }
            }
            device.executeShellCommand("rm -rf /sdcard/Documents/DtnExchange/Foo")
        }
    }

    private fun selectSafFolder(
        device: UiDevice,
        parentFolder: String,
        targetFolder: String,
    ) {
        var docsItem = device.wait(Until.findObject(By.text(parentFolder)), 3_000)
        if (docsItem == null) {
            val showRoots =
                device.findObject(By.desc(Pattern.compile("Show roots|Open navigation drawer", Pattern.CASE_INSENSITIVE)))
            showRoots?.click()
            docsItem = device.wait(Until.findObject(By.text(parentFolder)), 3_000)
        }
        docsItem?.click()

        val targetItem = device.wait(Until.findObject(By.text(targetFolder)), 5_000)
        assertNotNull("SAF folder '$targetFolder' should be visible", targetItem)
        targetItem.click()

        val useThisFolder =
            device.wait(
                Until.findObject(By.text(Pattern.compile("USE THIS FOLDER", Pattern.CASE_INSENSITIVE))),
                5_000,
            )
        assertNotNull("'USE THIS FOLDER' button should be visible", useThisFolder)
        useThisFolder.click()

        val allowBtn =
            device.wait(
                Until.findObject(By.text(Pattern.compile("ALLOW", Pattern.CASE_INSENSITIVE))),
                5_000,
            )
        allowBtn?.click()
    }

    private fun <T> waitFor(
        timeoutMs: Long,
        block: () -> T?,
    ): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val value = block()
            if (value != null) return value
            Thread.sleep(250)
        }
        return block()
    }
}
