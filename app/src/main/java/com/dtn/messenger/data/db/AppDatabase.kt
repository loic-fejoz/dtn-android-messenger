package com.dtn.messenger.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dtn.messenger.data.dao.*
import com.dtn.messenger.data.model.*

class Converters {
    @TypeConverter
    fun toViewerType(value: String) = ViewerType.valueOf(value)

    @TypeConverter
    fun fromViewerType(value: ViewerType) = value.name

    @TypeConverter
    fun toBundleState(value: String) = BundleState.valueOf(value)

    @TypeConverter
    fun fromBundleState(value: BundleState) = value.name

    @TypeConverter
    fun toBpsecStatus(value: String) = BpsecStatus.valueOf(value)

    @TypeConverter
    fun fromBpsecStatus(value: BpsecStatus) = value.name

    @TypeConverter
    fun toTriggerType(value: String) = TriggerType.valueOf(value)

    @TypeConverter
    fun fromTriggerType(value: TriggerType) = value.name
}

@Database(
    entities = [
        LocalService::class,
        BundleRecord::class,
        RoutingRule::class,
        ConvergenceProfile::class,
        BpsecKey::class,
        SystemLog::class,
        SenmlEntry::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localServiceDao(): LocalServiceDao

    abstract fun bundleRecordDao(): BundleRecordDao

    abstract fun routingRuleDao(): RoutingRuleDao

    abstract fun convergenceProfileDao(): ConvergenceProfileDao

    abstract fun bpsecKeyDao(): BpsecKeyDao

    abstract fun systemLogDao(): SystemLogDao

    abstract fun senmlEntryDao(): SenmlEntryDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_services ADD COLUMN isBroadcast INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `senml_entries` (
                        `serviceEid` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `customLabel` TEXT,
                        `value` TEXT NOT NULL,
                        `unit` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `displayOrder` INTEGER NOT NULL DEFAULT 0,
                        `isDeleted` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`serviceEid`, `name`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_services ADD COLUMN isNotificationEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
