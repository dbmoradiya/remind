package com.remind.app.backup

import com.remind.app.ui.ImportResult
import androidx.room.withTransaction
import com.remind.app.data.AppDatabase
import com.remind.app.data.MemoryDao
import com.remind.app.data.PhotoUploadEntity
import com.remind.app.data.SettingEntity
import com.remind.app.data.SettingsDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Outcome of one Drive backup or restore. ok=false means nothing was applied;
 *  [error] is user-visible. Restore never writes partial data (import is
 *  two-phase and transactional). */
data class BackupOpResult(val ok: Boolean, val detail: String? = null, val error: String? = null)

/** Result of one photo sync pass. Partial failures are reported, never hidden:
 *  [failedTitles] names the memories whose photo did not upload this pass. */
data class PhotoSyncResult(val uploaded: Int, val alreadyBackedUp: Int, val failedTitles: List<String>)

/** Backup orchestration. Pure logic over injected seams so the whole flow is
 *  unit-tested with fake clients; the live HTTP/sign-in implementations are
 *  validated separately on device with real credentials. */
class BackupSync(
    private val drive: DriveClient,
    private val photos: PhotosClient?,
    private val export: suspend () -> String,
    private val import: suspend (String) -> ImportResult,
    private val db: AppDatabase,
    private val memories: MemoryDao,
    private val settings: SettingsDao,
    private val photoBytes: suspend (String) -> ByteArray?,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val BACKUP_PREFIX = "remind-backup-"
        const val KEY_LAST_BACKUP_MS = "last_backup_ms"
        fun backupName(atMs: Long): String {
            val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return BACKUP_PREFIX + fmt.format(Date(atMs)) + ".json"
        }
    }

    /** Upload a fresh timestamped backup of everything. NEVER deletes or prunes
     *  existing remote backups - retention is the user's choice, deferred. */
    suspend fun backupNow(): BackupOpResult { return try {
        val json = export()
        val name = backupName(nowMs())
        val id = drive.upload(name, json.toByteArray(Charsets.UTF_8), "application/json")
        settings.put(SettingEntity(KEY_LAST_BACKUP_MS, nowMs().toString()))
        BackupOpResult(true, detail = "saved as $name ($id)")
    } catch (e: Exception) {
        BackupOpResult(false, error = e.message ?: e.javaClass.simpleName)
    } }

    /** Restore from the newest remote backup. A corrupt or incomplete remote
     *  file fails clean: the importer validates everything before any write. */
    suspend fun restoreLatest(): BackupOpResult { return try {
        val newest = drive.list(BACKUP_PREFIX).maxByOrNull { it.createdMs }
            ?: return BackupOpResult(false, error = "no backup found in Google Drive")
        val bytes = drive.download(newest.id)
        val r = import(String(bytes, Charsets.UTF_8))
        if (r.ok) {
            val dropped = if (r.droppedPhotos > 0) " (${r.droppedPhotos} photo(s) could not be restored)" else ""
            BackupOpResult(true, detail = "restored from ${newest.name}$dropped")
        } else BackupOpResult(false, error = r.error ?: "backup file rejected")
    } catch (e: Exception) {
        BackupOpResult(false, error = e.message ?: e.javaClass.simpleName)
    } }

    /** Upload every memory photo that has no Google media id yet. Each failure
     *  (unreadable local file, upload error) is collected and named; a failed
     *  memory keeps googleMediaId=null so the next pass retries it.
     *  Duplicate protection: the photo_uploads ledger remembers each completed
     *  upload, so if the upload succeeded but marking the memory failed (crash
     *  between network and DB), the next pass re-marks from the ledger instead
     *  of uploading a second copy. Residual risk: a crash after the upload but
     *  before the ledger transaction can still duplicate one photo in Google
     *  Photos - visible as a duplicate there, never silent data loss. */
    suspend fun syncPhotos(): PhotoSyncResult {
        val client = photos ?: return PhotoSyncResult(0, 0, emptyList())
        var uploaded = 0
        var already = 0
        val failed = mutableListOf<String>()
        for (m in memories.all()) {
            val uri = m.photoUri ?: continue
            if (m.googleMediaId != null) { already++; continue }
            try {
                val ledger = db.photoUploads().byMemory(m.id)
                if (ledger != null) {
                    // upload already completed earlier - just re-mark, no second upload
                    memories.upsert(m.copy(googleMediaId = ledger.mediaId))
                    already++
                    continue
                }
                val bytes = photoBytes(uri)
                if (bytes == null) { failed.add(m.title); continue }
                val mediaId = client.upload(bytes, "remind-memory-${m.id}.jpg")
                db.withTransaction {
                    db.photoUploads().put(PhotoUploadEntity(m.id, mediaId, nowMs()))
                    memories.upsert(m.copy(googleMediaId = mediaId))
                }
                uploaded++
            } catch (e: Exception) {
                failed.add(m.title)
            }
        }
        return PhotoSyncResult(uploaded, already, failed)
    }

    /** Restore pass for a NEW device: after restoreLatest() brings back memory
     *  rows (with their googleMediaId links), download the photo bytes of every
     *  memory whose local file is missing and re-point photoUri at the fresh
     *  local copy. Failures are named, per memory, and leave the row untouched
     *  so a later pass retries. Requires the readonly.appcreateddata scope. */
    suspend fun restorePhotos(
        photoExists: suspend (String) -> Boolean,
        writePhoto: suspend (Long, ByteArray) -> String
    ): PhotoSyncResult {
        val client = photos ?: return PhotoSyncResult(0, 0, emptyList())
        var restored = 0
        var present = 0
        val failed = mutableListOf<String>()
        for (m in memories.all()) {
            val mediaId = m.googleMediaId ?: continue
            if (m.photoUri != null && photoExists(m.photoUri)) { present++; continue }
            try {
                val bytes = client.download(mediaId)
                val localUri = writePhoto(m.id, bytes)
                memories.upsert(m.copy(photoUri = localUri))
                restored++
            } catch (e: Exception) {
                failed.add(m.title)
            }
        }
        return PhotoSyncResult(restored, present, failed)
    }
}
