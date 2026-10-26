package com.remind.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.remind.app.data.AppDatabase
import com.remind.app.data.PlanCheckEntity
import com.remind.app.data.PlanItemEntity
import com.remind.app.data.ReminderEntity
import com.remind.app.data.ReminderType
import com.remind.app.notify.Notifications
import com.remind.app.notify.ReminderReceiver
import com.remind.app.notify.Scheduler
import com.remind.app.notify.SnoozeReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Direct-invocation tests for the notification receivers: snooze persistence,
 *  plan-habit alerts, and the actions attached to reminder notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiverTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val db by lazy { AppDatabase.get(ctx) }

    @Before
    fun reset() {
        runBlocking { withContext(Dispatchers.IO) { db.clearAllTables() } }
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun seedReminder(id: Long = 42): ReminderEntity {
        val r = ReminderEntity(id = id, name = "Policy", category = "Insurance",
            type = ReminderType.EXPIRY, dateEpochDay = LocalDate.now().toEpochDay(),
            validityMonths = 12, leadDays = 7, alarm = true, enabled = true, createdAt = 1)
        runBlocking { db.reminders().upsert(r) }
        return r
    }

    @Test fun snooze_persists_and_rearms() {
        val r = seedReminder()
        val intent = Intent(ctx, SnoozeReceiver::class.java)
            .putExtra(Scheduler.EXTRA_ID, r.id)
            .putExtra(Scheduler.EXTRA_DELAY, 3_600_000L)
        val before = System.currentTimeMillis()
        SnoozeReceiver().onReceive(ctx, intent)
        val snoozedUntil = runBlocking { db.reminders().byId(r.id)!!.snoozedUntilMs!! }
        assertTrue(snoozedUntil >= before + 3_600_000L)
        val am = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        assertTrue(am.scheduledAlarms.isNotEmpty())
    }

    @Test fun plan_alerts_when_unchecked() {
        runBlocking { db.plan().upsert(PlanItemEntity(id = 7, name = "Gym", timeMinutes = 390, sort = 1)) }
        val intent = Intent(ctx, ReminderReceiver::class.java)
            .putExtra(Scheduler.EXTRA_KIND, Scheduler.KIND_PLAN)
            .putExtra(Scheduler.EXTRA_ID, 7L)
        ReminderReceiver().onReceive(ctx, intent)
        val nm = shadowOf(ctx.getSystemService(NotificationManager::class.java))
        assertTrue(nm.allNotifications.any { it.extras.getString("android.title") == "Gym" })
    }

    @Test fun plan_silent_when_already_checked() {
        runBlocking {
            db.plan().upsert(PlanItemEntity(id = 8, name = "Stretch", timeMinutes = 480, sort = 1))
            db.plan().setCheck(PlanCheckEntity(itemId = 8, dateEpochDay = LocalDate.now().toEpochDay(), done = true))
        }
        val intent = Intent(ctx, ReminderReceiver::class.java)
            .putExtra(Scheduler.EXTRA_KIND, Scheduler.KIND_PLAN)
            .putExtra(Scheduler.EXTRA_ID, 8L)
        ReminderReceiver().onReceive(ctx, intent)
        val nm = shadowOf(ctx.getSystemService(NotificationManager::class.java))
        assertEquals(0, nm.size())
    }

    @Test fun reminder_notification_has_snooze_actions() {
        Notifications.post(ctx, 101, "Policy", "3 days left to renew", false, 55L)
        val nm = shadowOf(ctx.getSystemService(NotificationManager::class.java))
        val n = nm.getNotification(101)
        val labels = n.actions?.map { it.title.toString() } ?: emptyList()
        assertTrue(labels.contains("Snooze 1 hour"))
        assertTrue(labels.contains("Snooze 1 day"))
    }
}
