package app.exinder.data

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The few Google Photos Library API calls the app needs: create an album,
 * upload a file and add uploads to an album.
 *
 * The API only lets an app manage albums and photos it created itself
 * ("appendonly" scope), so exinder never sees the rest of the user's library.
 * Sign-in uses Android's authorization client: the OAuth client is matched by
 * package name and signing certificate, so no client id or secret lives in
 * the app (see docs/GOOGLE_PHOTOS.md for the one-time Google Cloud setup).
 */
class GooglePhotos(private val context: Context) {

    /** The user isn't signed in (or declined), or Google sign-in isn't set up for this build. */
    class NotAuthorized(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE_APPEND_ONLY)))
        .build()

    /**
     * An access token for the Photos API.
     *
     * @param interactive whether the consent screen may be shown. Without it,
     *   only an earlier grant works.
     * @throws NotAuthorized when there's no grant and none can be obtained.
     */
    suspend fun accessToken(interactive: Boolean): String {
        val client = Identity.getAuthorizationClient(context)
        val result = try {
            client.authorize(request).await()
        } catch (e: Exception) {
            throw NotAuthorized("Google sign-in is not available", e)
        }
        if (!result.hasResolution()) {
            return result.accessToken ?: throw NotAuthorized("no access token")
        }
        if (!interactive) throw NotAuthorized("consent needed")
        val consent = result.pendingIntent ?: throw NotAuthorized("no consent screen")
        val answer = SystemRequests.runForResult(consent)
        if (answer?.resultCode != Activity.RESULT_OK) throw NotAuthorized("consent declined")
        return client.getAuthorizationResultFromIntent(answer.data).accessToken
            ?: throw NotAuthorized("no access token")
    }

    /** Drops a cached token the API rejected, so the next [accessToken] gets a fresh one. */
    suspend fun forget(token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }

    /** Creates an album and returns its id. */
    suspend fun createAlbum(token: String, title: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("album", JSONObject().put("title", title.take(MAX_TITLE)))
        val response = call(token, "$API/albums", body.toString())
        JSONObject(response).getString("id")
    }

    /**
     * Uploads a file's bytes, streamed straight from MediaStore.
     *
     * @return the upload token to pass to [addToAlbum].
     */
    suspend fun upload(token: String, uri: Uri, mime: String, size: Long): String = withContext(Dispatchers.IO) {
        val connection = (URL("$API/uploads").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-type", "application/octet-stream")
            setRequestProperty("X-Goog-Upload-Content-Type", mime)
            setRequestProperty("X-Goog-Upload-Protocol", "raw")
            if (size > 0) setFixedLengthStreamingMode(size) else setChunkedStreamingMode(0)
        }
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't read $uri")
            input.use { source -> connection.outputStream.use { source.copyTo(it) } }
            read(connection)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Turns uploads into photos in the album, at most [BATCH] at a time.
     *
     * @return for each upload token, whether it was added.
     */
    suspend fun addToAlbum(token: String, albumId: String, uploads: List<Upload>): Map<String, Boolean> =
        withContext(Dispatchers.IO) {
            val added = HashMap<String, Boolean>()
            uploads.chunked(BATCH).forEach { batch ->
                val items = JSONArray()
                batch.forEach { up ->
                    items.put(
                        JSONObject().put(
                            "simpleMediaItem",
                            JSONObject().put("uploadToken", up.uploadToken).put("fileName", up.fileName),
                        ),
                    )
                }
                val body = JSONObject().put("albumId", albumId).put("newMediaItems", items)
                val results = JSONObject(call(token, "$API/mediaItems:batchCreate", body.toString()))
                    .optJSONArray("newMediaItemResults") ?: JSONArray()
                for (i in 0 until results.length()) {
                    val result = results.getJSONObject(i)
                    val code = result.optJSONObject("status")?.optInt("code", 0) ?: 0
                    added[result.optString("uploadToken")] = code == 0 && result.has("mediaItem")
                }
            }
            added
        }

    /** One file to add to an album. */
    data class Upload(val uploadToken: String, val fileName: String)

    /** The API rejected the token (expired or revoked). */
    class Unauthorized : IOException("unauthorized")

    private fun call(token: String, url: String, json: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(json.toByteArray()) }
            return read(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun read(connection: HttpURLConnection): String {
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw Unauthorized()
        if (code !in 200..299) {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            // Google's error JSON carries a readable message (e.g. "Photos Library API
            // has not been used in project ..."); show that rather than the raw JSON.
            val message = runCatching { JSONObject(body).getJSONObject("error").getString("message") }
                .getOrDefault(body)
            throw IOException("Google Photos ($code): ${message.take(300)}")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private companion object {
        const val API = "https://photoslibrary.googleapis.com/v1"
        const val SCOPE_APPEND_ONLY = "https://www.googleapis.com/auth/photoslibrary.appendonly"

        /** The API's limit for one batchCreate call. */
        const val BATCH = 50

        /** The API's limit for an album title. */
        const val MAX_TITLE = 500
    }
}

/** Waits for a Play services [Task] without blocking a thread. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
