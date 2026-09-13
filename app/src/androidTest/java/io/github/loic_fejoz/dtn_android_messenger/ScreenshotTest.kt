package io.github.loic_fejoz.dtn_android_messenger

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.github.loic_fejoz.dtn_android_messenger.data.dao.*
import io.github.loic_fejoz.dtn_android_messenger.data.model.*
import io.github.loic_fejoz.dtn_android_messenger.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.locale.LocaleTestRule
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    @get:Rule
    val localeTestRule = LocaleTestRule()

    private fun sleep(ms: Long = 1500) {
        try {
            Thread.sleep(ms)
        } catch (_: Exception) {}
    }

    @Before
    fun setUpDatabaseData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val localServiceDao: LocalServiceDao = koin.get()
        val bundleRecordDao: BundleRecordDao = koin.get()
        val convergenceProfileDao: ConvergenceProfileDao = koin.get()
        val routingRuleDao: RoutingRuleDao = koin.get()
        val senmlEntryDao: SenmlEntryDao = koin.get()

        runBlocking {
            localServiceDao.insert(
                LocalService(
                    serviceEid = "dtn://my-node/chat",
                    displayName = "Default Chat Service",
                    viewerType = ViewerType.CHAT,
                    defaultDestinationEid = "dtn://f4jxq-2/chat"
                )
            )
            localServiceDao.insert(
                LocalService(
                    serviceEid = "dtn://my-node/files",
                    displayName = "File Exchange",
                    viewerType = ViewerType.BUNDLE_LIST
                )
            )
            localServiceDao.insert(
                LocalService(
                    serviceEid = "dtn://my-node/sensor",
                    displayName = "SENML Sensor Monitor",
                    viewerType = ViewerType.SENML_LAST
                )
            )

            // 1. Markdown payload file
            val payloadFileMarkdown = File(context.filesDir, "payload_markdown.md").apply {
                writeText(
                    """
                    # DTN BPv7 Telemetry Report
                    
                    **Status**: *OPERATIONAL*
                    - Node EID: `dtn://my-node/chat`
                    - Transport: TCPCLv4 / Bluetooth Classic
                    - Security: BPSec BIB HMAC-SHA256
                    
                    > *Delay-tolerant store-and-forward bundle payload.*
                    """.trimIndent()
                )
            }

            // 2. Image payload file (icon.png from test assets)
            val testContext = InstrumentationRegistry.getInstrumentation().context
            val payloadFileImage = File(context.filesDir, "payload_image.png")
            testContext.assets.open("icon.png").use { input ->
                FileOutputStream(payloadFileImage).use { output ->
                    input.copyTo(output)
                }
            }

            // 3. Audio / Vocal payload file (MP3 ID3 magic header)
            val payloadFileVocal = File(context.filesDir, "payload_vocal.mp3")
            val mp3Bytes = byteArrayOf(
                0x49.toByte(), 0x44.toByte(), 0x33.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
            )
            FileOutputStream(payloadFileVocal).use { it.write(mp3Bytes) }

            // Insert Bundles (sorted DESC by creationTimestamp: Vocal=newest, Image=middle, Markdown=oldest)
            val bundleMarkdown = BundleRecord(
                bundleId = "bundle-md-001",
                destinationEid = "dtn://my-node/files",
                sourceEid = "dtn://f4jxq-2/files",
                creationTimestamp = System.currentTimeMillis() - 120000,
                sequenceNumber = 201,
                lifetimeMs = 3600000,
                payloadFilePath = payloadFileMarkdown.absolutePath,
                state = BundleState.RECEIVED,
                isRead = true,
                bpsecStatus = BpsecStatus.VALID,
                hopCount = 1
            )

            val bundleImage = BundleRecord(
                bundleId = "bundle-img-002",
                destinationEid = "dtn://my-node/files",
                sourceEid = "dtn://f4jxq-2/files",
                creationTimestamp = System.currentTimeMillis() - 90000,
                sequenceNumber = 202,
                lifetimeMs = 3600000,
                payloadFilePath = payloadFileImage.absolutePath,
                state = BundleState.RECEIVED,
                isRead = true,
                bpsecStatus = BpsecStatus.VALID,
                hopCount = 1
            )

            val bundleVocal = BundleRecord(
                bundleId = "bundle-voc-003",
                destinationEid = "dtn://my-node/files",
                sourceEid = "dtn://f4jxq-2/files",
                creationTimestamp = System.currentTimeMillis() - 60000,
                sequenceNumber = 203,
                lifetimeMs = 3600000,
                payloadFilePath = payloadFileVocal.absolutePath,
                state = BundleState.RECEIVED,
                isRead = true,
                bpsecStatus = BpsecStatus.VALID,
                hopCount = 1
            )

            bundleRecordDao.insert(bundleMarkdown)
            bundleRecordDao.insert(bundleImage)
            bundleRecordDao.insert(bundleVocal)

            // SenML Entries
            senmlEntryDao.insertOrUpdate(
                SenmlEntry(
                    serviceEid = "dtn://my-node/sensor",
                    name = "temperature",
                    customLabel = "Ambient Temperature",
                    value = "21.5",
                    unit = "Cel",
                    timestamp = System.currentTimeMillis()
                )
            )
            senmlEntryDao.insertOrUpdate(
                SenmlEntry(
                    serviceEid = "dtn://my-node/sensor",
                    name = "humidity",
                    customLabel = "Relative Humidity",
                    value = "45.0",
                    unit = "%RH",
                    timestamp = System.currentTimeMillis()
                )
            )
            senmlEntryDao.insertOrUpdate(
                SenmlEntry(
                    serviceEid = "dtn://my-node/sensor",
                    name = "voltage",
                    customLabel = "Battery Voltage",
                    value = "12.4",
                    unit = "V",
                    timestamp = System.currentTimeMillis()
                )
            )

            // Convergence Profiles
            convergenceProfileDao.insert(
                ConvergenceProfile(
                    profileId = "dtn://f4jxq-2",
                    name = "Hardy Instance f4jxq-2 (TCPCL)",
                    triggerType = TriggerType.PERIODIC_INTERNET,
                    targetAddress = "10.0.2.2:4556",
                    triggerCondition = "120"
                )
            )
            convergenceProfileDao.insert(
                ConvergenceProfile(
                    profileId = "dtn://node-bt",
                    name = "Bluetooth Node",
                    triggerType = TriggerType.BLUETOOTH_ALWAYS,
                    targetAddress = "00:11:22:33:44:55"
                )
            )

            // Routing Rules
            routingRuleDao.insert(
                RoutingRule(
                    destinationEidPattern = "dtn://f4jxq-2/*",
                    nextHopEid = "dtn://f4jxq-2"
                )
            )
        }
    }

    @Test
    fun captureScreenshots() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // Dismiss permissions dialog if present
        sleep(2000)
        val allowBtn = device.findObject(By.textContains("Allow"))
            ?: device.findObject(By.textContains("Autoriser"))
        allowBtn?.click()
        sleep(1000)

        // 1. Registry Screen (Main View)
        Screengrab.screenshot("screencap")

        // 2. Chat Service View
        var node = device.findObject(By.textContains("Default Chat Service"))
        if (node != null) {
            node.click()
            sleep(1500)
            Screengrab.screenshot("screencap_chat")
            val backBtn = device.findObject(By.descContains("Back"))
            backBtn?.click() ?: device.pressBack()
            sleep(1000)
        }

        // 3. SenML Last Service View
        node = device.findObject(By.textContains("SENML Sensor Monitor"))
        if (node != null) {
            node.click()
            sleep(1500)
            Screengrab.screenshot("screencap_senml_last")
            val backBtn = device.findObject(By.descContains("Back"))
            backBtn?.click() ?: device.pressBack()
            sleep(1000)
        }

        // 4. File Exchange Service View & Bundle Details (Markdown, Image, Vocal)
        node = device.findObject(By.textContains("File Exchange"))
        if (node != null) {
            node.click()
            sleep(1500)
            Screengrab.screenshot("screencap_files")

            // a. Vocal Bundle Detail (Newest: index 0)
            var cards = device.findObjects(By.textContains("From:"))
            if (cards.isNotEmpty()) {
                cards[0].click()
                sleep(1500)
                Screengrab.screenshot("screencap_bundle_detail_vocal")
                val backBtn = device.findObject(By.descContains("Back"))
                backBtn?.click()
                sleep(1500)
            }

            // b. Image Bundle Detail (Middle: index 1)
            cards = device.findObjects(By.textContains("From:"))
            if (cards.size >= 2) {
                cards[1].click()
                sleep(1500)
                Screengrab.screenshot("screencap_bundle_detail_image")
                val backBtn = device.findObject(By.descContains("Back"))
                backBtn?.click()
                sleep(1500)
            }

            // c. Markdown Bundle Detail (Oldest: index 2)
            cards = device.findObjects(By.textContains("From:"))
            if (cards.size >= 3) {
                cards[2].click()
                sleep(1500)
                Screengrab.screenshot("screencap_bundle_detail_markdown")
                Screengrab.screenshot("screencap_bundle_detail")
                val backBtn = device.findObject(By.descContains("Back"))
                backBtn?.click()
                sleep(1500)
            }

            val backBtn = device.findObject(By.descContains("Back"))
            backBtn?.click() ?: device.pressBack()
            sleep(1000)
        }

        // 5. Opportunistic Sender (Text & Image View)
        val fab = device.findObject(By.descContains("Send bundle"))
        if (fab != null) {
            fab.click()
            sleep(1500)
            Screengrab.screenshot("screencap_opportunistic_send")

            val fileImageBtn = device.findObject(By.textContains("FILE / IMAGE"))
            if (fileImageBtn != null) {
                fileImageBtn.click()
                sleep(1000)
            }
            Screengrab.screenshot("screencap_opportunistic_send_image")
            val backBtn = device.findObject(By.descContains("Back"))
            backBtn?.click() ?: device.pressBack()
            sleep(1000)
        }

        // 6. Settings Screen (Convergence Layers, Routing, Local Services)
        val settingsBtn = device.findObject(By.descContains("Settings"))
        if (settingsBtn != null) {
            settingsBtn.click()
            sleep(1500)

            val convTab = device.findObject(By.textContains("CLA Profiles"))
            convTab?.click()
            sleep(1000)
            Screengrab.screenshot("screencap_convergence_layers")

            val routingTab = device.findObject(By.textContains("Routing"))
            routingTab?.click()
            sleep(1000)
            Screengrab.screenshot("screencap_routing")

            val servicesTab = device.findObject(By.textContains("Services"))
            servicesTab?.click()
            sleep(1000)
            Screengrab.screenshot("screencap_local_services")

            val backBtn = device.findObject(By.descContains("Back"))
            backBtn?.click() ?: device.pressBack()
            sleep(1000)
        }

        scenario.close()
    }
}
