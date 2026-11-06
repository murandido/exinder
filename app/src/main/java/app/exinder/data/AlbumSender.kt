package app.exinder.data

import android.util.Log
import androidx.core.net.toUri
import java.io.IOException

/**
 * Creates albums and sends their files: moved to a folder on the phone, or
 * uploaded to Google Photos. Files are sent only when the user asks ("send"),
 * so putting a file in an album during review stays undoable until then.
 */
class AlbumSender(
    private val albums: AlbumDao,
    private val decisions: DecisionDao,
    private val media: MediaRepository,
    private val photos: GooglePhotos,
) {

    /** How a send ended. */
    sealed interface Outcome {
        /**
         * [sent] files reached the album, [failed] didn't (they can be retried),
         * [trashed] originals went to the trash. [error] is the first failure's
         * reason, shown to the user so a setup problem can be told apart from a
         * network hiccup.
         */
        data class Done(val sent: Int, val failed: Int, val trashed: Int, val error: String? = null) : Outcome

        /** Google sign-in was declined or isn't set up for this build. */
        data object NeedsSignIn : Outcome

        /** Moving files needs write access, and the user didn't give it. */
        data object WriteDenied : Outcome
    }

    suspend fun createAlbum(name: String, destination: AlbumDestination): Long =
        albums.upsert(Album(name = name.trim(), destination = destination, createdAt = System.currentTimeMillis()))

    /**
     * Sends the album's files that weren't sent yet (or failed before).
     *
     * @param trashOriginals Google Photos only: send the originals to the
     *   phone's trash once they are safely uploaded.
     * @param onProgress how many of the files have been handled so far.
     */
    suspend fun send(albumId: Long, trashOriginals: Boolean, onProgress: (done: Int, total: Int) -> Unit): Outcome {
        val album = albums.album(albumId) ?: return Outcome.Done(0, 0, 0)
        val items = albums.unsent(albumId)
        if (items.isEmpty()) return Outcome.Done(0, 0, 0)
        return when (album.destination) {
            AlbumDestination.PHONE -> moveToFolder(album, items, onProgress)
            AlbumDestination.GOOGLE_PHOTOS -> upload(album, items, trashOriginals, onProgress)
        }
    }

    private suspend fun moveToFolder(album: Album, items: List<AlbumItem>, onProgress: (Int, Int) -> Unit): Outcome {
        val uris = items.map { it.uri.toUri() }
        // One write request for the whole album; with media management it
        // completes without a dialog.
        if (!SystemRequests.run(media.writeRequest(uris))) return Outcome.WriteDenied
        val path = "Pictures/${folderName(album.name)}/"
        var sent = 0
        items.forEachIndexed { i, item ->
            val moved = media.moveTo(item.uri.toUri(), path)
            albums.setState(item.uri, if (moved) AlbumItemState.DONE else AlbumItemState.FAILED)
            if (moved) sent++
            onProgress(i + 1, items.size)
        }
        return Outcome.Done(sent, items.size - sent, 0)
    }

    private suspend fun upload(
        album: Album,
        items: List<AlbumItem>,
        trashOriginals: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): Outcome {
        var token = try {
            photos.accessToken(interactive = true)
        } catch (_: GooglePhotos.NotAuthorized) {
            return Outcome.NeedsSignIn
        }

        /** Runs [block] with the token, getting a fresh one once if the API rejects it. */
        suspend fun <T> withToken(block: suspend (String) -> T): T = try {
            block(token)
        } catch (_: GooglePhotos.Unauthorized) {
            photos.forget(token)
            token = photos.accessToken(interactive = true)
            block(token)
        }

        val googleId = try {
            album.googleAlbumId ?: withToken { photos.createAlbum(it, album.name) }.also {
                albums.setGoogleId(album.id, it)
            }
        } catch (_: GooglePhotos.NotAuthorized) {
            return Outcome.NeedsSignIn
        } catch (e: IOException) {
            Log.w(TAG, "creating the Google Photos album failed", e)
            items.forEach { albums.setState(it.uri, AlbumItemState.FAILED) }
            return Outcome.Done(0, items.size, 0, e.message)
        }

        // Upload every file first, then add them to the album in batches.
        val uploads = HashMap<String, AlbumItem>()
        var error: String? = null
        items.forEachIndexed { i, item ->
            val uploadToken = try {
                withToken { photos.upload(it, item.uri.toUri(), item.mime, item.size) }
            } catch (e: Exception) {
                Log.w(TAG, "uploading ${item.name} failed", e)
                error = error ?: e.message
                null
            }
            if (uploadToken == null) {
                albums.setState(item.uri, AlbumItemState.FAILED)
            } else {
                uploads[uploadToken] = item
            }
            onProgress(i + 1, items.size)
        }

        val added = try {
            withToken { t ->
                photos.addToAlbum(t, googleId, uploads.map { GooglePhotos.Upload(it.key, it.value.name) })
            }
        } catch (e: Exception) {
            Log.w(TAG, "adding uploads to the album failed", e)
            error = error ?: e.message
            emptyMap()
        }
        val sent = uploads.filter { (uploadToken, _) -> added[uploadToken] == true }.values.toList()
        uploads.values.forEach { item ->
            albums.setState(item.uri, if (item in sent) AlbumItemState.DONE else AlbumItemState.FAILED)
        }

        val trashed = if (trashOriginals && sent.isNotEmpty()) trash(sent) else 0
        return Outcome.Done(sent.size, items.size - sent.size, trashed, error)
    }

    /** Sends originals already safe in Google Photos to the phone's trash, and records it. */
    private suspend fun trash(items: List<AlbumItem>): Int {
        val ok = runCatching { SystemRequests.run(media.trashRequest(items.map { it.uri.toUri() }, true)) }
            .getOrDefault(false)
        if (!ok) return 0
        items.forEach { item ->
            decisions.get(item.uri)?.let { decisions.upsert(it.copy(status = Status.NA_LIXEIRA)) }
        }
        return items.size
    }

    companion object {
        private const val TAG = "AlbumSender"

        /** A folder name from an album name: no path separators or characters Android rejects. */
        fun folderName(album: String): String =
            album.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").trim('.', ' ').ifEmpty { "Album" }
    }
}
