package com.remind.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.components.CircleIconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.LocalContext
import com.remind.app.MainActivity
import com.remind.app.R
import com.remind.app.data.AppDatabase
import com.remind.app.data.PlanItemEntity
import com.remind.app.data.ReminderType
import com.remind.app.domain.RenewalEngine
import com.remind.app.domain.RepeatEngine
import com.remind.app.domain.WaterPlan
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Today-home-screen widget: date, plan habits with 48dp tap targets, water with a
 *  working tap-to-log row. Data and next-water text come from the real plan math
 *  (WaterPlan slots over the configured window), never an invented clock formula. */
class RemindWidget : GlanceAppWidget() {

    companion object {
        /** Static-style refresh used by ViewModel/receivers after data changes. */
        suspend fun updateAll(context: Context) = RemindWidget().updateAll(context)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Data-load failures show fallback content; receiver/session failures are separate.
        val data = runCatching { loadData(context) }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            try {
                AppDatabase.get(context).settings().put(
                    com.remind.app.data.SettingEntity("widget_last_error",
                        "${e.javaClass.simpleName}: ${e.message?.replace('\n', ' ')?.take(300)}"))
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) { }
            WidgetData(
                todayLabel = LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM")),
                habits = emptyList(), hasWater = false, waterCount = 0, waterTotal = 0,
                waterStatus = "", upcoming = emptyList(), loadError = true,
            )
        }
        provideContent { Content(data) }
    }

    internal data class WidgetData(
        val todayLabel: String,
        val habits: List<Pair<PlanItemEntity, Boolean>>,
        val hasWater: Boolean,
        val waterCount: Int,
        val waterTotal: Int,
        val waterStatus: String,
        val upcoming: List<String>,
        val loadError: Boolean = false,
    )

    internal suspend fun loadData(context: Context): WidgetData {
        val db = AppDatabase.get(context)
        val today = LocalDate.now()
        val epoch = today.toEpochDay()
        val items = db.plan().items()
        val checks = db.plan().checks(epoch).associate { it.itemId to it.done }
        val habits = items.filter { !it.isWater }.sortedBy { it.sort }.take(4)
            .map { it to (checks[it.id] ?: false) }
        val hasWater = items.any { it.isWater }
        val intervalRaw = db.settings().get("water_interval")?.toIntOrNull() ?: WaterPlan.DEFAULT_INTERVAL_MIN
        val startRaw = db.settings().get("water_start")?.toIntOrNull() ?: WaterPlan.DEFAULT_START_MIN
        val endRaw = db.settings().get("water_end")?.toIntOrNull() ?: WaterPlan.DEFAULT_END_MIN
        // dailySlots/slotsElapsed require interval>0 and a non-empty window; a corrupt
        // or half-written setting must degrade to defaults, never blank the widget.
        val intervalMin = if (intervalRaw > 0) intervalRaw else WaterPlan.DEFAULT_INTERVAL_MIN
        val (waterStart, waterEnd) =
            if (startRaw != endRaw) startRaw to endRaw
            else WaterPlan.DEFAULT_START_MIN to WaterPlan.DEFAULT_END_MIN
        val total = WaterPlan.dailySlots(waterStart, waterEnd, intervalMin)
        val waterCount = db.water().get(epoch)?.count ?: 0
        // Grounded in the app's slot model: WaterPlan defines slots as
        // start + k*interval inside the window; the periodic WaterWorker logs a
        // slot each fire. Next boundary = start + (elapsed+1)*interval.
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val waterStatus = when {
            !hasWater -> ""
            waterCount >= total -> "done"
            !WaterPlan.inWindow(nowMin, waterStart, waterEnd) -> "from ${fmt12(waterStart)}"
            else -> {
                val next = (waterStart +
                    (WaterPlan.slotsElapsed(nowMin, waterStart, waterEnd, intervalMin) + 1) * intervalMin) % 1440
                "next ~${fmt12(next)}"
            }
        }
        // Same upcoming semantics as the app's Today screen: every reminder kind
        // (REPEAT next occurrence via RepeatEngine, DATE one-off, EXPIRY current
        // renewal cycle via RenewalEngine), chronological, overdue kept 30 days.
        val reminders = db.reminders().enabled()
        val renewals = db.renewals().all()
        val upcoming = reminders.mapNotNull { r ->
            when (r.type) {
                ReminderType.EXPIRY -> runCatching {
                    RenewalEngine.currentCycle(r, renewals.filter { it.reminderId == r.id })
                }.getOrNull()?.let { c -> r to RenewalEngine.daysLeft(c, today) }
                else -> RepeatEngine.nextAfter(r, today.minusDays(1))
                    ?.let { d -> r to (d.toEpochDay() - epoch) }
            }
        }.filter { it.second >= -30 }.sortedBy { it.second }.take(3).map { (r, days) ->
            val when_ = when {
                days < 0 -> "overdue ${-days}d"
                days == 0L -> "today"
                days == 1L -> "tomorrow"
                else -> LocalDate.ofEpochDay(epoch + days).format(DateTimeFormatter.ofPattern("d MMM"))
            }
            val time = r.timeMinutes?.let { ", " + fmt12(it) } ?: ""
            "• ${r.name} · $when_$time"
        }
        return WidgetData(
            todayLabel = today.format(DateTimeFormatter.ofPattern("EEE, d MMM")),
            habits = habits, hasWater = hasWater,
            waterCount = waterCount, waterTotal = total, waterStatus = waterStatus,
            upcoming = upcoming,
        )
    }

    @Composable
    internal fun Content(d: WidgetData) {
        val ctx = LocalContext.current
        val open = actionStartActivity(android.content.ComponentName(ctx, MainActivity::class.java))
        val bg = GlanceTheme.colors.surface
        val ink = GlanceTheme.colors.onSurface
        val ink2 = GlanceTheme.colors.onSurfaceVariant
        // Responsive tiers fitted to the ACTUAL LocalSize height (no reliance on
        // launcher cell mapping). All rows are fixed-height and all texts are
        // maxLines=1, so the arithmetic holds under font scaling too (extreme
        // scales may clip glyphs inside a fixed row but can never push the
        // layout taller). Totals incl. 16dp outer padding + 26dp header:
        //   210-299dp: 2 habits + water + 1 upcoming = 206dp (4dp slack)
        //   300-359dp: 3 habits + water + 3 upcoming = 294dp (6dp slack)
        //   >=360dp:   4 habits + water + 3 upcoming + footer = 356dp (4dp slack)
        // Upcoming reminders appear at EVERY supported size - only the count adapts.
        val h = androidx.glance.LocalSize.current.height
        val habitsShown = if (h >= 360.dp) 4 else if (h >= 300.dp) 3 else 2
        val upcomingShown = if (h >= 300.dp) 3 else 1
        val showFooter = h >= 360.dp
        Column(
            GlanceModifier.fillMaxSize().cornerRadius(22.dp).background(bg)
                .clickable(open).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(GlanceModifier.fillMaxWidth().height(26.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Remind", style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                Text(d.todayLabel, style = TextStyle(color = ink2, fontSize = 12.sp), maxLines = 1)
            }
            if (d.loadError) {
                Text("Could not load - tap to open Remind",
                    GlanceModifier.fillMaxWidth().height(48.dp).clickable(open),
                    style = TextStyle(color = ink, fontSize = 14.sp), maxLines = 1)
                return@Column
            }
            d.habits.take(habitsShown).forEach { (item, done) -> HabitRow(item, done, ink, ink2) }
            if (d.hasWater) WaterRow(d, ink, ink2)
            d.upcoming.take(upcomingShown).forEach { line ->
                Row(GlanceModifier.fillMaxWidth().height(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(line, style = TextStyle(color = ink2, fontSize = 11.sp), maxLines = 1)
                }
            }
            if (showFooter) {
                Spacer(GlanceModifier.defaultWeight())
                Text("Tap to open Remind", GlanceModifier.fillMaxWidth().height(14.dp),
                    style = TextStyle(color = ink2, fontSize = 10.sp,
                        textAlign = androidx.glance.text.TextAlign.Center), maxLines = 1)
            }
        }
    }

    @Composable
    private fun HabitRow(item: PlanItemEntity, done: Boolean,
                         ink: ColorProvider, ink2: ColorProvider) {
        // 48dp row: full-width tap target per Android touch-target guidance.
        Row(
            GlanceModifier.fillMaxWidth().height(48.dp)
                .clickable(actionRunCallback<PlanToggleAction>(
                    actionParametersOf(PlanToggleAction.ItemIdKey to item.id))),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (done) "☑" else "☐", style = TextStyle(color = ink, fontSize = 18.sp))
            Spacer(GlanceModifier.width(10.dp))
            Text(item.name, style = TextStyle(color = ink, fontSize = 14.sp,
                fontWeight = if (done) FontWeight.Normal else FontWeight.Medium), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            Text(item.timeMinutes?.let { fmt12(it) } ?: "Any time",
                style = TextStyle(color = ink2, fontSize = 12.sp), maxLines = 1)
        }
    }

    @Composable
    private fun WaterRow(d: WidgetData, ink: ColorProvider, ink2: ColorProvider) {
        Row(
            GlanceModifier.fillMaxWidth().height(48.dp)
                .clickable(actionRunCallback<WaterIncrementAction>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("◌", style = TextStyle(color = ink, fontSize = 18.sp))
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text("Water · ${d.waterCount}/${d.waterTotal}",
                    style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                if (d.waterStatus.isNotEmpty()) {
                    Text(d.waterStatus, style = TextStyle(color = ink2, fontSize = 11.sp), maxLines = 1)
                }
            }
            Text("+ log", style = TextStyle(color = ink2, fontSize = 12.sp), maxLines = 1)
        }
    }
}

class PlanToggleAction : ActionCallback {
    companion object { val ItemIdKey = androidx.glance.action.ActionParameters.Key<Long>("itemId") }
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        try {
            val id = parameters[ItemIdKey] ?: return
            val db = AppDatabase.get(context)
            val today = LocalDate.now().toEpochDay()
            val cur = db.plan().checks(today).firstOrNull { it.itemId == id }?.done ?: false
            db.plan().setCheck(com.remind.app.data.PlanCheckEntity(id, today, !cur))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { }
        RemindWidget().updateAll(context)
    }
}

class WaterIncrementAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        try {
            val db = AppDatabase.get(context)
            val today = LocalDate.now().toEpochDay()
            val cur = db.water().get(today)?.count ?: 0
            // Matches the app's + button semantics (AppViewModel.addWater has no cap);
            // the automatic worker stops at the daily total on its own.
            db.water().put(com.remind.app.data.WaterLogEntity(today, cur + 1))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { }
        RemindWidget().updateAll(context)
    }
}

private fun fmt12(min: Int): String {
    val h = min / 60; val m = min % 60
    val ap = if (h < 12) "AM" else "PM"
    val hh = when (val t = h % 12) { 0 -> 12; else -> t }
    return "%d:%02d %s".format(hh, m, ap)
}
