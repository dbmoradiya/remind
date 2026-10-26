package com.remind.app.widget

import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.test.core.app.ApplicationProvider
import com.remind.app.data.AppDatabase
import com.remind.app.data.PlanItemEntity
import com.remind.app.data.WaterLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** WaterIncrementAction against the real Room DB. Every onAction also runs
 *  RemindWidget().updateAll(context) with zero registered widgets, so the
 *  refresh path's no-widget behaviour is exercised by every test here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetWaterActionTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val db by lazy { AppDatabase.get(ctx) }
    private val fakeId = object : GlanceId { override fun toString() = "fake" }
    private val today = LocalDate.now().toEpochDay()

    @Before
    fun reset() = runBlocking { withContext(Dispatchers.IO) { db.clearAllTables() } }

    @Test fun increment_from_zero_creates_today_row() = runBlocking {
        WaterIncrementAction().onAction(ctx, fakeId, actionParametersOf())
        assertEquals(1, db.water().get(today)?.count)
    }

    @Test fun increment_adds_to_existing_count() = runBlocking {
        db.water().put(WaterLogEntity(today, 7))
        WaterIncrementAction().onAction(ctx, fakeId, actionParametersOf())
        assertEquals(8, db.water().get(today)?.count)
    }

    @Test fun increment_leaves_other_dates_untouched() = runBlocking {
        val yesterday = today - 1
        db.water().put(WaterLogEntity(yesterday, 5))
        WaterIncrementAction().onAction(ctx, fakeId, actionParametersOf())
        assertEquals("yesterday must not change", 5, db.water().get(yesterday)?.count)
        assertEquals(1, db.water().get(today)?.count)
    }

    @Test fun over_total_allowed_matching_app_plus_button() = runBlocking {
        // The app's + button (AppViewModel.addWater) has no cap; only the automatic
        // WaterWorker stops at the daily total. The widget matches the app's button.
        db.water().put(WaterLogEntity(today, 30))
        WaterIncrementAction().onAction(ctx, fakeId, actionParametersOf())
        assertEquals(31, db.water().get(today)?.count)
    }

    @Test fun action_reresolves_date_on_each_invocation() = runBlocking {
        db.water().put(WaterLogEntity(today, 2))
        WaterIncrementAction().onAction(ctx, fakeId, actionParametersOf())
        assertEquals(3, db.water().get(today)?.count)
        assertNull("no row may be created for another day", db.water().get(today + 1))
    }

    @Test fun toggle_then_water_are_independent() = runBlocking {
        val id = db.plan().upsert(PlanItemEntity(name = "Gym", timeMinutes = 390, sort = 0))
        PlanToggleAction().onAction(ctx, fakeId, actionParametersOf(PlanToggleAction.ItemIdKey to id))
        WaterIncrementAction().onAction(ctx, fakeId, actionParametersOf())
        assertEquals(true, db.plan().checks(today).firstOrNull { it.itemId == id }?.done)
        assertEquals(1, db.water().get(today)?.count)
    }
}
