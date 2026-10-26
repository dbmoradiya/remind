package com.remind.app.backup

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Live Google implementations over plain HTTPS (no extra client-library deps).
 *  COMPILE-STAGE ONLY: not exercised by unit tests; validated on device with
 *  the user's real OAuth clients once the console setup is unblocked. */

private fun connect(url: String, token: String, method: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        setRequestProperty("Authorization", "Bearer $token")
        connectTimeout = 30_000
        readTimeout = 60_000
    }

private fun HttpURLConnection.readBytes(): ByteArray {
    val code = responseCode
    val stream = if (code in 200..299) inputStream else errorStream
    val body = stream?.readBytes() ?: ByteArray(0)
    if (code !in 200..299) throw java.io.IOException("HTTP $code: ${String(body).take(200)}")
    return body
}

class DriveRestClient(private val tokens: TokenProvider) : DriveClient {
    override suspend fun upload(name: String, bytes: ByteArray, mimeType: String): String =
        withContext(Dispatchers.IO) {
            val boundary = "remind" + System.currentTimeMillis()
            val meta = JSONObject()
                .put("name", name)
                .put("parents", JSONArray().put("appDataFolder")).toString()
            val c = connect(
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                tokens.token(), "POST"
            )
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            DataOutputStream(c.outputStream).use { out ->
                fun part(contentType: String, body: ByteArray) {
                    out.writeBytes("--$boundary\r\nContent-Type: $contentType\r\n\r\n")
                    out.write(body); out.writeBytes("\r\n")
                }
                part("application/json; charset=UTF-8", meta.toByteArray())
                part(mimeType, bytes)
                out.writeBytes("--$boundary--\r\n")
            }
            JSONObject(String(c.readBytes())).getString("id")
        }

    override suspend fun list(namePrefix: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode("name contains '$namePrefix'", "UTF-8")
        val c = connect(
            "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id,name,createdTime)",
            tokens.token(), "GET"
        )
        val arr = JSONObject(String(c.readBytes())).getJSONArray("files")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteFile(o.getString("id"), o.getString("name"),
                java.time.Instant.parse(o.getString("createdTime")).toEpochMilli())
        }
    }

    override suspend fun download(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        connect("https://www.googleapis.com/drive/v3/files/$fileId?alt=media",
            tokens.token(), "GET").readBytes()
    }
}

class PhotosRestClient(private val tokens: TokenProvider) : PhotosClient {
    override suspend fun upload(bytes: ByteArray, fileName: String): String = withContext(Dispatchers.IO) {
        // step 1: raw byte upload -> upload token
        val up = connect("https://photoslibrary.googleapis.com/v1/uploads", tokens.token(), "POST")
        up.doOutput = true
        up.setRequestProperty("Content-Type", "application/octet-stream")
        up.setRequestProperty("X-Goog-Upload-File-Name", fileName)
        up.setRequestProperty("X-Goog-Upload-Protocol", "raw")
        up.outputStream.use { it.write(bytes) }
        val uploadToken = String(up.readBytes())
        // step 2: batchCreate -> mediaItem id
        val body = JSONObject().put("newMediaItems", JSONArray().put(
            JSONObject().put("description", fileName).put("simpleMediaItem",
                JSONObject().put("fileName", fileName).put("uploadToken", uploadToken))
        )).toString()
        val bc = connect("https://photoslibrary.googleapis.com/v1/mediaItems:batchCreate",
            tokens.token(), "POST")
        bc.doOutput = true
        bc.setRequestProperty("Content-Type", "application/json")
        bc.outputStream.use { it.write(body.toByteArray()) }
        val results = JSONObject(String(bc.readBytes())).getJSONArray("newMediaItemResults")
        val item = results.getJSONObject(0)
        val status = item.optJSONObject("status")
        if (status != null && status.optInt("code", 0) != 0 && status.optString("message").isNotEmpty()
            && !status.optString("message").equals("OK", true) && status.optInt("code", 0) != 0)
            throw java.io.IOException("batchCreate failed: $status")
        item.getJSONObject("mediaItem").getString("id")
    }

    override suspend fun download(mediaId: String): ByteArray = withContext(Dispatchers.IO) {
        val c = connect("https://photoslibrary.googleapis.com/v1/mediaItems/$mediaId",
            tokens.token(), "GET")
        val baseUrl = JSONObject(String(c.readBytes())).getString("baseUrl")
        // fetch the actual bytes (original resolution download parameter)
        (URL("$baseUrl=d").openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 60_000
        }.readBytes()
    }
}

/** Google Sign-In glue (play-services-auth). Token via GoogleAuthUtil using the
 *  three verified scopes. Runtime use needs the console OAuth clients - blocked
 *  on the user's vault taps; this code compiles and is reviewable now. */
class GoogleSignInBackup(context: Context) {
    private val appContext = context.applicationContext
    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(GoogleScopes.ALL[0]),
            *GoogleScopes.ALL.drop(1).map { Scope(it) }.toTypedArray())
        .build()
    private val client = GoogleSignIn.getClient(appContext, gso)

    fun signInIntent(): Intent = client.signInIntent
    fun signedInAccount() = GoogleSignIn.getLastSignedInAccount(appContext)

    fun tokenProvider(): TokenProvider = object : TokenProvider {
        override suspend fun token(): String = withContext(Dispatchers.IO) {
            val account = GoogleSignIn.getLastSignedInAccount(appContext)
                ?: throw java.io.IOException("not signed in")
            val androidAccount = account.account
                ?: throw java.io.IOException("sign-in account has no Android account")
            GoogleAuthUtil.getToken(appContext, androidAccount,
                "oauth2:" + GoogleScopes.ALL.joinToString(" "))
        }
    }
}
