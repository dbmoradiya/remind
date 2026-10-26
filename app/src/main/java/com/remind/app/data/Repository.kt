package com.remind.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class Repository(private val db: AppDatabase) {
    val reminders = db.reminders().observeAll()
    val memories = db.memories().observeAll()
    val planItems = db.plan().observeItems()
    val settings = db.settings().observeAll().map { list -> list.associate { it.key to it.value } }

    fun remindersWithRenewals(): Flow<List<Pair<ReminderEntity, List<RenewalEntity>>>> =
        kotlinx.coroutines.flow.combine(reminders, kotlinx.coroutines.flow.flow { emit(db.renewals().all()) }) { rs, ren ->
            rs.map { r -> r to ren.filter { it.reminderId == r.id } }
        }

    fun renewalsFor(id: Long) = db.renewals().observeFor(id)
    fun reminder(id: Long) = db.reminders().observeById(id)
    fun planChecks(date: LocalDate) = db.plan().observeChecks(date.toEpochDay())
    fun water(date: LocalDate) = db.water().observe(date.toEpochDay())

    suspend fun saveReminder(r: ReminderEntity): Long = db.reminders().upsert(r)
    suspend fun deleteReminder(r: ReminderEntity) = db.reminders().delete(r)
    suspend fun renew(record: RenewalEntity) = db.renewals().insert(record)
    suspend fun savePlanItem(p: PlanItemEntity) = db.plan().upsert(p)
    suspend fun deletePlanItem(p: PlanItemEntity) = db.plan().delete(p)
    suspend fun setPlanChecked(itemId: Long, date: LocalDate, done: Boolean) =
        db.plan().setCheck(PlanCheckEntity(itemId, date.toEpochDay(), done))
    suspend fun addWater(date: LocalDate, count: Int) = db.water().put(WaterLogEntity(date.toEpochDay(), count))
    suspend fun saveMemory(m: MemoryEntity) = db.memories().upsert(m)
    suspend fun deleteMemory(m: MemoryEntity) = db.memories().delete(m)
    suspend fun putSetting(key: String, value: String) = db.settings().put(SettingEntity(key, value))
    suspend fun getSetting(key: String) = db.settings().get(key)

    companion object {
        fun get(context: Context) = Repository(AppDatabase.get(context))
    }
}
