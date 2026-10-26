package com.remind.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY name") fun observeAll(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE enabled = 1") suspend fun enabled(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun byId(id: Long): ReminderEntity?
    @Query("SELECT * FROM reminders WHERE id = :id") fun observeById(id: Long): Flow<ReminderEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(r: ReminderEntity): Long
    @Delete suspend fun delete(r: ReminderEntity)
}

@Dao
interface RenewalDao {
    @Query("SELECT * FROM renewals WHERE reminderId = :reminderId ORDER BY renewedAtEpochDay DESC") fun observeFor(reminderId: Long): Flow<List<RenewalEntity>>
    @Query("SELECT * FROM renewals ORDER BY renewedAtEpochDay DESC") suspend fun all(): List<RenewalEntity>
    @Insert suspend fun insert(r: RenewalEntity): Long
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan_items ORDER BY sort, id") fun observeItems(): Flow<List<PlanItemEntity>>
    @Query("SELECT * FROM plan_items ORDER BY sort, id") suspend fun items(): List<PlanItemEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: PlanItemEntity): Long
    @Delete suspend fun delete(p: PlanItemEntity)
    @Query("SELECT * FROM plan_checks WHERE dateEpochDay = :date") fun observeChecks(date: Long): Flow<List<PlanCheckEntity>>
    @Query("SELECT * FROM plan_checks WHERE dateEpochDay = :date") suspend fun checks(date: Long): List<PlanCheckEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setCheck(c: PlanCheckEntity)
}

@Dao
interface WaterDao {
    @Query("SELECT * FROM water_log WHERE dateEpochDay = :date") fun observe(date: Long): Flow<WaterLogEntity?>
    @Query("SELECT * FROM water_log WHERE dateEpochDay = :date") suspend fun get(date: Long): WaterLogEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(w: WaterLogEntity)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY dateEpochDay DESC") fun observeAll(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories") suspend fun all(): List<MemoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: MemoryEntity): Long
    @Delete suspend fun delete(m: MemoryEntity)
}

@Dao
interface PhotoUploadDao {
    @Query("SELECT * FROM photo_uploads WHERE memoryId = :memoryId") suspend fun byMemory(memoryId: Long): PhotoUploadEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(u: PhotoUploadEntity)
    @Query("SELECT * FROM photo_uploads") suspend fun all(): List<PhotoUploadEntity>
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE key = :key") suspend fun get(key: String): String?
    @Query("SELECT * FROM settings") fun observeAll(): Flow<List<SettingEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(s: SettingEntity)
}
