package com.remind.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.remind.app.data.AppDatabase
import com.remind.app.data.ReminderType
import com.remind.app.domain.MemoryEngine
import com.remind.app.domain.RenewalEngine
import com.remind.app.domain.RepeatEngine
import com.remind.app.domain.WaterPlan
import com.remind.app.widget.RemindWidget
import java.time.LocalDate
import java.time.LocalTime

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending: BroadcastReceiver.PendingResult? = goAsync()
        kotlinx.coroutines.runBlocking {
            try {
                val db = AppDatabase.get(context)
                val kind = intent.getStringExtra(Scheduler.EXTRA_KIND) ?: return@runBlocking
                val id = intent.getLongExtra(Scheduler.EXTRA_ID, -1)
                val today = LocalDate.now()
                when (kind) {
                    Scheduler.KIND_REMINDER -> {
                        val r = db.reminders().byId(id) ?: return@runBlocking
                        if (!r.enabled) return@runBlocking
                        // a pending snooze fires via KIND_SNOOZE; the daily check skips while snoozed
                        val snoozed = r.snoozedUntilMs?.let { it > System.currentTimeMillis() } == true
                        if (!snoozed) when (r.type) {
                            ReminderType.EXPIRY -> {
                                val renewals = db.renewals().all().filter { it.reminderId == r.id }
                                val cycle = RenewalEngine.currentCycle(r, renewals)
                                if (RenewalEngine.shouldRemindOn(cycle, today)) {
                                    val left = RenewalEngine.daysLeft(cycle, today)
                                    val text = if (left >= 0) "$left days left to renew" else "Expired ${-left} days ago - renew now"
                                    Notifications.post(context, r.id.toInt(), r.name, text, r.alarm, r.id)
                                }
                            }
                            else -> {
                                val next = RepeatEngine.nextAfter(r, today.minusDays(1))
                                if (next == today) Notifications.post(context, r.id.toInt(), r.name, r.category, r.alarm, r.id)
                            }
                        }
                        Scheduler.scheduleReminder(context, r) // next day
                    }
                    Scheduler.KIND_SNOOZE -> {
                        val r = db.reminders().byId(id) ?: return@runBlocking
                        if (r.enabled) {
                            db.reminders().upsert(r.copy(snoozedUntilMs = null))
                            Notifications.post(context, r.id.toInt(), r.name, "Snoozed reminder", r.alarm, r.id)
                        }
                    }
                    Scheduler.KIND_PLAN -> {
                        val item = db.plan().items().find { it.id == id } ?: return@runBlocking
                        val checked = db.plan().checks(today.toEpochDay()).any { it.itemId == id && it.done }
                        if (!checked) Notifications.post(context, (50000 + item.id).toInt(), item.name,
                            "Time for ${item.name}", false)
                        Scheduler.schedulePlanItem(context, item) // next day
                    }
                    Scheduler.KIND_MEMORY -> {
                        val m = db.memories().all().find { it.id == id } ?: return@runBlocking
                        val date = LocalDate.ofEpochDay(m.dateEpochDay)
                        if (MemoryEngine.nextAnniversary(date, today) == today) {
                            val years = MemoryEngine.yearsAgo(date, today)
                            Notifications.post(context, (100000 + m.id).toInt(), "Memory",
                                "$years year${if (years == 1) "" else "s"} ago today: ${m.title}", false)
                        }
                        Scheduler.scheduleMemory(context, m.id)
                    }
                }
                RemindWidget.updateAll(context)
            } finally { pending?.finish() }
        }
    }
}

/** Handles the "Snooze 1 hour / 1 day" notification actions: dismisses the
 *  notification, persists the snooze (survives reboot) and schedules the re-fire. */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Scheduler.EXTRA_ID, -1)
        if (id < 0) return
        val delay = intent.getLongExtra(Scheduler.EXTRA_DELAY, 3_600_000L)
        val pending: BroadcastReceiver.PendingResult? = goAsync()
        kotlinx.coroutines.runBlocking {
            try {
                val db = AppDatabase.get(context)
                val r = db.reminders().byId(id) ?: return@runBlocking
                val at = System.currentTimeMillis() + delay
                db.reminders().upsert(r.copy(snoozedUntilMs = at))
                Notifications.cancel(context, r.id.toInt())
                Scheduler.scheduleSnooze(context, id, at)
            } finally { pending?.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_TIME_CHANGED &&
            a != Intent.ACTION_TIMEZONE_CHANGED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending: BroadcastReceiver.PendingResult? = goAsync()
        kotlinx.coroutines.runBlocking {
            try { Scheduler.rescheduleAll(context, com.remind.app.data.Repository.get(context)) }
            finally { pending?.finish() }
        }
    }
}

class WaterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val interval = db.settings().get("water_interval")?.toIntOrNull() ?: WaterPlan.DEFAULT_INTERVAL_MIN
        val start = db.settings().get("water_start")?.toIntOrNull() ?: WaterPlan.DEFAULT_START_MIN
        val end = db.settings().get("water_end")?.toIntOrNull() ?: WaterPlan.DEFAULT_END_MIN
        val now = LocalTime.now()
        val minute = now.hour * 60 + now.minute
        if (!WaterPlan.inWindow(minute, start, end)) return Result.success() // quiet hours
        val today = LocalDate.now().toEpochDay()
        val done = db.water().get(today)?.count ?: 0
        val total = WaterPlan.dailySlots(start, end, interval)
        if (done < total) {
            val next = done + 1
            db.water().put(com.remind.app.data.WaterLogEntity(today, next))
            Notifications.postWater(applicationContext, next, total)
            RemindWidget.updateAll(applicationContext)
        }
        return Result.success()
    }
}
