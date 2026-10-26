package com.remind.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.room.withTransaction
import androidx.lifecycle.viewModelScope
import com.remind.app.backup.BackupSync
import com.remind.app.backup.DriveRestClient
import com.remind.app.backup.GoogleSignInBackup
import com.remind.app.backup.PhotosRestClient
import com.remind.app.data.*
import com.remind.app.domain.*
import com.remind.app.notify.Notifications
import com.remind.app.notify.Scheduler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class UiSettings(
    val theme: AppTheme = AppTheme.WHITE,
    val waterEnabled: Boolean = true,
    val waterInterval: Int = WaterPlan.DEFAULT_INTERVAL_MIN,
    val waterStart: Int = WaterPlan.DEFAULT_START_MIN,
    val waterEnd: Int = WaterPlan.DEFAULT_END_MIN,
    val alarmDefault: Boolean = false,
    val googleBackup: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app), RemindUi {
    private val repo = Repository.get(app)
    private val db get() = AppDatabase.get(getApplication())

    override val settings: StateFlow<UiSettings> = repo.settings.map { s ->
        UiSettings(
            theme = s["theme"]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: AppTheme.WHITE,
            waterEnabled = s["water_enabled"]?.toBooleanStrictOrNull() ?: true,
            waterInterval = s["water_interval"]?.toIntOrNull() ?: WaterPlan.DEFAULT_INTERVAL_MIN,
            waterStart = s["water_start"]?.toIntOrNull() ?: WaterPlan.DEFAULT_START_MIN,
            waterEnd = s["water_end"]?.toIntOrNull() ?: WaterPlan.DEFAULT_END_MIN,
            alarmDefault = s["alarm_default"]?.toBooleanStrictOrNull() ?: false,
            googleBackup = s["google_backup"]?.toBooleanStrictOrNull() ?: false
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiSettings())

    override val reminders = repo.reminders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<ReminderEntity>())
    override val memories = repo.memories.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<MemoryEntity>())
    override val planItems = repo.planItems.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<PlanItemEntity>())
    override val planChecks: StateFlow<Map<Long, Boolean>> =
        repo.planChecks(LocalDate.now()).map { list -> list.associate { it.itemId to it.done } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    override val waterCount: StateFlow<Int> =
        repo.water(LocalDate.now()).map { it?.count ?: 0 }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _renewals = MutableStateFlow<List<RenewalEntity>>(emptyList())
    override val renewals: StateFlow<List<RenewalEntity>> = _renewals

    private val _photoCopyFailed = MutableStateFlow(false)
    override val photoCopyFailed: StateFlow<Boolean> = _photoCopyFailed

    init {
        Notifications.createChannels(app)
        viewModelScope.launch {
            _renewals.value = db.renewals().all()
            Scheduler.rescheduleAll(app, repo)
            seedIfEmpty()
        }
    }

    private suspend fun seedIfEmpty() {
        if (db.plan().items().isEmpty()) {
            repo.savePlanItem(PlanItemEntity(name = "Gym", timeMinutes = 6 * 60 + 30, sort = 1))
            repo.savePlanItem(PlanItemEntity(name = "Mandir", timeMinutes = 8 * 60, sort = 2))
            repo.savePlanItem(PlanItemEntity(name = "Fruit", sort = 3))
            repo.savePlanItem(PlanItemEntity(name = "Water", isWater = true, sort = 4))
        }
    }

    override fun renewalCycleOf(r: ReminderEntity): Cycle? =
        if (r.type != ReminderType.EXPIRY) null
        else runCatching { RenewalEngine.currentCycle(r, _renewals.value.filter { it.reminderId == r.id }) }.getOrNull()

    override fun reminder(id: Long) = repo.reminder(id)
    override fun renewalsFor(id: Long) = repo.renewalsFor(id)

    // --- actions ---
    override fun saveReminder(r: ReminderEntity) {
        viewModelScope.launch {
        val id = repo.saveReminder(r)
        Scheduler.scheduleReminder(getApplication(), r.copy(id = if (r.id == 0L) id else r.id))
        com.remind.app.widget.RemindWidget.updateAll(getApplication())
    }
    }

    override fun deleteReminder(r: ReminderEntity) {
        viewModelScope.launch {
        Scheduler.cancel(getApplication(), Scheduler.KIND_REMINDER, r.id)
        repo.deleteReminder(r)
        com.remind.app.widget.RemindWidget.updateAll(getApplication())
    }
    }

    override fun renewReminder(r: ReminderEntity, periodMonths: Int?, periodDays: Int?, nextLeadDays: Int?) {
        viewModelScope.launch {
        repo.renew(RenewalEntity(reminderId = r.id, renewedAtEpochDay = LocalDate.now().toEpochDay(),
            periodMonths = periodMonths, periodDays = periodDays, nextLeadDays = nextLeadDays))
        _renewals.value = db.renewals().all()
        com.remind.app.widget.RemindWidget.updateAll(getApplication())
    }
    }

    override fun savePlanItem(p: PlanItemEntity) {
        viewModelScope.launch {
        val id = repo.savePlanItem(p)
        Scheduler.schedulePlanItem(getApplication(), p.copy(id = if (p.id == 0L) id else p.id))
        com.remind.app.widget.RemindWidget.updateAll(getApplication())
    }
    }

    override fun deletePlanItem(p: PlanItemEntity) {
        viewModelScope.launch {
        Scheduler.cancel(getApplication(), Scheduler.KIND_PLAN, p.id)
        repo.deletePlanItem(p)
        com.remind.app.widget.RemindWidget.updateAll(getApplication())
    }
    }

    override fun togglePlan(item: PlanItemEntity, done: Boolean) {
        viewModelScope.launch {
        repo.setPlanChecked(item.id, LocalDate.now(), done)
        com.remind.app.widget.RemindWidget.updateAll(getApplication())
    }
    }

    override fun addWater() {
        viewModelScope.launch {
        repo.addWater(LocalDate.now(), waterCount.value + 1)
        com.remind.app.widget.RemindWidget.updateAll(getApplication())
    }
    }

    override fun saveMemory(m: MemoryEntity) {
        viewModelScope.launch {
        _photoCopyFailed.value = false
        val wantedPhoto = m.photoUri != null
        val stored = m.copy(photoUri = copyPhotoToStorage(m.photoUri))
        // never persist an expiring content:// uri: on copy failure save without the photo
        // and tell the screen, instead of a photo that silently breaks after reboot
        if (wantedPhoto && stored.photoUri == null) _photoCopyFailed.value = true
        val id = repo.saveMemory(stored)
        Scheduler.scheduleMemory(getApplication(), if (stored.id == 0L) id else stored.id)
    }
    }

    override fun deleteMemory(m: MemoryEntity) {
        viewModelScope.launch {
        Scheduler.cancel(getApplication(), Scheduler.KIND_MEMORY, m.id)
        repo.deleteMemory(m)
    }
    }

    /** Copies a picked photo into app-private storage so it survives reboots
     *  and permission expiry; returns the stored path, or null on failure. */
    fun copyPhotoToStorage(uri: String?): String? {
        if (uri == null) return null
        if (!uri.startsWith("content://") && !uri.startsWith("file://")) return uri
        return runCatching {
            val dir = java.io.File(getApplication<Application>().filesDir, "memories").apply { mkdirs() }
            val out = java.io.File(dir, java.util.UUID.randomUUID().toString() + ".jpg")
            getApplication<Application>().contentResolver.openInputStream(android.net.Uri.parse(uri)).use { input ->
                requireNotNull(input) { "cannot open $uri" }
                out.outputStream().use { input.copyTo(it) }
            }
            android.net.Uri.fromFile(out).toString()
        }.getOrElse { null }
    }

    override fun setSetting(key: String, value: String) {
        viewModelScope.launch {
        repo.putSetting(key, value)
        if (key == "water_enabled" || key == "water_interval") {
            val s = settings.value
            if (s.waterEnabled) Scheduler.scheduleWater(getApplication(), s.waterInterval.toLong())
            else Scheduler.cancelWater(getApplication())
        }
    }
    }

    // ---- Google backup ----
    private val _backupState = MutableStateFlow(BackupUiState())
    override val backupState: StateFlow<BackupUiState> = _backupState
    private val googleSignIn by lazy { GoogleSignInBackup(getApplication()) }
    private val backupSync by lazy {
        val tokens = googleSignIn.tokenProvider()
        BackupSync(
            db = AppDatabase.get(getApplication()),
            drive = DriveRestClient(tokens),
            photos = PhotosRestClient(tokens),
            export = { exportJson() },
            import = { importJson(it) },
            memories = AppDatabase.get(getApplication()).memories(),
            settings = AppDatabase.get(getApplication()).settings(),
            photoBytes = { uri ->
                runCatching {
                    if (uri.startsWith("content://"))
                        getApplication<Application>().contentResolver.openInputStream(android.net.Uri.parse(uri))?.readBytes()
                    else java.io.File(uri).takeIf { it.exists() }?.readBytes()
                }.getOrNull()
            }
        )
    }

    override fun googleSignInIntent(): android.content.Intent = googleSignIn.signInIntent()

    override fun onGoogleSignInResult(data: android.content.Intent?) {
        viewModelScope.launch {
            val account = runCatching {
                com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(data).result
            }.getOrNull()
            if (account?.email != null) {
                _backupState.value = _backupState.value.copy(
                    signedInEmail = account.email, error = null, message = "Connected")
            } else {
                _backupState.value = _backupState.value.copy(error = "Google sign-in did not complete")
            }
        }
    }

    override fun backupNow() {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(busy = true, error = null, message = null)
            val r = backupSync.backupNow()
            val photos = if (r.ok) backupSync.syncPhotos() else null
            _backupState.value = BackupUiState(
                signedInEmail = _backupState.value.signedInEmail,
                busy = false,
                lastBackupMs = if (r.ok) System.currentTimeMillis() else _backupState.value.lastBackupMs,
                message = if (r.ok) {
                    val p = photos!!
                    "Backed up - ${p.uploaded} photo(s) uploaded" +
                        (if (p.failedTitles.isNotEmpty()) ", ${p.failedTitles.size} photo(s) failed: ${p.failedTitles.joinToString()}" else "")
                } else null,
                error = if (r.ok) null else "Backup failed: ${r.error}"
            )
        }
    }

    override fun restoreFromGoogle() {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(busy = true, error = null, message = null)
            val r = backupSync.restoreLatest()
            val photos = if (r.ok) backupSync.restorePhotos(
                photoExists = { uri -> java.io.File(uri).exists() },
                writePhoto = { id, bytes ->
                    val f = java.io.File(getApplication<Application>().filesDir, "memory-photo-$id.jpg")
                    f.writeBytes(bytes); f.absolutePath
                }
            ) else null
            _backupState.value = _backupState.value.copy(
                busy = false,
                message = if (r.ok) (r.detail ?: "Restored") + (photos?.let {
                    if (it.failedTitles.isNotEmpty()) " - ${it.failedTitles.size} photo(s) not restored: ${it.failedTitles.joinToString()}" else ""
                } ?: "") else null,
                error = if (r.ok) null else "Restore failed: ${r.error}"
            )
        }
    }

    /** Full local export: every table as JSON (pre-Google-backup safety + migration route). */
    override suspend fun exportJson(): String {
        val db = AppDatabase.get(getApplication())
        val root = org.json.JSONObject()
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())
        fun Any?.put(o: org.json.JSONObject, k: String) { if (this != null) o.put(k, this) }
        val reminders = org.json.JSONArray()
        for (r in db.reminders().observeAll().first()) {
            val o = org.json.JSONObject()
            o.put("id", r.id); o.put("name", r.name); o.put("category", r.category); o.put("type", r.type.name)
            r.repeatUnit?.name.put(o, "repeatUnit"); o.put("repeatInterval", r.repeatInterval)
            r.dateEpochDay.put(o, "dateEpochDay"); r.anchorEpochDay.put(o, "anchorEpochDay")
            r.validityMonths.put(o, "validityMonths"); r.validityDays.put(o, "validityDays")
            o.put("leadDays", r.leadDays); r.timeMinutes.put(o, "timeMinutes")
            o.put("alarm", r.alarm); o.put("enabled", r.enabled); o.put("createdAt", r.createdAt)
            r.amount.put(o, "amount"); r.contactName.put(o, "contactName"); r.contactUri.put(o, "contactUri")
            reminders.put(o)
        }
        root.put("reminders", reminders)
        val renewals = org.json.JSONArray()
        for (n in db.renewals().all()) {
            val o = org.json.JSONObject()
            o.put("id", n.id); o.put("reminderId", n.reminderId); o.put("renewedAtEpochDay", n.renewedAtEpochDay)
            n.periodMonths.put(o, "periodMonths"); n.periodDays.put(o, "periodDays"); n.nextLeadDays.put(o, "nextLeadDays")
            renewals.put(o)
        }
        root.put("renewals", renewals)
        val items = org.json.JSONArray()
        for (pl in db.plan().items()) {
            val o = org.json.JSONObject()
            o.put("id", pl.id); o.put("name", pl.name); pl.timeMinutes.put(o, "timeMinutes")
            o.put("isWater", pl.isWater); o.put("sort", pl.sort)
            items.put(o)
        }
        root.put("planItems", items)
        val mems = org.json.JSONArray()
        for (m in db.memories().all()) {
            val o = org.json.JSONObject()
            o.put("id", m.id); o.put("title", m.title); o.put("dateEpochDay", m.dateEpochDay)
            m.note.put(o, "note"); m.photoUri.put(o, "photoUri"); m.googleMediaId.put(o, "googleMediaId")
            // embed the actual photo bytes so an uninstall/reinstall migration is lossless
            m.photoUri?.let { uri ->
                runCatching {
                    val path = android.net.Uri.parse(uri).path
                    val f = if (path != null) java.io.File(path) else null
                    if (f != null && f.exists() && f.length() <= 15_000_000) {
                        o.put("photoData", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
                    }
                }
            }
            mems.put(o)
        }
        root.put("memories", mems)
        val settings = org.json.JSONObject()
        for (st in db.settings().observeAll().first()) settings.put(st.key, st.value)
        root.put("settings", settings)
        return root.toString(2)
    }

    /** Import an export produced by exportJson(). Validates the whole backup -
     *  including every photo payload - BEFORE touching the database, then applies
     *  all rows in one transaction, so a bad file never leaves partial data. */
    override suspend fun importJson(text: String): ImportResult {
        val db = AppDatabase.get(getApplication())
        fun org.json.JSONObject.optLongOrNull(k: String) = if (has(k)) getLong(k) else null
        fun org.json.JSONObject.optIntOrNull(k: String) = if (has(k)) getInt(k) else null
        fun org.json.JSONObject.optStringOrNull(k: String) = if (has(k)) getString(k) else null
        fun org.json.JSONObject.optDoubleOrNull(k: String) = if (has(k)) getDouble(k) else null

        // Phase 1: parse and validate everything; no writes of any kind.
        val root = runCatching { org.json.JSONObject(text) }.getOrNull()
            ?: return ImportResult(false, error = "not a valid backup file")
        data class PendingMemory(val entity: MemoryEntity, val bytes: ByteArray?)
        val reminders = mutableListOf<ReminderEntity>()
        val renewals = mutableListOf<RenewalEntity>()
        val planItems = mutableListOf<PlanItemEntity>()
        val memories = mutableListOf<PendingMemory>()
        val settings = mutableListOf<SettingEntity>()
        var droppedPhotos = 0
        try {
            val rArr = root.getJSONArray("reminders")
            for (i in 0 until rArr.length()) {
                val o = rArr.getJSONObject(i)
                reminders.add(ReminderEntity(
                    id = o.getLong("id"), name = o.getString("name"), category = o.getString("category"),
                    type = ReminderType.valueOf(o.getString("type")),
                    repeatUnit = o.optStringOrNull("repeatUnit")?.let { RepeatUnit.valueOf(it) },
                    repeatInterval = o.optInt("repeatInterval"),
                    dateEpochDay = o.optLongOrNull("dateEpochDay"), anchorEpochDay = o.optLongOrNull("anchorEpochDay"),
                    validityMonths = o.optIntOrNull("validityMonths"), validityDays = o.optIntOrNull("validityDays"),
                    leadDays = o.optInt("leadDays"), timeMinutes = o.optIntOrNull("timeMinutes"),
                    alarm = o.optBoolean("alarm"), enabled = o.optBoolean("enabled", true),
                    createdAt = o.optLong("createdAt"), amount = o.optDoubleOrNull("amount"),
                    contactName = o.optStringOrNull("contactName"), contactUri = o.optStringOrNull("contactUri")))
            }
            root.optJSONArray("renewals")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    renewals.add(RenewalEntity(id = o.getLong("id"), reminderId = o.getLong("reminderId"),
                        renewedAtEpochDay = o.getLong("renewedAtEpochDay"), periodMonths = o.optIntOrNull("periodMonths"),
                        periodDays = o.optIntOrNull("periodDays"), nextLeadDays = o.optIntOrNull("nextLeadDays")))
                }
            }
            root.optJSONArray("planItems")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    planItems.add(PlanItemEntity(id = o.getLong("id"), name = o.getString("name"),
                        timeMinutes = o.optIntOrNull("timeMinutes"), isWater = o.optBoolean("isWater"), sort = o.optInt("sort")))
                }
            }
            root.optJSONArray("memories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val data = o.optStringOrNull("photoData")
                    val bytes: ByteArray? = if (data != null) {
                        runCatching { java.util.Base64.getDecoder().decode(data) }.getOrNull()
                            ?: return ImportResult(false, error = "photo data in the backup is corrupt - nothing was imported")
                    } else null
                    var photoUri = o.optStringOrNull("photoUri")
                    if (bytes == null && photoUri != null && !java.io.File(photoUri).exists()) {
                        // Old path is gone on this device (e.g. fresh install) and the backup
                        // carries no bytes: this photo cannot be restored. Counted, not silent.
                        photoUri = null
                        droppedPhotos++
                    }
                    memories.add(PendingMemory(MemoryEntity(id = o.getLong("id"), title = o.getString("title"),
                        dateEpochDay = o.getLong("dateEpochDay"), note = o.optStringOrNull("note"),
                        photoUri = photoUri, googleMediaId = o.optStringOrNull("googleMediaId")), bytes))
                }
            }
            root.optJSONObject("settings")?.let { so ->
                for (k in so.keys()) settings.add(SettingEntity(k, so.getString(k)))
            }
        } catch (e: Exception) {
            return ImportResult(false, error = "backup file is incomplete or corrupt - nothing was imported")
        }

        // Phase 2: write photo payloads to fresh app-storage paths; on any
        // failure remove what was written and leave the database untouched.
        val dir = java.io.File(getApplication<Application>().filesDir, "memories").apply { mkdirs() }
        val written = mutableListOf<java.io.File>()
        var importedPhotos = 0
        val finalMemories = mutableListOf<MemoryEntity>()
        for (pm in memories) {
            var entity = pm.entity
            if (pm.bytes != null) {
                val f = java.io.File(dir, "mem_${entity.id}_${System.currentTimeMillis()}.jpg")
                if (runCatching { f.writeBytes(pm.bytes) }.isFailure) {
                    written.forEach { it.delete() }
                    return ImportResult(false, error = "could not store restored photos - nothing was imported")
                }
                written.add(f)
                importedPhotos++
                entity = entity.copy(photoUri = f.absolutePath)
            }
            finalMemories.add(entity)
        }

        // Phase 3: apply all rows atomically; a mid-write failure rolls back.
        try {
            db.withTransaction {
                for (r in reminders) db.reminders().upsert(r)
                for (n in renewals) db.renewals().insert(n)
                for (pl in planItems) db.plan().upsert(pl)
                for (m in finalMemories) db.memories().upsert(m)
                for (st in settings) db.settings().put(st)
            }
        } catch (e: Exception) {
            written.forEach { it.delete() }
            return ImportResult(false, error = "could not write restored data - nothing was imported")
        }
        _renewals.value = db.renewals().all()
        runCatching { Scheduler.rescheduleAll(getApplication(), repo) }
        runCatching { com.remind.app.widget.RemindWidget.updateAll(getApplication()) }
        return ImportResult(true, importedPhotos = importedPhotos, droppedPhotos = droppedPhotos)
    }
}
