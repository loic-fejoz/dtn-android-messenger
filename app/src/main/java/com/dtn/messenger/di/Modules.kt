package com.dtn.messenger.di

import androidx.room.Room
import com.dtn.messenger.data.db.AppDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "dtn_messenger_database"
        )
        // .fallbackToDestructiveMigration() // Disabled for production safety. Provide explicit Migration paths instead.
        .build()
    }

    single { get<AppDatabase>().localServiceDao() }
    single { get<AppDatabase>().bundleRecordDao() }
    single { get<AppDatabase>().routingRuleDao() }
    single { get<AppDatabase>().convergenceProfileDao() }
    single { get<AppDatabase>().bpsecKeyDao() }
    single { get<AppDatabase>().systemLogDao() }
}
