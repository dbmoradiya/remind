package com.remind.app.backup

/** Seams between the backup orchestration (unit-tested with fakes) and the live
 *  Google services (only usable with real credentials on device). */

data class RemoteFile(val id: String, val name: String, val createdMs: Long)

/** Drive appDataFolder: hidden, app-owned backup storage. Never deletes -
 *  retention policy is a user decision, not ours. */
interface DriveClient {
    suspend fun upload(name: String, bytes: ByteArray, mimeType: String): String // returns file id
    suspend fun list(namePrefix: String): List<RemoteFile>
    suspend fun download(fileId: String): ByteArray
}

/** Google Photos uploads (appendonly scope - verified current March 2025 policy:
 *  uploads keep working; reading back is limited to app-created items). */
interface PhotosClient {
    suspend fun upload(bytes: ByteArray, fileName: String): String // returns mediaItem id
    suspend fun download(mediaId: String): ByteArray
}

/** OAuth scopes the console Web/Android clients must carry - verified current
 *  (March 2025 Photos API policy: appendonly uploads keep working; read-back
 *  of app-created items needs readonly.appcreateddata):
 *  https://developers.google.com/photos/support/updates */
object GoogleScopes {
    const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    const val PHOTOS_APPEND = "https://www.googleapis.com/auth/photoslibrary.appendonly"
    const val PHOTOS_READ_APP = "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata"
    val ALL = listOf(DRIVE_APPDATA, PHOTOS_APPEND, PHOTOS_READ_APP)
}

/** OAuth token source. The device implementation wraps Google Sign-In. */
interface TokenProvider {
    suspend fun token(): String
}
