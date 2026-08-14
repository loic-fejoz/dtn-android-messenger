package com.dtn.messenger.di

import androidx.room.Room
import com.dtn.messenger.data.db.AppDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule =
    module {
        single {
            Room.databaseBuilder(
                androidContext(),
                AppDatabase::class.java,
                "dtn_messenger_database",
            )
                .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
                .build()
        }

        single { get<AppDatabase>().localServiceDao() }
        single { get<AppDatabase>().bundleRecordDao() }
        single { get<AppDatabase>().routingRuleDao() }
        single { get<AppDatabase>().convergenceProfileDao() }
        single { get<AppDatabase>().bpsecKeyDao() }
        single { get<AppDatabase>().systemLogDao() }
        single { get<AppDatabase>().senmlEntryDao() }
    }
