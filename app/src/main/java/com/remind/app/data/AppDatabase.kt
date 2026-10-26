package com.remind.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ReminderEntity::class, RenewalEntity::class, PlanItemEntity::class,
        PlanCheckEntity::class, WaterLogEntity::class, MemoryEntity::class, SettingEntity::class, PhotoUploadEntity::class],
    version = 3, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminders(): ReminderDao
    abstract fun renewals(): RenewalDao
    abstract fun plan(): PlanDao
    abstract fun water(): WaterDao
    abstract fun memories(): MemoryDao
    abstract fun settings(): SettingsDao
    abstract fun photoUploads(): PhotoUploadDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN amount REAL")
                db.execSQL("ALTER TABLE reminders ADD COLUMN contactName TEXT")
                db.execSQL("ALTER TABLE reminders ADD COLUMN contactUri TEXT")
                db.execSQL("ALTER TABLE reminders ADD COLUMN snoozedUntilMs INTEGER")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN googleMediaId TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS photo_uploads (memoryId INTEGER NOT NULL PRIMARY KEY, mediaId TEXT NOT NULL, uploadedAt INTEGER NOT NULL)")
            }
        }
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "remind.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }
    }
}
