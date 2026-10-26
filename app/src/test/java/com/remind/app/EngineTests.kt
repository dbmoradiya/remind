package com.remind.app

import com.remind.app.data.*
import com.remind.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RenewalEngineTest {
    private val insurance = ReminderEntity(
        id = 1, name = "Car insurance", category = "Vehicle", type = ReminderType.EXPIRY,
        anchorEpochDay = LocalDate.of(2026, 1, 15).toEpochDay(),
        validityMonths = 12, leadDays = 15, alarm = true
    )
    private val t = LocalDate.of(2026, 6, 1)

    @Test fun `expiry is anchor plus validity months`() {
        val c = RenewalEngine.currentCycle(insurance, emptyList())
        assertEquals(LocalDate.of(2027, 1, 15).toEpochDay(), c.expiryEpochDay)
    }

    @Test fun `custom validity days supported`() {
        val r = insurance.copy(validityMonths = null, validityDays = 180)
        val c = RenewalEngine.currentCycle(r, emptyList())
        assertEquals(LocalDate.of(2026, 1, 15).toEpochDay() + 180, c.expiryEpochDay)
    }

    @Test fun `no reminder before lead window`() {
        val c = RenewalEngine.currentCycle(insurance, emptyList())
        // expiry 15 Jan 2027, lead 15 days -> start 31 Dec 2026
        assertFalse(RenewalEngine.shouldRemindOn(c, LocalDate.of(2026, 12, 30)))
        assertTrue(RenewalEngine.shouldRemindOn(c, LocalDate.of(2026, 12, 31)))
    }

    @Test fun `daily reminders through expiry`() {
        val c = RenewalEngine.currentCycle(insurance, emptyList())
        assertTrue(RenewalEngine.shouldRemindOn(c, LocalDate.of(2027, 1, 1)))
        assertTrue(RenewalEngine.shouldRemindOn(c, LocalDate.of(2027, 1, 15)))
    }

    @Test fun `keeps reminding after expiry until renewed`() {
        val c = RenewalEngine.currentCycle(insurance, emptyList())
        assertTrue(RenewalEngine.isExpired(c, LocalDate.of(2027, 2, 1)))
        assertTrue(RenewalEngine.shouldRemindOn(c, LocalDate.of(2027, 2, 1)))
        assertEquals(-17, RenewalEngine.daysLeft(c, LocalDate.of(2027, 2, 1)))
    }

    @Test fun `renewal starts a fresh cycle with new lead days`() {
        val renewal = RenewalEntity(reminderId = 1,
            renewedAtEpochDay = LocalDate.of(2027, 1, 10).toEpochDay(),
            periodMonths = 12, nextLeadDays = 30)
        val c = RenewalEngine.currentCycle(insurance, listOf(renewal))
        assertEquals(LocalDate.of(2028, 1, 10).toEpochDay(), c.expiryEpochDay)
        assertEquals(30, c.leadDays)
        // no reminders in the quiet middle of the new cycle
        assertFalse(RenewalEngine.shouldRemindOn(c, LocalDate.of(2027, 6, 1)))
        // but inside new lead window
        assertTrue(RenewalEngine.shouldRemindOn(c, LocalDate.of(2027, 12, 20)))
    }

    @Test fun `latest renewal wins`() {
        val old = RenewalEntity(reminderId = 1, renewedAtEpochDay = LocalDate.of(2027, 1, 10).toEpochDay(), periodMonths = 12)
        val recent = RenewalEntity(reminderId = 1, renewedAtEpochDay = LocalDate.of(2028, 1, 5).toEpochDay(), periodMonths = 6)
        val c = RenewalEngine.currentCycle(insurance, listOf(old, recent))
        assertEquals(LocalDate.of(2028, 7, 5).toEpochDay(), c.expiryEpochDay)
        assertEquals(15, c.leadDays) // falls back to reminder default
    }

    @Test fun `renewedCycle computes from renewal date`() {
        val c = RenewalEngine.renewedCycle(insurance, LocalDate.of(2027, 1, 10), 12, null, null)
        assertEquals(LocalDate.of(2028, 1, 10).toEpochDay(), c.expiryEpochDay)
        assertEquals(15, c.leadDays)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing validity is rejected`() {
        RenewalEngine.currentCycle(insurance.copy(validityMonths = null, validityDays = null), emptyList())
    }
}

class RepeatEngineTest {
    private fun repeat(unit: RepeatUnit, anchor: LocalDate, interval: Int = 1) = ReminderEntity(
        id = 2, name = "Test", category = "Other", type = ReminderType.REPEAT,
        repeatUnit = unit, repeatInterval = interval, anchorEpochDay = anchor.toEpochDay(),
        createdAt = anchor.toEpochDay()
    )
    private val t = LocalDate.of(2026, 10, 24)

    @Test fun `daily next day`() {
        val r = repeat(RepeatUnit.DAY, LocalDate.of(2026, 10, 1))
        assertEquals(t.plusDays(1), RepeatEngine.nextAfter(r, t))
    }

    @Test fun `weekly lands on same weekday`() {
        val r = repeat(RepeatUnit.WEEK, LocalDate.of(2026, 10, 2)) // Friday
        val n = RepeatEngine.nextAfter(r, t)!!
        assertEquals(java.time.DayOfWeek.FRIDAY, n.dayOfWeek)
        assertTrue(n.isAfter(t))
    }

    @Test fun `monthly clamps to month end`() {
        val r = repeat(RepeatUnit.MONTH, LocalDate.of(2026, 1, 31))
        val n = RepeatEngine.nextAfter(r, LocalDate.of(2026, 2, 15))
        assertEquals(LocalDate.of(2026, 2, 28), n)
    }

    @Test fun `yearly anniversary`() {
        val r = repeat(RepeatUnit.YEAR, LocalDate.of(2025, 2, 14))
        assertEquals(LocalDate.of(2027, 2, 14), RepeatEngine.nextAfter(r, t))
    }

    @Test fun `every third day`() {
        val r = repeat(RepeatUnit.DAY, LocalDate.of(2026, 10, 22), interval = 3)
        assertEquals(LocalDate.of(2026, 10, 25), RepeatEngine.nextAfter(r, t))
    }

    @Test fun `specific date fires once`() {
        val once = ReminderEntity(id = 3, name = "Once", category = "Other", type = ReminderType.DATE,
            dateEpochDay = t.plusDays(5).toEpochDay(), createdAt = t.toEpochDay())
        assertEquals(t.plusDays(5), RepeatEngine.nextAfter(once, t))
        assertNull(RepeatEngine.nextAfter(once, t.plusDays(5)))
    }

    @Test fun `expiry reminders are not repeat-scheduled`() {
        val expiry = ReminderEntity(id = 4, name = "E", category = "V", type = ReminderType.EXPIRY,
            anchorEpochDay = t.toEpochDay(), validityMonths = 12)
        assertNull(RepeatEngine.nextAfter(expiry, t))
    }

    @Test fun `upcoming returns N dates in order`() {
        val r = repeat(RepeatUnit.DAY, t)
        val ups = RepeatEngine.upcoming(r, t, 3)
        assertEquals(listOf(t.plusDays(1), t.plusDays(2), t.plusDays(3)), ups)
    }
}

class WaterPlanTest {
    @Test fun `daily slots divide the window`() {
        assertEquals(30, WaterPlan.dailySlots(7 * 60, 22 * 60, 30))
        assertEquals(15, WaterPlan.dailySlots(7 * 60, 22 * 60, 60))
    }

    @Test fun `window boundaries`() {
        assertTrue(WaterPlan.inWindow(7 * 60, 7 * 60, 22 * 60))
        assertTrue(WaterPlan.inWindow(21 * 60 + 59, 7 * 60, 22 * 60))
        assertFalse(WaterPlan.inWindow(22 * 60, 7 * 60, 22 * 60)) // quiet hours start
        assertFalse(WaterPlan.inWindow(3 * 60, 7 * 60, 22 * 60))  // night
    }

    @Test fun `slots elapsed for progress`() {
        assertEquals(0, WaterPlan.slotsElapsed(6 * 60, 7 * 60, 22 * 60, 30))
        assertEquals(6, WaterPlan.slotsElapsed(10 * 60, 7 * 60, 22 * 60, 30))
        assertEquals(30, WaterPlan.slotsElapsed(23 * 60, 7 * 60, 22 * 60, 30))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero interval rejected`() { WaterPlan.dailySlots(7 * 60, 22 * 60, 0) }

    @Test fun `window spanning midnight`() {
        // active 22:00 -> 07:00 = 9 hours = 18 slots at 30 min
        assertEquals(18, WaterPlan.dailySlots(22 * 60, 7 * 60, 30))
        assertTrue(WaterPlan.inWindow(23 * 60, 22 * 60, 7 * 60))
        assertTrue(WaterPlan.inWindow(3 * 60, 22 * 60, 7 * 60))
        assertFalse(WaterPlan.inWindow(12 * 60, 22 * 60, 7 * 60))
        assertEquals(6, WaterPlan.slotsElapsed(1 * 60, 22 * 60, 7 * 60, 30))
    }

    @Test fun `empty window rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WaterPlan.dailySlots(7 * 60, 7 * 60, 30) }
    }
}

class MemoryEngineTest {
    @Test fun `anniversary today`() {
        val mem = LocalDate.of(2024, 10, 24)
        assertEquals(LocalDate.of(2026, 10, 24), MemoryEngine.nextAnniversary(mem, LocalDate.of(2026, 10, 24)))
    }

    @Test fun `anniversary rolls to next year when passed`() {
        val mem = LocalDate.of(2024, 3, 12)
        assertEquals(LocalDate.of(2027, 3, 12), MemoryEngine.nextAnniversary(mem, LocalDate.of(2026, 10, 24)))
    }

    @Test fun `anniversary later this year stays`() {
        val mem = LocalDate.of(2024, 12, 25)
        assertEquals(LocalDate.of(2026, 12, 25), MemoryEngine.nextAnniversary(mem, LocalDate.of(2026, 10, 24)))
    }

    @Test fun `feb 29 memory handled`() {
        val mem = LocalDate.of(2024, 2, 29)
        val n = MemoryEngine.nextAnniversary(mem, LocalDate.of(2026, 10, 24))
        assertEquals(2027, n.year) // 2027 has no Feb 29 -> withYear clamps to Feb 28, no crash
    }

    @Test fun `years ago`() {
        assertEquals(2, MemoryEngine.yearsAgo(LocalDate.of(2024, 3, 12), LocalDate.of(2026, 10, 24)))
        assertEquals(0, MemoryEngine.yearsAgo(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 10, 24)))
    }
}
