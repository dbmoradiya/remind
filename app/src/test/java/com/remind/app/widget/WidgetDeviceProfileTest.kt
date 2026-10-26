package com.remind.app.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.remind.app.data.AppDatabase
import com.remind.app.data.PlanItemEntity
import com.remind.app.data.SettingEntity
import com.remind.app.data.WaterLogEntity
import com.remind.app.domain.WaterPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Device-regression guard for "widget shows nothing" after 0.2.5: composition
 *  with a synthetic data profile, corrupt-settings resilience, and the
 *  never-blank visible fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetDeviceProfileTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val db by lazy { AppDatabase.get(ctx) }
    private val today = LocalDate.now()

    @Before
    fun reset() = runBlocking { withContext(Dispatchers.IO) { db.clearAllTables() } }

    @Test fun renders_synthetic_profile_at_large_size() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(320.dp, 360.dp))
        setContext(ctx)
        // Synthetic sample with timed/untimed habits and no upcoming reminders.
        runBlocking {
            db.plan().upsert(PlanItemEntity(name = "Walk", timeMinutes = 450, sort = 0))
            db.plan().upsert(PlanItemEntity(name = "Read", sort = 1))
            db.plan().upsert(PlanItemEntity(name = "Water", isWater = true, sort = 2))
            db.water().put(WaterLogEntity(today.toEpochDay(), 7))
            db.settings().put(SettingEntity("water_interval", "30"))
        }
        val data = runBlocking { RemindWidget().loadData(ctx) }
        provideComposable { RemindWidget().Content(data) }
        onNode(hasTextEqualTo("Remind")).assertExists()
        onNode(hasText("7:30 AM")).assertExists()
        onNode(hasText("Any time")).assertExists()
        onNode(hasText("Water · 7/30")).assertExists()
        onNode(hasText("Tap to open Remind")).assertExists()   // footer at large size
    }

    @Test fun corrupt_water_settings_degrade_to_defaults_not_blank() = runBlocking {
        // start == end would make WaterPlan.dailySlots require() throw -> old code
        // path risked a blank widget; now must sanitize to defaults.
        db.plan().upsert(PlanItemEntity(name = "Water", isWater = true, sort = 2))
        db.settings().put(SettingEntity("water_start", "1320"))
        db.settings().put(SettingEntity("water_end", "1320"))
        db.settings().put(SettingEntity("water_interval", "0"))
        val d = RemindWidget().loadData(ctx)
        assertEquals(WaterPlan.dailySlots(WaterPlan.DEFAULT_START_MIN,
            WaterPlan.DEFAULT_END_MIN, WaterPlan.DEFAULT_INTERVAL_MIN), d.waterTotal)
        assertFalse(d.loadError)
    }

    @Test fun load_error_renders_visible_fallback_not_blank() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(320.dp, 360.dp))
        setContext(ctx)
        val data = RemindWidget.WidgetData(
            todayLabel = "Sun, 25 Oct", habits = emptyList(), hasWater = false,
            waterCount = 0, waterTotal = 0, waterStatus = "", upcoming = emptyList(),
            loadError = true)
        provideComposable { RemindWidget().Content(data) }
        onNode(hasText("Could not load - tap to open Remind")).assertExists()
    }
}
