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
    ],
    version = 4,
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

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_services ADD COLUMN isBroadcast INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
