package com.remind.app.domain

import com.remind.app.data.ReminderEntity
import com.remind.app.data.ReminderType
import com.remind.app.data.RenewalEntity
import com.remind.app.data.RepeatUnit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One renewal cycle: the period covered by one purchase/renewal. */
data class Cycle(
    val startEpochDay: Long,
    val expiryEpochDay: Long,   // exclusive end = start + validity
    val leadDays: Int
)

object RenewalEngine {

    private fun expiryFrom(anchorEpochDay: Long, months: Int?, days: Int?): Long {
        val anchor = LocalDate.ofEpochDay(anchorEpochDay)
        return when {
            months != null -> anchor.plusMonths(months.toLong()).toEpochDay()
            days != null -> anchorEpochDay + days
            else -> throw IllegalArgumentException("validity months or days required")
        }
    }

    /** The cycle the user is currently in: latest renewal, else the original purchase. */
    fun currentCycle(reminder: ReminderEntity, renewals: List<RenewalEntity>): Cycle {
        val latest = renewals.filter { it.reminderId == reminder.id }.maxByOrNull { it.renewedAtEpochDay }
        return if (latest != null) {
            Cycle(
                startEpochDay = latest.renewedAtEpochDay,
                expiryEpochDay = expiryFrom(latest.renewedAtEpochDay, latest.periodMonths, latest.periodDays),
                leadDays = latest.nextLeadDays ?: reminder.leadDays
            )
        } else {
            val anchor = reminder.anchorEpochDay ?: throw IllegalArgumentException("anchor required")
            Cycle(anchor, expiryFrom(anchor, reminder.validityMonths, reminder.validityDays), reminder.leadDays)
        }
    }

    fun daysLeft(cycle: Cycle, today: LocalDate): Long =
        cycle.expiryEpochDay - today.toEpochDay()

    fun isExpired(cycle: Cycle, today: LocalDate): Boolean = daysLeft(cycle, today) < 0

    /**
     * Lead-up rule (user requirement): daily reminders start leadDays before expiry and
     * continue every day until the item is renewed - including after expiry (accepted proposal).
     */
    fun shouldRemindOn(cycle: Cycle, day: LocalDate): Boolean {
        val leadStart = cycle.expiryEpochDay - cycle.leadDays
        return day.toEpochDay() >= leadStart
    }

    /** Next expiry after renewing on [renewedOn] for [periodMonths]/[periodDays]. */
    fun renewedCycle(reminder: ReminderEntity, renewedOn: LocalDate, periodMonths: Int?, periodDays: Int?, nextLeadDays: Int?): Cycle {
        val anchor = renewedOn.toEpochDay()
        return Cycle(anchor, expiryFrom(anchor, periodMonths, periodDays), nextLeadDays ?: reminder.leadDays)
    }
}

object RepeatEngine {

    /** Next fire date strictly after [after] for a repeat reminder. */
    fun nextAfter(reminder: ReminderEntity, after: LocalDate): LocalDate? {
        return when (reminder.type) {
        ReminderType.DATE -> reminder.dateEpochDay?.let(LocalDate::ofEpochDay)?.takeIf { it.isAfter(after) }
        ReminderType.EXPIRY -> null // handled by RenewalEngine
        ReminderType.REPEAT -> {
            val unit = reminder.repeatUnit ?: return null
            val anchor = (reminder.dateEpochDay ?: reminder.anchorEpochDay
                ?: reminder.createdAt).let(LocalDate::ofEpochDay)
            var next = anchor
            val step = reminder.repeatInterval.coerceAtLeast(1).toLong()
            while (!next.isAfter(after)) {
                next = when (unit) {
                    RepeatUnit.DAY -> next.plusDays(step)
                    RepeatUnit.WEEK -> next.plusWeeks(step)
                    RepeatUnit.MONTH -> next.plusMonths(step)
                    RepeatUnit.YEAR -> next.plusYears(step)
                }
            }
            next
        }
        }
    }

    /** Up to [count] upcoming fire dates after [after]. */
    fun upcoming(reminder: ReminderEntity, after: LocalDate, count: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>(count)
        var cursor = after
        repeat(count) {
            val n = nextAfter(reminder, cursor) ?: return out
            out += n; cursor = n
        }
        return out
    }
}

object WaterPlan {
    const val DEFAULT_INTERVAL_MIN = 30
    const val DEFAULT_START_MIN = 7 * 60
    const val DEFAULT_END_MIN = 22 * 60

    /** Length of the active window in minutes; supports windows spanning midnight. */
    fun windowMinutes(startMin: Int, endMin: Int): Int {
        val w = (endMin - startMin + 1440) % 1440
        require(w > 0) { "active window must not be empty" }
        return w
    }

    /** Number of water slots for one day given window + interval. */
    fun dailySlots(startMin: Int, endMin: Int, intervalMin: Int): Int {
        require(intervalMin > 0)
        return windowMinutes(startMin, endMin) / intervalMin
    }

    /** True when [minuteOfDay] is inside the active water window (quiet hours are outside). */
    fun inWindow(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean =
        if (startMin < endMin) minuteOfDay in startMin until endMin
        else minuteOfDay >= startMin || minuteOfDay < endMin

    /** Slots completed by [minuteOfDay] (for progress display). */
    fun slotsElapsed(minuteOfDay: Int, startMin: Int, endMin: Int, intervalMin: Int): Int {
        if (!inWindow(minuteOfDay, startMin, endMin))
            return if (startMin < endMin && minuteOfDay >= endMin) dailySlots(startMin, endMin, intervalMin) else 0
        val elapsed = (minuteOfDay - startMin + 1440) % 1440
        return elapsed / intervalMin
    }
}

object MemoryEngine {
    /** Next yearly notification date for a memory, strictly on-or-after [today]. */
    fun nextAnniversary(memoryDate: LocalDate, today: LocalDate): LocalDate {
        var next = memoryDate.withYear(today.year)
        if (next.isBefore(today)) next = next.plusYears(1)
        return next
    }

    fun yearsAgo(memoryDate: LocalDate, on: LocalDate): Int =
        ChronoUnit.YEARS.between(memoryDate, on).toInt().coerceAtLeast(0)
}
