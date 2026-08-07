package com.dtn.messenger.car

import android.content.Context
import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.*
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dtn.messenger.data.dao.BundleRecordDao
import com.dtn.messenger.data.model.BundleRecord
import com.dtn.messenger.service.DtnEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

class DtnCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return DtnCarSession()
    }
}

class DtnCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return DtnCarScreen(carContext)
    }
}

class DtnCarScreen(carContext: CarContext) : Screen(carContext), KoinComponent {
    private val bundleRecordDao: BundleRecordDao by inject()
    private var lastBundle: BundleRecord? = null
    private var isLoading = true
    private var dbJob: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                dbJob = CoroutineScope(Dispatchers.Main).launch {
                    bundleRecordDao.getAll().collectLatest { list ->
                        // Get only the single most recently received bundle
                        lastBundle = list.firstOrNull { it.state.name == "RECEIVED" }
                        isLoading = false
                        invalidate()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                dbJob?.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        if (isLoading) {
            return PaneTemplate.Builder(
                Pane.Builder()
                    .setLoading(true)
                    .build()
            )
            .setTitle("Dernier Message DTN")
            .setHeaderAction(Action.APP_ICON)
            .build()
        }

        val bundle = lastBundle
        val paneBuilder = Pane.Builder()

        if (bundle == null) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Aucun message reçu")
                    .addText("Les messages entrants s'afficheront ici en temps réel.")
                    .build()
            )
        } else {
            val payloadText = try {
                val file = File(bundle.payloadFilePath)
                if (file.exists()) {
                    String(file.readBytes(), StandardCharsets.UTF_8).take(120)
                } else {
                    "Contenu Binaire"
                }
            } catch (e: Exception) {
                "Contenu Binaire"
            }

            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("De : ${bundle.sourceEid}")
                    .addText(payloadText)
                    .build()
            )

            // Option 1 : Reply action opening the canned responses
            val replyAction = Action.Builder()
                .setTitle("Répondre")
                .setOnClickListener {
                    screenManager.push(ReplyCarScreen(carContext, bundle))
                }
                .build()

            paneBuilder.addAction(replyAction)
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("Dernier Message DTN")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}

class ReplyCarScreen(
    carContext: CarContext,
    private val replyToBundle: BundleRecord
) : Screen(carContext), KoinComponent {

    private val bundleRecordDao: BundleRecordDao by inject()

    override fun onGetTemplate(): Template {
        val quickReplies = listOf("Oui", "Non", "J'arrive", "En route", "Appelle-moi")
        val listBuilder = ItemList.Builder()

        quickReplies.forEach { text ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(text)
                    .setOnClickListener {
                        sendReply(text)
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Répondre à ${replyToBundle.sourceEid.substringAfterLast("/")}")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun sendReply(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val context = carContext
            val payloadsDir = File(context.filesDir, "payloads")
            if (!payloadsDir.exists()) payloadsDir.mkdirs()

            val bundleId = UUID.randomUUID().toString()
            val payloadFile = File(payloadsDir, "$bundleId.bin")
            FileOutputStream(payloadFile).use { fos ->
                fos.write(text.toByteArray(StandardCharsets.UTF_8))
            }

            val record = BundleRecord(
                bundleId = bundleId,
                destinationEid = replyToBundle.sourceEid,
                sourceEid = replyToBundle.destinationEid,
                creationTimestamp = System.currentTimeMillis(),
                sequenceNumber = System.currentTimeMillis() % 100000,
                lifetimeMs = 3600000L,
                payloadFilePath = payloadFile.absolutePath,
                state = com.dtn.messenger.data.model.BundleState.OUTBOX,
                isRead = true,
                bpsecStatus = com.dtn.messenger.data.model.BpsecStatus.UNVERIFIED,
                hopCount = 0
            )
            bundleRecordDao.insert(record)

            // Trigger engine service to flush the queue
            context.startService(Intent(context, DtnEngineService::class.java).apply {
                action = "FLUSH_QUEUE"
            })
            
            // Pop back to the last message screen
            CoroutineScope(Dispatchers.Main).launch {
                screenManager.pop()
            }
        }
    }
}
