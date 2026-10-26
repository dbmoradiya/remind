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

/** Refresh data path: after an action runs (as the launcher dispatch would run it),
 *  the data the next composition loads (loadData, the exact provideGlance source)
 *  must reflect the change. This closes the app-side loop action -> DB -> reload
 *  without claiming anything about launcher PendingIntent delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRefreshTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val db by lazy { AppDatabase.get(ctx) }
    private val fakeId = object : GlanceId { override fun toString() = "fake" }
    private val today = LocalDate.now().toEpochDay()

    @Before
    fun reset() = runBlocking { withContext(Dispatchers.IO) { db.clearAllTables() } }

    @Test fun toggle_then_reload_shows_check() = runBlocking {
        val id = db.plan().upsert(PlanItemEntity(name = "Gym", timeMinutes = 390, sort = 0))
        val before = RemindWidget().loadData(ctx)
        assertEquals(false, before.habits.first { it.first.id == id }.second)
        PlanToggleAction().onAction(ctx, fakeId, actionParametersOf(PlanToggleAction.ItemIdKey to id))
        val after = RemindWidget().loadData(ctx)
        assertEquals(true, after.habits.first { it.first.id == id }.second)
        PlanToggleAction().onAction(ctx, fakeId, actionParametersOf(PlanToggleAction.ItemIdKey to id))
        assertEquals(false, RemindWidget().loadData(ctx).habits.first { it.first.id == id }.second)
    }

    @Test fun water_tap_then_reload_shows_count_and_done_state() = runBlocking {
        db.plan().upsert(PlanItemEntity(name = "Water", isWater = true, sort = 4))
        // default window 7:00-22:00 at 30min = 30 slots; seed one short of the goal
        db.water().put(WaterLogEntity(today, 29))
        val before = RemindWidget().loadData(ctx)
        assertEquals(29, before.waterCount)
        assertNotEquals("done", before.waterStatus)
        WaterIncrementAction().onAction(ctx, fakeId, actionParametersOf())
        val after = RemindWidget().loadData(ctx)
        assertEquals(30, after.waterCount)
        assertEquals("done", after.waterStatus)
    }

    @Test fun toggle_with_missing_item_id_is_noop() = runBlocking {
        db.plan().upsert(PlanItemEntity(name = "Gym", sort = 0))
        // corrupt/empty PendingIntent extras must not crash or corrupt state
        PlanToggleAction().onAction(ctx, fakeId, actionParametersOf())
        assertTrue(db.plan().checks(today).isEmpty())
    }

    @Test fun toggle_with_unknown_item_id_writes_only_that_check() = runBlocking {
        // stale widget row (item deleted) taps still land on the id alone
        PlanToggleAction().onAction(ctx, fakeId, actionParametersOf(PlanToggleAction.ItemIdKey to 999L))
        val checks = db.plan().checks(today)
        assertEquals(1, checks.size)
        assertEquals(999L, checks[0].itemId)
        assertTrue(checks[0].done)
    }
}
