package com.remind.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.navigation.compose.rememberNavController
import com.remind.app.data.*
import com.remind.app.domain.Cycle
import com.remind.app.ui.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Renders every screen in every theme through Android's layoutlib so the
 *  actual UI pixels can be inspected without a device. */
class ScreenSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    private class FakeUi(theme: AppTheme = AppTheme.WHITE,
                         backup: com.remind.app.ui.BackupUiState = com.remind.app.ui.BackupUiState()) : RemindUi {
        override val backupState: StateFlow<com.remind.app.ui.BackupUiState> = MutableStateFlow(backup)
        private val today = LocalDate.now()
        val car = ReminderEntity(
            id = 1, name = "Car insurance", category = "Vehicle", type = ReminderType.EXPIRY,
            anchorEpochDay = today.minusMonths(11).toEpochDay(), validityMonths = 12,
            leadDays = 15, alarm = true, amount = 24000.0)
        val gym = ReminderEntity(
            id = 2, name = "Gym membership", category = "Bills", type = ReminderType.REPEAT,
            repeatUnit = RepeatUnit.MONTH, repeatInterval = 1, anchorEpochDay = today.toEpochDay(),
            createdAt = today.toEpochDay())
        val items = listOf(
            PlanItemEntity(id = 1, name = "Gym", timeMinutes = 6 * 60 + 30, sort = 1),
            PlanItemEntity(id = 2, name = "Stretch", timeMinutes = 8 * 60, sort = 2),
            PlanItemEntity(id = 3, name = "Fruit", sort = 3),
            PlanItemEntity(id = 4, name = "Water", isWater = true, sort = 4))
        val mems = listOf(
            MemoryEntity(id = 1, title = "Example trip", dateEpochDay = today.minusYears(1).toEpochDay(), note = "Beach week"),
            MemoryEntity(id = 2, title = "First car", dateEpochDay = today.minusYears(2).toEpochDay()))

        override val settings = MutableStateFlow(UiSettings(theme = theme))
        override val reminders: StateFlow<List<ReminderEntity>> = MutableStateFlow(listOf(car, gym))
        override val memories: StateFlow<List<MemoryEntity>> = MutableStateFlow(mems)
        override val planItems: StateFlow<List<PlanItemEntity>> = MutableStateFlow(items)
        override val planChecks: StateFlow<Map<Long, Boolean>> = MutableStateFlow(mapOf(1L to true))
        override val waterCount: StateFlow<Int> = MutableStateFlow(9)
        override val renewals: StateFlow<List<RenewalEntity>> = MutableStateFlow(emptyList())
        override val photoCopyFailed: StateFlow<Boolean> = MutableStateFlow(false)
        override fun renewalCycleOf(r: ReminderEntity): Cycle? =
            if (r.type != ReminderType.EXPIRY) null
            else Cycle(today.minusMonths(11).toEpochDay(), today.plusDays(21).toEpochDay(), 15)
        override fun reminder(id: Long): Flow<ReminderEntity?> = flowOf(if (id == 1L) car else gym)
        override fun renewalsFor(id: Long): Flow<List<RenewalEntity>> = flowOf(emptyList())
        override fun saveReminder(r: ReminderEntity) {}
        override fun deleteReminder(r: ReminderEntity) {}
        override fun renewReminder(r: ReminderEntity, periodMonths: Int?, periodDays: Int?, nextLeadDays: Int?) {}
        override fun togglePlan(item: PlanItemEntity, done: Boolean) {}
        override fun savePlanItem(p: PlanItemEntity) {}
        override fun deletePlanItem(p: PlanItemEntity) {}
        override fun addWater() {}
        override fun saveMemory(m: MemoryEntity) {}
        override fun deleteMemory(m: MemoryEntity) {}
        override fun setSetting(key: String, value: String) {}
    }

    private fun snap(name: String, theme: AppTheme, screen: @androidx.compose.runtime.Composable (androidx.navigation.NavHostController, RemindUi) -> Unit) {
        val vm = FakeUi(theme)
        paparazzi.snapshot(name = name) {
            RemindTheme(theme) {
                val nav = rememberNavController()
                screen(nav, vm)
            }
        }
    }

    @Test fun today_white() = snap("today_white", AppTheme.WHITE) { n, vm -> TodayScreen(n, vm) }
    @Test fun today_dark() = snap("today_dark", AppTheme.DARK) { n, vm -> TodayScreen(n, vm) }
    @Test fun today_black() = snap("today_black", AppTheme.BLACK) { n, vm -> TodayScreen(n, vm) }
    @Test fun reminders_white() = snap("reminders_white", AppTheme.WHITE) { n, vm -> RemindersScreen(n, vm) }
    @Test fun reminders_dark() = snap("reminders_dark", AppTheme.DARK) { n, vm -> RemindersScreen(n, vm) }
    @Test fun reminders_black() = snap("reminders_black", AppTheme.BLACK) { n, vm -> RemindersScreen(n, vm) }
    @Test fun add_white() = snap("add_white", AppTheme.WHITE) { n, vm -> AddReminderScreen(n, vm) }
    @Test fun add_dark() = snap("add_dark", AppTheme.DARK) { n, vm -> AddReminderScreen(n, vm) }
    @Test fun add_black() = snap("add_black", AppTheme.BLACK) { n, vm -> AddReminderScreen(n, vm) }
    @Test fun detail_white() = snap("detail_white", AppTheme.WHITE) { n, vm -> DetailScreen(n, vm, 1) }
    @Test fun detail_dark() = snap("detail_dark", AppTheme.DARK) { n, vm -> DetailScreen(n, vm, 1) }
    @Test fun detail_black() = snap("detail_black", AppTheme.BLACK) { n, vm -> DetailScreen(n, vm, 1) }
    @Test fun memories_white() = snap("memories_white", AppTheme.WHITE) { n, vm -> MemoriesScreen(n, vm) }
    @Test fun memories_dark() = snap("memories_dark", AppTheme.DARK) { n, vm -> MemoriesScreen(n, vm) }
    @Test fun memories_black() = snap("memories_black", AppTheme.BLACK) { n, vm -> MemoriesScreen(n, vm) }
    @Test fun addmemory_white() = snap("addmemory_white", AppTheme.WHITE) { n, vm -> AddMemoryScreen(n, vm) }
    @Test fun addmemory_dark() = snap("addmemory_dark", AppTheme.DARK) { n, vm -> AddMemoryScreen(n, vm) }
    @Test fun addmemory_black() = snap("addmemory_black", AppTheme.BLACK) { n, vm -> AddMemoryScreen(n, vm) }
    private fun snapWith(name: String, theme: AppTheme, vm: FakeUi, screen: @androidx.compose.runtime.Composable (androidx.navigation.NavHostController, RemindUi) -> Unit) {
        paparazzi.snapshot(name = name) {
            RemindTheme(theme) {
                val nav = rememberNavController()
                screen(nav, vm)
            }
        }
    }

    @Test fun settings_backup_connected() = snapWith("settings_backup_connected", AppTheme.WHITE,
        FakeUi(AppTheme.WHITE, com.remind.app.ui.BackupUiState(
            signedInEmail = "demo@example.com", lastBackupMs = 1_761_400_000_000L,
            message = "Backed up - 2 photo(s) uploaded"))) { n, vm -> SettingsScreen(n, vm) }
    @Test fun settings_backup_busy() = snapWith("settings_backup_busy", AppTheme.WHITE,
        FakeUi(AppTheme.WHITE, com.remind.app.ui.BackupUiState(
            signedInEmail = "demo@example.com", busy = true))) { n, vm -> SettingsScreen(n, vm) }
    @Test fun settings_backup_error() = snapWith("settings_backup_error", AppTheme.WHITE,
        FakeUi(AppTheme.WHITE, com.remind.app.ui.BackupUiState(
            signedInEmail = "demo@example.com",
            error = "Backup failed: HTTP 403: insufficient permissions"))) { n, vm -> SettingsScreen(n, vm) }
    @Test fun settings_backup_connected_black() = snapWith("settings_backup_connected_black", AppTheme.BLACK,
        FakeUi(AppTheme.BLACK, com.remind.app.ui.BackupUiState(
            signedInEmail = "demo@example.com", lastBackupMs = 1_761_400_000_000L,
            message = "Backed up - 2 photo(s) uploaded"))) { n, vm -> SettingsScreen(n, vm) }

    @Test fun settings_white() = snap("settings_white", AppTheme.WHITE) { n, vm -> SettingsScreen(n, vm) }
    @Test fun settings_dark() = snap("settings_dark", AppTheme.DARK) { n, vm -> SettingsScreen(n, vm) }
    @Test fun settings_black() = snap("settings_black", AppTheme.BLACK) { n, vm -> SettingsScreen(n, vm) }
    @Test fun plan_white() = snap("plan_white", AppTheme.WHITE) { n, vm -> ManagePlanScreen(n, vm) }
    @Test fun plan_dark() = snap("plan_dark", AppTheme.DARK) { n, vm -> ManagePlanScreen(n, vm) }
    @Test fun plan_black() = snap("plan_black", AppTheme.BLACK) { n, vm -> ManagePlanScreen(n, vm) }
}
