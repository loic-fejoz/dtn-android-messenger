package io.github.loic_fejoz.dtn_android_messenger.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.*
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.loic_fejoz.dtn_android_messenger.BuildConfig
import io.github.loic_fejoz.dtn_android_messenger.data.dao.BundleRecordDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleRecord
import io.github.loic_fejoz.dtn_android_messenger.service.DtnEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

class DtnCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
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
    private var lastPayloadText: String = "Contenu Binaire"
    private var isLoading = true
    private var dbJob: Job? = null

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    dbJob =
                        CoroutineScope(Dispatchers.Main).launch {
                            bundleRecordDao.getLatestReceived().collectLatest { bundle ->
                                lastBundle = bundle
                                lastPayloadText =
                                    if (bundle == null) {
                                        ""
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            try {
                                                val file = File(bundle.payloadFilePath)
                                                if (file.exists()) {
                                                    val header = ByteArray(512)
                                                    val bytesRead = file.inputStream().use { it.read(header) }
                                                    if (bytesRead > 0) {
                                                        String(header, 0, bytesRead, StandardCharsets.UTF_8).take(120)
                                                    } else {
                                                        "Contenu Vide"
                                                    }
                                                } else {
                                                    "Contenu Binaire"
                                                }
                                            } catch (e: Exception) {
                                                "Contenu Binaire"
                                            }
                                        }
                                    }
                                isLoading = false
                                invalidate()
                            }
                        }
                }

                override fun onStop(owner: LifecycleOwner) {
                    dbJob?.cancel()
                }
            },
        )
    }

    override fun onGetTemplate(): Template {
        if (isLoading) {
            return PaneTemplate.Builder(
                Pane.Builder()
                    .setLoading(true)
                    .build(),
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
                    .build(),
            )
        } else {
            val payloadText = lastPayloadText

            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("De : ${bundle.sourceEid}")
                    .addText(payloadText)
                    .build(),
            )

            // Option 1 : Reply action opening the canned responses
            val replyAction =
                Action.Builder()
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
    private val replyToBundle: BundleRecord,
) : Screen(carContext), KoinComponent {
    private val outboxRepository: io.github.loic_fejoz.dtn_android_messenger.data.repository.BundleOutboxRepository by inject()

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
                    .build(),
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Répondre à ${replyToBundle.sourceEid.substringAfterLast("/")}")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun sendReply(text: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                outboxRepository.enqueueTextBundle(
                    sourceEid = replyToBundle.destinationEid,
                    destinationEid = replyToBundle.sourceEid,
                    text = text,
                )
                outboxRepository.triggerQueueFlush()
            }

            // Pop back to the last message screen on main thread
            screenManager.pop()
        }
    }
}
