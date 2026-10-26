package com.remind.app

import androidx.test.core.app.ApplicationProvider
import com.remind.app.backup.BackupSync
import com.remind.app.ui.ImportResult
import com.remind.app.backup.DriveClient
import com.remind.app.backup.PhotosClient
import com.remind.app.backup.RemoteFile
import com.remind.app.data.AppDatabase
import com.remind.app.data.MemoryEntity
import com.remind.app.data.SettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM-only tests of the backup ORCHESTRATION with fake Drive/Photos clients.
 *  These prove the app's logic (naming, no-delete, retry-safety, error
 *  visibility, clean failure) - they are NOT live end-to-end Google tests;
 *  real Drive/Photos/OAuth validation happens on device with credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSyncTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val db by lazy { AppDatabase.get(ctx) }

    class FakeDrive : DriveClient {
        val files = LinkedHashMap<String, Pair<String, ByteArray>>() // id -> (name, bytes)
        val created = LinkedHashMap<String, Long>()
        private var next = 0
        override suspend fun upload(name: String, bytes: ByteArray, mimeType: String): String {
            val id = "f${next++}"
            files[id] = name to bytes
            created[id] = created.size * 1000L + 1   // increasing creation order
            return id
        }
        override suspend fun list(namePrefix: String) =
            files.entries.filter { it.value.first.startsWith(namePrefix) }
                .map { RemoteFile(it.key, it.value.first, created[it.key] ?: 0L) }
        override suspend fun download(fileId: String) = files[fileId]!!.second
    }

    class FakePhotos(val failOn: Set<String> = emptySet()) : PhotosClient {
        val uploads = mutableListOf<String>()
        val stored = HashMap<String, ByteArray>()
        override suspend fun upload(bytes: ByteArray, fileName: String): String {
            if (fileName in failOn) throw java.io.IOException("upload failed")
            uploads.add(fileName)
            val id = "media-$fileName"
            stored[id] = bytes
            return id
        }
        override suspend fun download(mediaId: String): ByteArray =
            stored[mediaId] ?: throw java.io.IOException("no such media item")
    }

    @Before
    fun reset() {
        runBlocking { withContext(Dispatchers.IO) { db.clearAllTables() } }
    }

    private fun sync(
        drive: FakeDrive,
        photos: PhotosClient? = null,
        import: suspend (String) -> ImportResult = { ImportResult(true) },
        clock: () -> Long = { 1_700_000_000_000L }
    ) = BackupSync(
        db = db, drive = drive, photos = photos,
        export = { """{"version":2,"marker":"full-export"}""" },
        import = import,
        memories = db.memories(), settings = db.settings(),
        photoBytes = { uri -> if (uri == "bad://missing") null else uri.toByteArray() },
        nowMs = clock
    )

    @Test fun backupNow_uploads_timestamped_backup_and_records_setting() = runBlocking {
        val drive = FakeDrive()
        val r = sync(drive).backupNow()
        assertTrue(r.error ?: "", r.ok)
        val (name, bytes) = drive.files.values.single()
        assertTrue(name.matches(Regex("remind-backup-\\d{8}-\\d{6}\\.json")))
        assertEquals("""{"version":2,"marker":"full-export"}""", String(bytes))
        assertEquals("1700000000000",
            db.settings().get(BackupSync.KEY_LAST_BACKUP_MS))
    }

    @Test fun backupNow_never_deletes_existing_remote_backups() = runBlocking {
        val drive = FakeDrive()
        // preload two older backups as if from previous days
        drive.files["old1"] = "remind-backup-20250101-090000.json" to "{}".toByteArray()
        drive.created["old1"] = 1L
        drive.files["old2"] = "remind-backup-20250102-090000.json" to "{}".toByteArray()
        drive.created["old2"] = 2L
        val s = sync(drive)
        assertTrue(s.backupNow().ok)
        assertTrue(s.backupNow().ok)
        // 2 old + 2 new - nothing pruned, retention is the user's choice
        assertEquals(4, drive.list("remind-backup-").size)
        assertTrue(drive.files.containsKey("old1"))
        assertTrue(drive.files.containsKey("old2"))
    }

    @Test fun restoreLatest_downloads_newest_backup() = runBlocking {
        val drive = FakeDrive()
        drive.files["a"] = "remind-backup-20250101-090000.json" to "older".toByteArray()
        drive.created["a"] = 100L
        drive.files["b"] = "remind-backup-20250103-090000.json" to "newest".toByteArray()
        drive.created["b"] = 200L
        var imported: String? = null
        val r = sync(drive, import = { imported = it; ImportResult(true, droppedPhotos = 1) }).restoreLatest()
        assertTrue(r.error ?: "", r.ok)
        assertEquals("newest", imported)
        assertTrue(r.detail!!.contains("remind-backup-20250103"))
        assertTrue("dropped-photo count must surface", r.detail!!.contains("1 photo"))
    }

    @Test fun restoreLatest_with_no_remote_backup_reports_error() = runBlocking {
        val r = sync(FakeDrive()).restoreLatest()
        assertFalse(r.ok)
        assertEquals("no backup found in Google Drive", r.error)
    }

    @Test fun restoreLatest_corrupt_remote_fails_clean() = runBlocking {
        val drive = FakeDrive()
        drive.files["x"] = "remind-backup-20250103-090000.json" to "garbage".toByteArray()
        drive.created["x"] = 1L
        var importCalled = false
        val r = sync(drive, import = { importCalled = true; ImportResult(false, error = "not a valid backup file") }).restoreLatest()
        assertFalse(r.ok)
        assertEquals("not a valid backup file", r.error)
        assertTrue(importCalled) // importer itself guarantees no writes on reject (FlowTest-covered)
    }

    @Test fun syncPhotos_uploads_unmarked_and_skips_done() = runBlocking {
        db.memories().upsert(MemoryEntity(id = 1, title = "Trip", dateEpochDay = 20000, photoUri = "file:///p1.jpg"))
        db.memories().upsert(MemoryEntity(id = 2, title = "Old", dateEpochDay = 19900, photoUri = "file:///p2.jpg", googleMediaId = "media-x"))
        val photos = FakePhotos()
        val r = sync(FakeDrive(), photos).syncPhotos()
        assertEquals(1, r.uploaded)
        assertEquals(1, r.alreadyBackedUp)
        assertTrue(r.failedTitles.isEmpty())
        assertEquals(listOf("remind-memory-1.jpg"), photos.uploads)
        assertEquals("media-remind-memory-1.jpg", db.memories().all().first { it.id == 1L }.googleMediaId)
    }

    @Test fun syncPhotos_mark_failure_reuses_ledger_no_duplicate_upload() = runBlocking {
        // simulate: upload succeeded in a previous pass, but marking the memory
        // failed afterward (crash between network and DB). The ledger holds the
        // completed upload, so the next pass re-marks WITHOUT uploading again.
        db.memories().upsert(MemoryEntity(id = 1, title = "Bike", dateEpochDay = 20000, photoUri = "file:///p1.jpg"))
        db.photoUploads().put(com.remind.app.data.PhotoUploadEntity(1, "media-remind-memory-1.jpg", 123L))
        val photos = FakePhotos()
        val r = sync(FakeDrive(), photos).syncPhotos()
        assertEquals(0, photos.uploads.size)   // NO duplicate upload
        assertEquals(0, r.uploaded)
        assertEquals(1, r.alreadyBackedUp)
        assertEquals("media-remind-memory-1.jpg", db.memories().all().first { it.id == 1L }.googleMediaId)
    }

    @Test fun restorePhotos_downloads_missing_local_files_from_google() = runBlocking {
        // fresh device: memory rows came back from the Drive restore with their
        // googleMediaId links, but the local photo files do not exist here
        db.memories().upsert(MemoryEntity(id = 1, title = "Trip", dateEpochDay = 20000, photoUri = null, googleMediaId = "media-a"))
        db.memories().upsert(MemoryEntity(id = 2, title = "Kept", dateEpochDay = 20001, photoUri = "file:///exists.jpg", googleMediaId = "media-b"))
        val photos = FakePhotos()
        photos.stored["media-a"] = "jpeg-bytes".toByteArray()
        photos.stored["media-b"] = "jpeg-bytes-2".toByteArray()
        val written = HashMap<Long, Pair<String, ByteArray>>()
        val r = sync(FakeDrive(), photos).restorePhotos(
            photoExists = { it == "file:///exists.jpg" },
            writePhoto = { id, bytes -> val u = "file:///restored-$id.jpg"; written[id] = u to bytes; u }
        )
        assertEquals(1, r.uploaded)          // restored count
        assertEquals(1, r.alreadyBackedUp)   // already present locally
        assertTrue(r.failedTitles.isEmpty())
        assertEquals("file:///restored-1.jpg", db.memories().all().first { it.id == 1L }.photoUri)
        assertEquals("jpeg-bytes", String(written[1L]!!.second))
    }

    @Test fun restorePhotos_download_failure_is_named_and_retriable() = runBlocking {
        db.memories().upsert(MemoryEntity(id = 1, title = "Trip", dateEpochDay = 20000, photoUri = null, googleMediaId = "media-gone"))
        val r = sync(FakeDrive(), FakePhotos()).restorePhotos(
            photoExists = { false },
            writePhoto = { id, _ -> "file:///restored-$id.jpg" }
        )
        assertEquals(0, r.uploaded)
        assertEquals(listOf("Trip"), r.failedTitles)
        assertNull(db.memories().all().first { it.id == 1L }.photoUri)   // untouched, retryable
    }

    @Test fun syncPhotos_failure_is_reported_and_retried_next_pass() = runBlocking {
        db.memories().upsert(MemoryEntity(id = 1, title = "Gym photo", dateEpochDay = 20000, photoUri = "file:///p1.jpg"))
        db.memories().upsert(MemoryEntity(id = 2, title = "Lost photo", dateEpochDay = 20001, photoUri = "bad://missing"))
        // pass 1: upload fails for p1, local bytes missing for p2
        val failing = FakePhotos(failOn = setOf("remind-memory-1.jpg"))
        val r1 = sync(FakeDrive(), failing).syncPhotos()
        assertEquals(0, r1.uploaded)
        assertEquals(setOf("Gym photo", "Lost photo"), r1.failedTitles.toSet())
        assertNull(db.memories().all().first { it.id == 1L }.googleMediaId)
        // pass 2: fixed client retries and succeeds; p2 still reported (bytes gone)
        val r2 = sync(FakeDrive(), FakePhotos()).syncPhotos()
        assertEquals(1, r2.uploaded)
        assertEquals(listOf("Lost photo"), r2.failedTitles)
        assertEquals("media-remind-memory-1.jpg", db.memories().all().first { it.id == 1L }.googleMediaId)
    }
}
