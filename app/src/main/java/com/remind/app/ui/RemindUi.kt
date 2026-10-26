package com.remind.app.ui

import android.content.Intent
import com.remind.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import com.remind.app.domain.Cycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Outcome of a backup import. ok=false means NOTHING was written. droppedPhotos
 *  counts memories whose photo could not be restored (no bytes in the backup and
 *  the old file path gone) - surfaced to the user, never silent. */
data class ImportResult(val ok: Boolean, val importedPhotos: Int = 0, val droppedPhotos: Int = 0, val error: String? = null)

/** UI contract used by all screens; AppViewModel implements it with Room,
 *  tests implement it with in-memory fakes so screens render without Android. */
interface RemindUi {
    val settings: StateFlow<UiSettings>
    val reminders: StateFlow<List<ReminderEntity>>
    val memories: StateFlow<List<MemoryEntity>>
    val planItems: StateFlow<List<PlanItemEntity>>
    val planChecks: StateFlow<Map<Long, Boolean>>
    val waterCount: StateFlow<Int>
    val renewals: StateFlow<List<RenewalEntity>>
    val photoCopyFailed: StateFlow<Boolean>
    fun renewalCycleOf(r: ReminderEntity): Cycle?
    fun reminder(id: Long): Flow<ReminderEntity?>
    fun renewalsFor(id: Long): Flow<List<RenewalEntity>>
    fun saveReminder(r: ReminderEntity)
    fun deleteReminder(r: ReminderEntity)
    fun renewReminder(r: ReminderEntity, periodMonths: Int?, periodDays: Int?, nextLeadDays: Int?)
    fun togglePlan(item: PlanItemEntity, done: Boolean)
    fun savePlanItem(p: PlanItemEntity)
    fun deletePlanItem(p: PlanItemEntity)
    fun addWater()
    fun saveMemory(m: MemoryEntity)
    fun deleteMemory(m: MemoryEntity)
    fun setSetting(key: String, value: String)
    suspend fun exportJson(): String = ""
    suspend fun importJson(text: String): ImportResult = ImportResult(false, error = "unsupported")

    // Google backup (live validation needs the user's Google sign-in; all
    // states below render honestly without it)
    val backupState: StateFlow<BackupUiState> get() = MutableStateFlow(BackupUiState())
    fun googleSignInIntent(): Intent? = null
    fun onGoogleSignInResult(data: Intent?) {}
    fun backupNow() {}
    fun restoreFromGoogle() {}
}

/** Google backup UI state. [error] and [message] are user-visible; [busy]
 *  disables the action row while a network op runs. */
data class BackupUiState(
    val signedInEmail: String? = null,
    val busy: Boolean = false,
    val lastBackupMs: Long? = null,
    val message: String? = null,
    val error: String? = null
)
