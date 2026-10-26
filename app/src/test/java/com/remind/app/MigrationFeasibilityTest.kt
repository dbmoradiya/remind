package com.remind.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.remind.app.data.*
import com.remind.app.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Feasibility only. No alternate package built or installed. Two isolated Room
 * stores simulate old/new app sandboxes. Exporter reconstructed from old source,
 * not executed from the delivered APK, which remains a separate verification gap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MigrationFeasibilityTest {
    @Test fun current_export_can_transfer_core_records_and_photo_bytes_to_fresh_database() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val old = AppDatabase.get(app)
        withContext(Dispatchers.IO) { old.clearAllTables() }
        val photo = File(app.filesDir,"migration-source.jpg").apply { writeBytes(ByteArray(4096) { (it%255).toByte() }) }
        val expectedBytes = photo.readBytes()
        old.reminders().upsert(ReminderEntity(id=21,name="Insurance",category="Vehicle",type=ReminderType.EXPIRY,
            anchorEpochDay=20000,validityMonths=12,leadDays=15,amount=1200.0,contactName="Family",contactUri="content://contacts/7",snoozedUntilMs=9999999))
        old.renewals().insert(RenewalEntity(id=22,reminderId=21,renewedAtEpochDay=20010,periodMonths=12,nextLeadDays=12))
        old.plan().upsert(PlanItemEntity(id=23,name="Gym",timeMinutes=390))
        old.plan().setCheck(PlanCheckEntity(23,LocalDate.now().toEpochDay(),true))
        old.water().put(WaterLogEntity(LocalDate.now().toEpochDay(),7))
        old.memories().upsert(MemoryEntity(id=24,title="Trip",dateEpochDay=20000,note="private",photoUri=android.net.Uri.fromFile(photo).toString(),googleMediaId="media-old"))
        old.settings().put(SettingEntity("theme","BLACK"))
        old.settings().put(SettingEntity("water_enabled","false"))
        val json = AppViewModel(app).exportJson()
        val target = Room.inMemoryDatabaseBuilder(app,AppDatabase::class.java).allowMainThreadQueries().build()
        val field=AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible=true }
        try {
            field.set(null,target)
            val result=AppViewModel(app).importJson(json)
            assertTrue(result.ok); assertEquals(1,result.importedPhotos);assertEquals(0,result.droppedPhotos)
            assertEquals("Insurance",target.reminders().byId(21)!!.name)
            assertEquals(1200.0,target.reminders().byId(21)!!.amount!!,0.001)
            assertEquals("content://contacts/7",target.reminders().byId(21)!!.contactUri)
            assertEquals(1,target.renewals().all().size)
            assertEquals("Gym",target.plan().items().single().name)
            assertEquals("BLACK",target.settings().get("theme"))
            val restored=target.memories().all().single()
            assertEquals("media-old",restored.googleMediaId)
            assertArrayEquals(expectedBytes,File(restored.photoUri!!).readBytes())
            assertNotEquals(photo.path,restored.photoUri)
            // Current format has confirmed omissions. A migration must expose them.
            assertNull(target.reminders().byId(21)!!.snoozedUntilMs)
            assertTrue(target.plan().checks(LocalDate.now().toEpochDay()).isEmpty())
            assertNull(target.water().get(LocalDate.now().toEpochDay()))
            // Original store and original photo are not removed by import.
            assertTrue(photo.exists());assertEquals(7,old.water().get(LocalDate.now().toEpochDay())!!.count)
            println("MIGRATION_PROBE core+renewal+settings+photos OK; omissions: snoozes,plan_checks,water_log; contact URI only copied, permission not transferable")
        } finally { field.set(null,old);target.close() }
    }
}
