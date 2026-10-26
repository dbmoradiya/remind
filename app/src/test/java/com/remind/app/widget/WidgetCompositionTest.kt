package com.remind.app.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.remind.app.data.AppDatabase
import com.remind.app.data.PlanItemEntity
import com.remind.app.data.ReminderEntity
import com.remind.app.data.ReminderType
import com.remind.app.data.RepeatUnit
import com.remind.app.data.WaterLogEntity
import com.remind.app.domain.RenewalEngine
import com.remind.app.domain.RepeatEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Renders the real widget composition through the Glance unit-test harness at
 *  the supported size tiers and asserts the emitted node tree: visible texts,
 *  responsive slicing (nothing clipped - content is chosen to fit), and the
 *  exact tap -> action-callback -> parameter bindings the launcher dispatches.
 *  Node-tree/binding evidence; pixel rasterisation needs a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetCompositionTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val db by lazy { AppDatabase.get(ctx) }
    private var gymId = 0L
    private val today = LocalDate.now()

    @Before
    fun seed() = runBlocking {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        gymId = db.plan().upsert(PlanItemEntity(name = "Gym", timeMinutes = 390, sort = 0))  // 6:30 AM
        db.plan().upsert(PlanItemEntity(name = "Fruit", sort = 1))                          // any time
        db.plan().upsert(PlanItemEntity(name = "Read", timeMinutes = 1290, sort = 2))       // 9:30 PM
        db.plan().upsert(PlanItemEntity(name = "Walk", timeMinutes = 450, sort = 3))        // 7:30 AM
        db.plan().upsert(PlanItemEntity(name = "Water", isWater = true, sort = 4))
        db.water().put(WaterLogEntity(today.toEpochDay(), 3))                               // -> 3/30
        db.reminders().upsert(ReminderEntity(name = "Rent", category = "Bills",
            type = ReminderType.REPEAT, repeatUnit = RepeatUnit.MONTH, repeatInterval = 1,
            dateEpochDay = today.toEpochDay(), timeMinutes = 540, enabled = true, createdAt = 1))
        db.reminders().upsert(ReminderEntity(name = "Visa", category = "Travel",
            type = ReminderType.DATE, dateEpochDay = today.toEpochDay() + 2,
            timeMinutes = 480, enabled = true, createdAt = 2))
        db.reminders().upsert(ReminderEntity(name = "Milk sub", category = "Home",
            type = ReminderType.EXPIRY, anchorEpochDay = today.toEpochDay() - 40,
            validityMonths = 1, leadDays = 3, enabled = true, createdAt = 3))
        Unit
    }

    private fun tree(size: DpSize, assertions: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(size)
            setContext(ctx)
            val data = runBlocking { RemindWidget().loadData(ctx) }
            provideComposable { RemindWidget().Content(data) }
            assertions()
        }

    @Test fun full_size_shows_all_content_and_bindings() = tree(DpSize(320.dp, 360.dp)) {
        onNode(hasTextEqualTo("Remind")).assertExists()
        onNode(hasText("6:30 AM")).assertExists()
        onNode(hasText("Any time")).assertExists()
        onNode(hasText("Walk")).assertExists()                 // 4th habit visible
        onNode(hasText("Water · 3/30")).assertExists()
        onNode(hasText("• Milk sub · overdue")).assertExists() // all 3 upcoming visible
        onNode(hasText("• Rent · today")).assertExists()
        onNode(hasText("• Visa ·")).assertExists()
        onNode(hasText("Tap to open Remind")).assertExists()   // footer
        // tap -> callback -> parameter bindings the launcher dispatches
        onNode(hasRunCallbackClickAction<PlanToggleAction>(
            actionParametersOf(PlanToggleAction.ItemIdKey to gymId))).assertExists()
        onNode(hasRunCallbackClickAction<WaterIncrementAction>()).assertExists()
        onNode(hasStartActivityClickAction(android.content.ComponentName(
            ctx, com.remind.app.MainActivity::class.java))).assertExists()
        // root + 4 habit rows + water row = 6 clickable nodes
        onAllNodes(hasClickAction()).assertCountEquals(6)
    }

    @Test fun min_supported_size_fits_without_clipping() = tree(DpSize(250.dp, 210.dp)) {
        // 210dp tier: 16 padding + 26 header + 2x48 habits + 48 water + 20 upcoming
        // = 206dp, fits the actual 210dp LocalSize with slack - no cell mapping assumed
        onNode(hasTextEqualTo("Remind")).assertExists()
        onNode(hasText("Gym")).assertExists()                  // 2 of 4 habits
        onNode(hasText("Fruit")).assertExists()
        onNode(hasText("Read")).assertDoesNotExist()           // sliced, not clipped
        onNode(hasText("Walk")).assertDoesNotExist()
        onNode(hasText("Water · 3/30")).assertExists()         // water always visible
        onNode(hasText("• Milk sub · overdue")).assertExists() // 1 upcoming at every size
        onNode(hasText("• Rent")).assertDoesNotExist()         // sliced
        onNode(hasText("Tap to open Remind")).assertDoesNotExist()
        onNode(hasRunCallbackClickAction<PlanToggleAction>(
            actionParametersOf(PlanToggleAction.ItemIdKey to gymId))).assertExists()
        onNode(hasRunCallbackClickAction<WaterIncrementAction>()).assertExists()
        // root + 2 habit rows + water row = 4 clickable nodes
        onAllNodes(hasClickAction()).assertCountEquals(4)
    }

    @Test fun standard_size_shows_three_upcoming() = tree(DpSize(320.dp, 320.dp)) {
        // 300-359dp tier: 3 habits + water + 3 upcoming = 294dp, fits
        onNode(hasText("Read")).assertExists()
        onNode(hasText("Walk")).assertDoesNotExist()           // 4th only at >=360dp
        onNode(hasText("• Milk sub · overdue")).assertExists()
        onNode(hasText("• Rent · today")).assertExists()
        onNode(hasText("• Visa ·")).assertExists()
        onNode(hasText("Tap to open Remind")).assertDoesNotExist() // footer only >=360dp
    }

    @Test fun loadData_upcoming_covers_all_kinds_chronological_with_dates() = runBlocking {
        val d = RemindWidget().loadData(ctx)
        assertEquals(3, d.upcoming.size)
        // overdue EXPIRY first, then today's REPEAT, then the future DATE reminder
        assertTrue("expiry overdue first: ${d.upcoming}", d.upcoming[0].startsWith("• Milk sub · overdue "))
        assertEquals("• Rent · today, 9:00 AM", d.upcoming[1])
        val visaDay = today.plusDays(2).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        assertEquals("• Visa · $visaDay, 8:00 AM", d.upcoming[2])
        // matches the app's own engine math for the same reminders
        val rent = db.reminders().enabled().first { it.name == "Rent" }
        assertEquals(today, RepeatEngine.nextAfter(rent, today.minusDays(1)))
        val milk = db.reminders().enabled().first { it.name == "Milk sub" }
        assertTrue(RenewalEngine.isExpired(RenewalEngine.currentCycle(milk, emptyList()), today))
    }
}
