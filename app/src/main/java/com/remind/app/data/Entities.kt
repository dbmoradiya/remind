package com.remind.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ReminderType { REPEAT, DATE, EXPIRY }
enum class RepeatUnit { DAY, WEEK, MONTH, YEAR }

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,
    val type: ReminderType,
    // REPEAT
    val repeatUnit: RepeatUnit? = null,
    val repeatInterval: Int = 1,
    // DATE (specific date)
    val dateEpochDay: Long? = null,
    // EXPIRY
    val anchorEpochDay: Long? = null,      // purchase/start date
    val validityMonths: Int? = null,       // 6 / 12
    val validityDays: Int? = null,         // custom
    val leadDays: Int = 7,                 // days before expiry to start daily reminders
    // common
    val timeMinutes: Int? = null,          // notification time, minutes since midnight
    val alarm: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    // v2 additions
    val amount: Double? = null,          // bill amount tracking (accepted addition)
    val contactName: String? = null,     // linked contact (birthday/anniversary)
    val contactUri: String? = null,
    val snoozedUntilMs: Long? = null     // pending snooze fire time
)

@Entity(tableName = "renewals")
data class RenewalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val renewedAtEpochDay: Long,
    val periodMonths: Int? = null,
    val periodDays: Int? = null,
    val nextLeadDays: Int? = null
)

@Entity(tableName = "plan_items")
data class PlanItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timeMinutes: Int? = null,  // null = any time
    val isWater: Boolean = false,
    val sort: Int = 0
)

@Entity(tableName = "plan_checks", primaryKeys = ["itemId", "dateEpochDay"])
data class PlanCheckEntity(
    val itemId: Long,
    val dateEpochDay: Long,
    val done: Boolean
)

@Entity(tableName = "water_log")
data class WaterLogEntity(
    @PrimaryKey val dateEpochDay: Long,
    val count: Int
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dateEpochDay: Long,
    val note: String? = null,
    val photoUri: String? = null,
    val googleMediaId: String? = null   // Photos mediaItem id once backed up
)

@Entity(tableName = "photo_uploads")
data class PhotoUploadEntity(
    @PrimaryKey val memoryId: Long,   // one uploaded Google media item per memory
    val mediaId: String,
    val uploadedAt: Long
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String
)
