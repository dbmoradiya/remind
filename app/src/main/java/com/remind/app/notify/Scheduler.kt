package com.remind.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.remind.app.data.ReminderEntity
import com.remind.app.data.ReminderType
import com.remind.app.data.Repository
import com.remind.app.data.RenewalEntity
import com.remind.app.domain.MemoryEngine
import com.remind.app.domain.RenewalEngine
import com.remind.app.domain.RepeatEngine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object Scheduler {
    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"
    const val KIND_REMINDER = "reminder"
    const val KIND_MEMORY = "memory"
    const val KIND_PLAN = "plan"
    const val KIND_SNOOZE = "snooze"
    const val EXTRA_DELAY = "delay"
    const val WATER_WORK = "water-reminders"

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pending(context: Context, kind: String, id: Long): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, (kind.hashCode() xor id.toInt()), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun atNextTime(timeMinutes: Int?, now: LocalDateTime = LocalDateTime.now()): Long {
        val time = LocalTime.ofSecondOfDay(((timeMinutes ?: 9 * 60) * 60).toLong())
        var t = now.with(time).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Daily check alarm for one reminder: fires every day; receiver decides if it should notify. */
    fun scheduleReminder(context: Context, reminder: ReminderEntity) {
        cancel(context, KIND_REMINDER, reminder.id)
        if (!reminder.enabled) return
        val am = context.getSystemService(AlarmManager::class.java)
        val triggerAt = when (reminder.type) {
            ReminderType.EXPIRY -> atNextTime(reminder.timeMinutes)
            else -> atNextTime(reminder.timeMinutes)
        }
        val pi = pending(context, KIND_REMINDER, reminder.id)
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun scheduleMemory(context: Context, id: Long) {
        cancel(context, KIND_MEMORY, id)
        val pi = pending(context, KIND_MEMORY, id)
        val am = context.getSystemService(AlarmManager::class.java)
        val triggerAt = atNextTime(9 * 60)
        if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    /** Daily fixed-time reminder for one plan habit (design: fixed time per habit). */
    fun schedulePlanItem(context: Context, item: com.remind.app.data.PlanItemEntity) {
        cancel(context, KIND_PLAN, item.id)
        val t = item.timeMinutes ?: return
        val pi = pending(context, KIND_PLAN, item.id)
        val am = context.getSystemService(AlarmManager::class.java)
        val triggerAt = atNextTime(t)
        if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    /** One-shot snooze fire at an absolute time (design: 1 hour or 1 day later). */
    fun scheduleSnooze(context: Context, reminderId: Long, triggerAtMs: Long) {
        val pi = pending(context, KIND_SNOOZE, reminderId)
        val am = context.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
    }

    fun cancel(context: Context, kind: String, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, kind, id))
    }

    /** Water: periodic worker (30 min min allowed is 15; we gate inside the window). Inexact by design. */
    fun scheduleWater(context: Context, intervalMinutes: Long) {
        val interval = intervalMinutes.coerceAtLeast(15)
        val req = PeriodicWorkRequestBuilder<WaterWorker>(interval, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATER_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancelWater(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WATER_WORK)

    /** Reschedule everything (boot, restore, app start). */
    suspend fun rescheduleAll(context: Context, repo: Repository) {
        val db = com.remind.app.data.AppDatabase.get(context)
        val nowMs = System.currentTimeMillis()
        for (r in db.reminders().enabled()) {
            scheduleReminder(context, r)
            r.snoozedUntilMs?.let { if (it > nowMs) scheduleSnooze(context, r.id, it) }
        }
        for (m in db.memories().all()) scheduleMemory(context, m.id)
        for (p in db.plan().items()) schedulePlanItem(context, p)
        val interval = repo.getSetting("water_interval")?.toLongOrNull() ?: 30
        val enabled = repo.getSetting("water_enabled")?.toBooleanStrictOrNull() ?: true
        if (enabled) scheduleWater(context, interval) else cancelWater(context)
    }
}
