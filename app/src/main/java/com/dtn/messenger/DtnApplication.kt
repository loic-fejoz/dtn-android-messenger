package com.dtn.messenger

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dtn.messenger.data.dao.ConvergenceProfileDao
import com.dtn.messenger.data.dao.LocalServiceDao
import com.dtn.messenger.data.model.ConvergenceProfile
import com.dtn.messenger.data.model.LocalService
import com.dtn.messenger.data.model.TriggerType
import com.dtn.messenger.data.model.ViewerType
import com.dtn.messenger.di.appModule
import com.dtn.messenger.service.DtnEngineService
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
                            viewerType = ViewerType.CHAT
                        )
                    )
                    localServiceDao.insert(
                        LocalService(
                            serviceEid = "dtn://my-node/files",
                            displayName = "Bundle File Exchange (EID dtn://my-node/files)",
                            viewerType = ViewerType.BUNDLE_LIST
                        )
                    )
                }

                val profiles = convergenceProfileDao.getAllList()
                if (profiles.isEmpty()) {
                    convergenceProfileDao.insert(
                        ConvergenceProfile(
                            profileId = "dtn://f4jxq-2",
                            name = "Hardy Instance f4jxq-2 (TCPCL)",
                            triggerType = TriggerType.PERIODIC_INTERNET,
                            targetAddress = "10.0.2.2:4556", // 10.0.2.2 points to the host loopback from the emulator
                            triggerCondition = "1" // Check every 1 minute
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
                }
            } catch (e: Exception) {
                // Database pre-population failed, log it or handle gracefully
            }
        }

        // Launch Foreground DTN Engine service
        val serviceIntent = Intent(this, DtnEngineService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}
