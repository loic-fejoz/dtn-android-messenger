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

        // Check for existing installation to ensure seamless upgrades without blocking users
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val services = localServiceDao.getAllList()
                if (services.isNotEmpty() && !io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.isOnboardingCompleted(this@DtnApplication)) {
                    io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper.setOnboardingCompleted(this@DtnApplication, true)
                }
            } catch (e: Exception) {
                // Ignore initialization errors
            }
        }
    }
}
