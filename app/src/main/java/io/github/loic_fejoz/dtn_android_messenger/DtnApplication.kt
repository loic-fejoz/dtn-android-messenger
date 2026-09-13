package io.github.loic_fejoz.dtn_android_messenger

import android.app.Application
import io.github.loic_fejoz.dtn_android_messenger.data.dao.ConvergenceProfileDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.LocalServiceDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.ConvergenceProfile
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import io.github.loic_fejoz.dtn_android_messenger.data.model.TriggerType
import io.github.loic_fejoz.dtn_android_messenger.data.model.ViewerType
import io.github.loic_fejoz.dtn_android_messenger.di.appModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class DtnApplication : Application() {
    private val localServiceDao: LocalServiceDao by inject()
    private val convergenceProfileDao: ConvergenceProfileDao by inject()

    override fun onCreate() {
        super.onCreate()

        // Start Koin dependency injection
        startKoin {
            androidContext(this@DtnApplication)
            modules(appModule)
        }

        // Prepopulate default database values if empty
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val services = localServiceDao.getAll().first()
                if (services.isEmpty()) {
                    localServiceDao.insert(
                        LocalService(
                            serviceEid = "dtn://my-node/chat",
                            displayName = "Node Chat (EID dtn://my-node/chat)",
                            viewerType = ViewerType.CHAT,
                        ),
                    )
                    localServiceDao.insert(
                        LocalService(
                            serviceEid = "dtn://my-node/files",
                            displayName = "Bundle File Exchange (EID dtn://my-node/files)",
                            viewerType = ViewerType.BUNDLE_LIST,
                        ),
                    )
                }

                val profiles = convergenceProfileDao.getAllList()
                if (profiles.isEmpty()) {
                    convergenceProfileDao.insert(
                        ConvergenceProfile(
                            profileId = "dtn://f4jxq",
                            name = "Hardy Instance f4jxq (TCPCL)",
                            triggerType = TriggerType.PERIODIC_INTERNET,
                            targetAddress = "44.27.131.233:4556",
                            // Check every 120 minutes (2 hours)
                            triggerCondition = "120",
                        ),
                    )
                    convergenceProfileDao.insert(
                        ConvergenceProfile(
                            profileId = "dtn://node-bt",
                            name = "Bluetooth Node",
                            triggerType = TriggerType.BLUETOOTH_ALWAYS,
                            targetAddress = "00:11:22:33:44:55",
                        ),
                    )
                }
            } catch (e: Exception) {
                // Database pre-population failed, log it or handle gracefully
            }
        }
    }
}
