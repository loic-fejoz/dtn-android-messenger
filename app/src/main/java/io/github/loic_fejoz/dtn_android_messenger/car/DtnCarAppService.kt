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
import io.github.loic_fejoz.dtn_android_messenger.R
import io.github.loic_fejoz.dtn_android_messenger.data.dao.BundleRecordDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.nio.charset.StandardCharsets

class DtnCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.dtn_car_hosts_allowlist)
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
    private var lastPayloadText: String = ""
    private var isLoading = true
    private var dbJob: Job? = null

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    dbJob =
                        lifecycleScope.launch {
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
                                                        carContext.getString(R.string.car_empty_content)
                                                    }
                                                } else {
                                                    carContext.getString(R.string.car_binary_content)
                                                }
                                            } catch (e: Exception) {
                                                carContext.getString(R.string.car_binary_content)
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
                .setTitle(carContext.getString(R.string.car_last_message_title))
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        val bundle = lastBundle
        val paneBuilder = Pane.Builder()

        if (bundle == null) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_no_message_title))
                    .addText(carContext.getString(R.string.car_no_message_subtitle))
                    .build(),
            )
        } else {
            val payloadText = lastPayloadText

            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_from_prefix, bundle.sourceEid))
                    .addText(payloadText)
                    .build(),
            )

            // Option 1 : Reply action opening the canned responses
            val replyAction =
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_reply))
                    .setOnClickListener {
                        screenManager.push(ReplyCarScreen(carContext, bundle))
                    }
                    .build()

            paneBuilder.addAction(replyAction)
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(carContext.getString(R.string.car_last_message_title))
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
        val quickReplies =
            listOf(
                carContext.getString(R.string.car_quick_reply_yes),
                carContext.getString(R.string.car_quick_reply_no),
                carContext.getString(R.string.car_quick_reply_arriving),
                carContext.getString(R.string.car_quick_reply_enroute),
                carContext.getString(R.string.car_quick_reply_call_me),
            )
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
            .setTitle(carContext.getString(R.string.car_reply_to_prefix, replyToBundle.sourceEid.substringAfterLast("/")))
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
