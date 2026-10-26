package com.remind.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.remind.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Exercises real tap-through flows against the actual app: navigation,
 *  saving, renewals, toggles, settings persistence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val db by lazy { AppDatabase.get(ApplicationProvider.getApplicationContext()) }

    @Before
    fun initWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        // The Room file DB survives across test methods under Robolectric, so
        // reset to the same baseline the app seeds on first launch, otherwise
        // earlier tests' rows change what later screens render.
        runBlocking {
            withContext(Dispatchers.IO) { db.clearAllTables() }
            val p = db.plan()
            p.upsert(com.remind.app.data.PlanItemEntity(id = 1, name = "Gym", timeMinutes = 6 * 60 + 30, sort = 1))
            p.upsert(com.remind.app.data.PlanItemEntity(id = 2, name = "Stretch", timeMinutes = 8 * 60, sort = 2))
            p.upsert(com.remind.app.data.PlanItemEntity(id = 3, name = "Fruit", sort = 3))
            p.upsert(com.remind.app.data.PlanItemEntity(id = 4, name = "Water", isWater = true, sort = 4))
        }
    }

    @Test fun navigation_between_tabs() {
        // Navigation transitions settle over a few frames; wait until the
        // destination's marker is actually displayed, then hard-assert it.
        fun SemanticsNodeInteraction.assertEventuallyDisplayed() {
            rule.waitUntil(5000) { runCatching { this.assertIsDisplayed() }.isSuccess }
            this.assertIsDisplayed()
        }
        rule.onNodeWithText("Reminders").performClick()
        rule.onNodeWithText("Add").assertEventuallyDisplayed()
        rule.onNodeWithText("Memories").performClick()
        rule.onNodeWithText("Save a memory - a purchase, a trip. Once a year, a simple notification brings it back.", substring = true).assertEventuallyDisplayed()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("THEME").assertEventuallyDisplayed()
        rule.onNodeWithText("Today").performClick()
        rule.onNodeWithText("PLAN").assertEventuallyDisplayed()
    }

    @Test fun add_expiry_reminder_saves_and_lists() {
        rule.onNodeWithText("Reminders").performClick()
        rule.onNodeWithText("Add").performClick()
        rule.onNodeWithText("Name").performTextInput("Test policy")
        rule.onNodeWithText("Purchased on (d/m/yyyy)").performTextInput("01/01/2026")
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Test policy").assertIsDisplayed()
        rule.waitUntil(5000) {
            runBlocking { db.reminders().enabled().map { it.name }.contains("Test policy") }
        }
    }

    @Test fun add_reminder_rejects_bad_date() {
        rule.onNodeWithText("Reminders").performClick()
        rule.onNodeWithText("Add").performClick()
        rule.onNodeWithText("Name").performTextInput("Bad date item")
        rule.onNodeWithText("Purchased on (d/m/yyyy)").performTextInput("32/13/2026")
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Enter the date as d/m/yyyy", substring = true).assertIsDisplayed()
    }

    @Test fun plan_toggle_persists() {
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Stretch", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Stretch", substring = true).performClick()
        rule.waitForIdle()
        rule.waitUntil(5000) {
            runBlocking { db.plan().checks(LocalDate.now().toEpochDay()).any { it.done } }
        }
    }

    @Test fun water_tap_increments() {
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Water", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Water", substring = true).performClick()
        rule.waitForIdle()
        rule.waitUntil(5000) {
            runBlocking { (db.water().get(LocalDate.now().toEpochDay())?.count ?: 0) >= 1 }
        }
    }

    @Test fun theme_chip_persists_selection() {
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Dark").performClick()
        rule.waitForIdle()
        rule.waitUntil(5000) {
            runBlocking { db.settings().get("theme") == "DARK" }
        }
    }

    @Test fun quiet_hours_dialog_persists_both_times() {
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Quiet hours").performClick()
        rule.onNodeWithText("Quiet from (HH:mm)").performTextReplacement("23:00")
        rule.onNodeWithText("Quiet until (HH:mm)").performTextReplacement("06:30")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        rule.waitUntil(5000) {
            runBlocking { db.settings().get("water_end") == "1380" && db.settings().get("water_start") == "390" }
        }
        rule.onNodeWithText("11 PM - 6 AM").assertIsDisplayed()
    }

    @Test fun quiet_hours_dialog_rejects_bad_input() {
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Quiet hours").performClick()
        rule.onNodeWithText("Quiet from (HH:mm)").performTextReplacement("99:99")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Use HH:mm, e.g. 22:30").assertIsDisplayed()
    }

    @Test fun memory_save_persists() {
        rule.onNodeWithText("Memories").performClick()
        rule.onNodeWithText("Add").performClick()
        rule.onNodeWithText("What happened").performTextInput("Test memory")
        rule.onNodeWithText("Date (d/m/yyyy)").performTextInput("10/5/2024")
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitForIdle()
        rule.waitUntil(5000) {
            runBlocking { db.memories().all().any { it.title == "Test memory" } }
        }
    }

    @Test fun renew_flow_creates_renewal_record() {
        val today = LocalDate.now()
        runBlocking {
            db.reminders().upsert(com.remind.app.data.ReminderEntity(
                name = "Renew me", category = "Vehicle", type = com.remind.app.data.ReminderType.EXPIRY,
                anchorEpochDay = today.minusMonths(11).toEpochDay(), validityMonths = 12,
                leadDays = 15, enabled = true))
        }
        rule.onNodeWithText("Reminders").performClick()
        rule.waitForIdle()
        // The list populates asynchronously from the Room flow; wait for the
        // seeded item to actually render before clicking (was a bare click at
        // this spot - failed once under a resource-starved test JVM).
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Renew me", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Renew me", substring = true).performClick()
        rule.onNodeWithText("Renew").performClick()
        rule.waitForIdle()
        rule.waitUntil(5000) {
            runBlocking { db.renewals().all().isNotEmpty() }
        }
    }

    @Test fun detail_toggles_and_delete() {
        runBlocking {
            db.reminders().upsert(com.remind.app.data.ReminderEntity(
                id = 42, name = "Toggle me", category = "Vehicle", type = com.remind.app.data.ReminderType.EXPIRY,
                anchorEpochDay = LocalDate.now().minusMonths(11).toEpochDay(), validityMonths = 12,
                leadDays = 15, enabled = true, alarm = false))
        }
        rule.onNodeWithText("Reminders").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Toggle me", substring = true).performClick()
        rule.waitForIdle()
        // alarm toggle on
        rule.onNodeWithText("Alarm ring ♪", substring = true).performScrollTo().performClick()
        rule.waitUntil(5000) { runBlocking { db.reminders().byId(42)?.alarm == true } }
        // enable -> paused
        rule.onNodeWithText("Enabled", substring = true).performScrollTo().performClick()
        rule.waitUntil(5000) { runBlocking { db.reminders().byId(42)?.enabled == false } }
        rule.onNodeWithText("Paused", substring = true).assertIsDisplayed()
        // delete removes row and returns to the list
        rule.onNodeWithText("Delete").performScrollTo().performClick()
        rule.waitUntil(5000) { runBlocking { db.reminders().byId(42) == null } }
    }

    @Test fun edit_reminder_updates_existing_row() {
        runBlocking {
            db.reminders().upsert(com.remind.app.data.ReminderEntity(
                id = 43, name = "Old name", category = "Vehicle", type = com.remind.app.data.ReminderType.EXPIRY,
                anchorEpochDay = LocalDate.now().minusMonths(2).toEpochDay(), validityMonths = 12,
                leadDays = 15, enabled = true, amount = 500.0))
        }
        rule.onNodeWithText("Reminders").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Old name", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("₹500", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Edit").performScrollTo().performClick()
        rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodes(hasSetTextAction() and hasText("Old name")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction() and hasText("Old name")).performTextReplacement("New name")
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitUntil(5000) { runBlocking { db.reminders().byId(43)?.name == "New name" } }
        // still one row, same id, amount preserved
        rule.waitUntil(5000) { runBlocking { db.reminders().byId(43)?.amount == 500.0 } }
    }

    @Test fun plan_manage_add_and_delete_habit() {
        rule.onNodeWithText("Manage plan").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add habit").performClick()
        rule.onNodeWithText("Name").performTextInput("Read")
        rule.onNodeWithText("Reminder time (HH:mm, optional)").performTextInput("21:30")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5000) { runBlocking { db.plan().items().any { it.name == "Read" && it.timeMinutes == 21 * 60 + 30 } } }
        // tap the habit row to edit, then delete it
        rule.waitForIdle()
        rule.onNodeWithText("Read").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(5000) { runBlocking { db.plan().items().none { it.name == "Read" } } }
    }

    @Test fun plan_manage_rejects_bad_time() {
        rule.onNodeWithText("Manage plan").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add habit").performClick()
        rule.onNodeWithText("Name").performTextInput("Bad time")
        rule.onNodeWithText("Reminder time (HH:mm, optional)").performTextInput("99:99")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Use HH:mm, e.g. 6:30").assertIsDisplayed()
    }

    @Test fun custom_validity_days_flow() {
        rule.onNodeWithText("Reminders").performClick()
        rule.onNodeWithText("Add").performClick()
        rule.onNodeWithText("Name").performTextInput("Custom validity")
        rule.onNodeWithText("Purchased on (d/m/yyyy)").performTextInput("01/01/2026")
        rule.onNodeWithText("Custom").performScrollTo().performClick()
        rule.onNodeWithText("Valid for (days)").performScrollTo().performTextInput("45")
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitUntil(5000) {
            runBlocking { db.reminders().enabled().any { it.name == "Custom validity" && it.validityDays == 45 } }
        }
    }

    @Test fun export_then_import_restores_data() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.remind.app.ui.AppViewModel(app)
        runBlocking {
            db.reminders().upsert(com.remind.app.data.ReminderEntity(
                id = 77, name = "Export row", category = "Bills", type = com.remind.app.data.ReminderType.EXPIRY,
                anchorEpochDay = LocalDate.now().toEpochDay(), validityMonths = 6, leadDays = 10,
                amount = 1200.0, enabled = true))
            val json = vm.exportJson()
            assertTrue(json.contains("Export row"))
            db.reminders().delete(db.reminders().byId(77)!!)
            assertTrue(vm.importJson(json).ok)
            val back = db.reminders().byId(77)
            assertNotNull(back)
            assertEquals("Export row", back!!.name)
            assertEquals(1200.0, back.amount!!, 0.001)
        }
    }

    @Test fun export_import_restores_photo_bytes_to_fresh_path() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.remind.app.ui.AppViewModel(app)
        runBlocking {
            val dir = java.io.File(app.filesDir, "memories").apply { mkdirs() }
            val photo = java.io.File(dir, "orig-photo.jpg")
            val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) + ByteArray(2048) { 42 }
            photo.writeBytes(bytes)
            val origUri = android.net.Uri.fromFile(photo).toString()
            db.memories().upsert(com.remind.app.data.MemoryEntity(
                id = 88, title = "With photo", dateEpochDay = LocalDate.now().toEpochDay(), photoUri = origUri))
            val json = vm.exportJson()
            assertTrue(json.contains("photoData"))
            // simulate fresh install: row and file gone
            db.memories().delete(db.memories().all().first { it.id == 88L })
            photo.delete()
            assertTrue(vm.importJson(json).ok)
            val back = db.memories().all().first { it.id == 88L }
            assertNotNull(back.photoUri)
            assertNotEquals("uri remapped to fresh path", origUri, back.photoUri)
            val restored = java.io.File(android.net.Uri.parse(back.photoUri).path!!)
            assertTrue(restored.exists())
            assertArrayEquals(bytes, restored.readBytes())
        }
    }

    @Test fun corrupt_import_fails_without_touching_data() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.remind.app.ui.AppViewModel(app)
        runBlocking {
            db.reminders().upsert(com.remind.app.data.ReminderEntity(
                id = 90, name = "Keep me", category = "Bills", type = com.remind.app.data.ReminderType.EXPIRY,
                anchorEpochDay = LocalDate.now().toEpochDay(), validityMonths = 6, leadDays = 10, enabled = true))
            assertFalse(vm.importJson("this is not json {{{").ok)
            assertFalse(vm.importJson("{\"reminders\": [{\"id\": 1}]}").ok)
            assertNotNull(db.reminders().byId(90))
            assertEquals("Keep me", db.reminders().byId(90)!!.name)
        }
    }

    @Test fun import_with_corrupt_photo_data_fails_clean() {
        runBlocking {
            db.memories().upsert(com.remind.app.data.MemoryEntity(id = 601, title = "Before", dateEpochDay = 20010))
        }
        val bad = """{"version":2,"reminders":[],"memories":[{"id":602,"title":"X","dateEpochDay":20010,"photoUri":"/nowhere/x.jpg","photoData":"!!!not-base64!!!"}]}"""
        val vm = com.remind.app.ui.AppViewModel(ApplicationProvider.getApplicationContext())
        val res = runBlocking { vm.importJson(bad) }
        assertTrue(!res.ok)
        runBlocking {
            assertTrue(db.memories().all().any { it.id == 601L && it.title == "Before" })
            assertTrue(db.memories().all().none { it.id == 602L })
        }
    }

    @Test fun import_reports_photo_that_cannot_be_restored() {
        val json = """{"version":2,"reminders":[],"memories":[{"id":603,"title":"Lost photo","dateEpochDay":20011,"photoUri":"/data/old/deleted.jpg"}]}"""
        val vm = com.remind.app.ui.AppViewModel(ApplicationProvider.getApplicationContext())
        val res = runBlocking { vm.importJson(json) }
        assertTrue(res.ok)
        assertEquals(1, res.droppedPhotos)
        runBlocking {
            val m = db.memories().all().first { it.id == 603L }
            assertNull(m.photoUri)
        }
    }

    @Test fun export_with_missing_photo_file_still_exports_data() {
        runBlocking {
            db.memories().upsert(com.remind.app.data.MemoryEntity(id = 604, title = "Gone file", dateEpochDay = 20012, photoUri = "/nowhere/gone.jpg"))
        }
        val vm = com.remind.app.ui.AppViewModel(ApplicationProvider.getApplicationContext())
        val json = runBlocking { vm.exportJson() }
        assertTrue(json.contains("Gone file"))
        assertTrue(!json.contains("photoData"))
    }

}
