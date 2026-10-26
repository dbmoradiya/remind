package com.remind.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.remind.app.data.AppDatabase
import com.remind.app.data.PlanCheckEntity
import com.remind.app.data.PlanItemEntity
import com.remind.app.data.ReminderEntity
import com.remind.app.data.ReminderType
import com.remind.app.notify.BootReceiver
import com.remind.app.notify.WaterWorker
import com.remind.app.widget.PlanToggleAction
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
import java.time.LocalTime

/** Coverage for paths the first 53 tests did not reach: the boot/timezone/package
 *  receiver, the widget check-off action, contact linking persistence, and the
 *  water worker's window/cap glue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GapCoverageTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val db by lazy { AppDatabase.get(ctx) }

    @Before
    fun reset() {
        runBlocking { withContext(Dispatchers.IO) { db.clearAllTables() } }
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
    }

    @Test fun boot_receiver_reschedules_after_timezone_change() {
        runBlocking {
            db.reminders().upsert(ReminderEntity(id = 9, name = "Visa", category = "Travel",
                type = ReminderType.EXPIRY, dateEpochDay = LocalDate.now().toEpochDay() + 30,
                validityMonths = 12, leadDays = 7, alarm = true, enabled = true, createdAt = 1))
        }
        BootReceiver().onReceive(ctx, Intent(ctx, BootReceiver::class.java)
            .setAction(Intent.ACTION_TIMEZONE_CHANGED))
        val am = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        assertTrue("rescheduleAll must re-register alarms", am.scheduledAlarms.isNotEmpty())
    }

    @Test fun boot_receiver_ignores_unknown_action() {
        BootReceiver().onReceive(ctx, Intent(ctx, BootReceiver::class.java)
            .setAction(Intent.ACTION_VIEW))
        val am = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        assertEquals(0, am.scheduledAlarms.size)
    }

    @Test fun widget_plan_toggle_marks_done_and_back() {
        runBlocking { db.plan().upsert(PlanItemEntity(id = 5, name = "Walk", timeMinutes = 420, sort = 1)) }
        val fakeId = object : GlanceId { override fun toString() = "fake" }
        val params = actionParametersOf(PlanToggleAction.ItemIdKey to 5L)
        val today = LocalDate.now().toEpochDay()
        runBlocking { PlanToggleAction().onAction(ctx, fakeId, params) }
        assertTrue(runBlocking {
            db.plan().checks(today).firstOrNull { it.itemId == 5L }?.done
        } == true)
        runBlocking { PlanToggleAction().onAction(ctx, fakeId, params) }
        assertFalse(runBlocking {
            db.plan().checks(today).firstOrNull { it.itemId == 5L }?.done
        } == true)
    }

    @Test fun migration_2_to_3_preserves_memories_and_adds_google_columns() {
        // build a real v2 database by hand (exact v2 schema), seed a memory,
        // then let Room open it: MIGRATION_2_3 must run and the row must survive
        val dbFile = ctx.getDatabasePath("migration-test.db")
        dbFile.delete()
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, type TEXT NOT NULL, repeatUnit TEXT, repeatInterval INTEGER NOT NULL, dateEpochDay INTEGER, anchorEpochDay INTEGER, validityMonths INTEGER, validityDays INTEGER, leadDays INTEGER NOT NULL, timeMinutes INTEGER, alarm INTEGER NOT NULL, enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL, amount REAL, contactName TEXT, contactUri TEXT, snoozedUntilMs INTEGER)")
        sqlite.execSQL("CREATE TABLE renewals (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, reminderId INTEGER NOT NULL, renewedAtEpochDay INTEGER NOT NULL, periodMonths INTEGER, periodDays INTEGER, nextLeadDays INTEGER)")
        sqlite.execSQL("CREATE TABLE plan_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, timeMinutes INTEGER, isWater INTEGER NOT NULL, sort INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE plan_checks (itemId INTEGER NOT NULL, dateEpochDay INTEGER NOT NULL, done INTEGER NOT NULL, PRIMARY KEY(itemId, dateEpochDay))")
        sqlite.execSQL("CREATE TABLE water_log (dateEpochDay INTEGER NOT NULL PRIMARY KEY, count INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE settings (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
        sqlite.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, dateEpochDay INTEGER NOT NULL, note TEXT, photoUri TEXT)")
        sqlite.execSQL("INSERT INTO memories (id, title, dateEpochDay, note, photoUri) VALUES (7, 'Old trip', 19900, 'note-1', '/old/path.jpg')")
        sqlite.execSQL("PRAGMA user_version = 2")
        sqlite.close()
        val migrated = androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-test.db")
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val m = runBlocking { migrated.memories().all().single() }
        assertEquals(7L, m.id)
        assertEquals("Old trip", m.title)
        assertEquals("note-1", m.note)
        assertNull(m.googleMediaId)   // new column defaults to null
        runBlocking { migrated.photoUploads().all() }.let { assertTrue(it.isEmpty()) }   // ledger table created
        migrated.close()
    }

    @Test fun contact_link_persists_on_reminder() {
        runBlocking {
            db.reminders().upsert(ReminderEntity(id = 11, name = "Mummy birthday",
                category = "Personal", type = ReminderType.DATE,
                dateEpochDay = LocalDate.now().toEpochDay(), validityMonths = 0, leadDays = 3,
                alarm = false, enabled = true, createdAt = 1,
                contactName = "Mummy", contactUri = "content://com.android.contacts/contacts/7"))
        }
        val r = runBlocking { db.reminders().byId(11)!! }
        assertEquals("Mummy", r.contactName)
        assertEquals("content://com.android.contacts/contacts/7", r.contactUri)
    }

    @Test fun water_worker_increments_inside_window() {
        val now = LocalTime.now()
        val nowMin = now.hour * 60 + now.minute
        runBlocking {
            db.settings().put(com.remind.app.data.SettingEntity("water_start", ((nowMin - 120 + 1440) % 1440).toString()))
            db.settings().put(com.remind.app.data.SettingEntity("water_end", ((nowMin + 120) % 1440).toString()))
            db.settings().put(com.remind.app.data.SettingEntity("water_interval", "30"))
        }
        val worker = TestListenableWorkerBuilder<WaterWorker>(ctx).build()
        runBlocking { assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork()) }
        val today = LocalDate.now().toEpochDay()
        assertEquals(1, runBlocking { db.water().get(today)?.count })
    }

    @Test fun water_worker_silent_outside_window() {
        val now = LocalTime.now()
        val nowMin = now.hour * 60 + now.minute
        runBlocking {
            // window sits 2-3 hours ahead of now, so now is always outside it
            db.settings().put(com.remind.app.data.SettingEntity("water_start", ((nowMin + 120) % 1440).toString()))
            db.settings().put(com.remind.app.data.SettingEntity("water_end", ((nowMin + 180) % 1440).toString()))
            db.settings().put(com.remind.app.data.SettingEntity("water_interval", "30"))
        }
        val worker = TestListenableWorkerBuilder<WaterWorker>(ctx).build()
        runBlocking { assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork()) }
        val today = LocalDate.now().toEpochDay()
        assertNull(runBlocking { db.water().get(today) })
    }
}
