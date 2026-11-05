package app.exinder.data

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the app reads from or changes in MediaStore.
 *
 * MediaStore is Android's media index. Using it (instead of walking the
 * file system) gives three things for free:
 * - the capture date, already extracted from EXIF / video metadata;
 * - the system trash, which keeps files for 30 days;
 * - the favorite flag, which the device's gallery also sees.
 */
class MediaRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** Table combining images and videos from all external volumes. */
    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Filter for "only photos, GIFs and videos". */
    private val mediaSelection =
        "(${FileColumns.MEDIA_TYPE} = ${FileColumns.MEDIA_TYPE_IMAGE} " +
            "OR ${FileColumns.MEDIA_TYPE} = ${FileColumns.MEDIA_TYPE_VIDEO})"

    /**
     * Lists every folder that has media, with how many files are left to review.
     *
     * @param decided keys ([MediaFile.key]) of the files that already have a decision.
     * @param trashed how many files each folder ([FolderRef.key]) has sent to the trash.
     */
    suspend fun folders(decided: Set<String>, trashed: Map<String, Int>): List<FolderInfo> =
        withContext(Dispatchers.IO) {
            // Per-folder accumulator, filled in a single pass over the cursor.
            class Acc {
                var total = 0
                var remaining = 0
                var oldestPending = Long.MAX_VALUE
                var cover: Uri? = null
                var anyUri: Uri? = null
            }

            val byFolder = HashMap<FolderRef, Acc>()
            val projection = arrayOf(
                FileColumns._ID,
                FileColumns.MEDIA_TYPE,
                MediaColumns.RELATIVE_PATH,
                MediaColumns.VOLUME_NAME,
                MediaColumns.DATE_TAKEN,
                MediaColumns.DATE_MODIFIED,
            )

            resolver.query(filesUri, projection, mediaSelection, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(FileColumns._ID)
                val iType = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
                val iPath = c.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
                val iVolume = c.getColumnIndexOrThrow(MediaColumns.VOLUME_NAME)
                val iTaken = c.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
                val iModified = c.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)

                while (c.moveToNext()) {
                    val path = c.getString(iPath) ?: continue
                    val volume = c.getString(iVolume) ?: continue
                    val uri = contentUri(volume, c.getLong(iId), c.getInt(iType))
                    val acc = byFolder.getOrPut(FolderRef(volume, path)) { Acc() }

                    acc.total++
                    acc.anyUri = acc.anyUri ?: uri
                    if (uri.toString() !in decided) {
                        acc.remaining++
                        val takenAt = takenAt(c.getLong(iTaken), c.getLong(iModified))
                        if (takenAt < acc.oldestPending) {
                            acc.oldestPending = takenAt
                            acc.cover = uri
                        }
                    }
                }
            }

            byFolder
                .map { (ref, acc) ->
                    FolderInfo(
                        ref,
                        acc.total,
                        acc.remaining,
                        acc.cover ?: acc.anyUri,
                        trashed[ref.key] ?: 0,
                    )
                }
                .sortedBy { it.ref.relativePath.lowercase() }
        }

    /**
     * Files in a folder (excluding subfolders), oldest to newest.
     *
     * Files already in the system trash don't appear: MediaStore
     * hides them by default.
     */
    suspend fun items(folder: FolderRef): List<MediaFile> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            FileColumns._ID,
            FileColumns.MEDIA_TYPE,
            MediaColumns.DISPLAY_NAME,
            MediaColumns.MIME_TYPE,
            MediaColumns.SIZE,
            MediaColumns.DATE_TAKEN,
            MediaColumns.DATE_MODIFIED,
            MediaColumns.DURATION,
            MediaColumns.IS_FAVORITE,
        )
        val selection =
            "$mediaSelection AND ${MediaColumns.RELATIVE_PATH} = ? AND ${MediaColumns.VOLUME_NAME} = ?"
        val args = arrayOf(folder.relativePath, folder.volume)

        val result = ArrayList<MediaFile>()
        resolver.query(filesUri, projection, selection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(FileColumns._ID)
            val iType = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
            val iName = c.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaColumns.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaColumns.SIZE)
            val iTaken = c.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
            val iModified = c.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
            val iDuration = c.getColumnIndexOrThrow(MediaColumns.DURATION)
            val iFavorite = c.getColumnIndexOrThrow(MediaColumns.IS_FAVORITE)

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val type = c.getInt(iType)
                val mime = c.getString(iMime).orEmpty()
                result += MediaFile(
                    id = id,
                    uri = contentUri(folder.volume, id, type),
                    name = c.getString(iName).orEmpty(),
                    mime = mime,
                    size = c.getLong(iSize),
                    takenAt = takenAt(c.getLong(iTaken), c.getLong(iModified)),
                    durationMs = c.getLong(iDuration),
                    kind = when {
                        type == FileColumns.MEDIA_TYPE_VIDEO -> MediaKind.VIDEO
                        mime == "image/gif" -> MediaKind.GIF
                        else -> MediaKind.IMAGE
                    },
                    isFavorite = c.getInt(iFavorite) == 1,
                )
            }
        }

        // Date ties (photo bursts, for example) are broken by name.
        result.sortedWith(compareBy<MediaFile> { it.takenAt }.thenBy { it.name })
    }

    /**
     * Request to move files to the system trash (or take them back out).
     *
     * Android always returns a [PendingIntent] that must be launched by
     * an Activity. With the "Media management" permission granted, it
     * completes immediately without showing any dialog.
     */
    fun trashRequest(uris: List<Uri>, trashed: Boolean): PendingIntent =
        MediaStore.createTrashRequest(resolver, uris, trashed)

    /** Request to modify files (needed to move them to an album folder). Same mechanism as the trash. */
    fun writeRequest(uris: List<Uri>): PendingIntent = MediaStore.createWriteRequest(resolver, uris)

    /**
     * Moves a file to another folder on the same volume, e.g. `Pictures/Trip/`.
     * Needs write access to it (see [writeRequest]).
     *
     * @return `true` if MediaStore moved it.
     */
    suspend fun moveTo(uri: Uri, relativePath: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply { put(MediaColumns.RELATIVE_PATH, relativePath) }
            resolver.update(uri, values, null, null) > 0
        }.getOrDefault(false)
    }

    /** Request to mark (or unmark) files as favorites. Same mechanism as the trash. */
    fun favoriteRequest(uris: List<Uri>, favorite: Boolean): PendingIntent =
        MediaStore.createFavoriteRequest(resolver, uris, favorite)

    /**
     * `true` if [uri] still points to a file MediaStore shows. Trashed files
     * are hidden by default, so a trashed file counts as missing, while a file
     * moved to another folder (same ID, new path) still exists.
     */
    suspend fun exists(uri: Uri): Boolean? = withContext(Dispatchers.IO) {
        // `null` when MediaStore can't answer (no permission, provider error):
        // callers must not read that as "gone".
        runCatching {
            resolver.query(uri, arrayOf(MediaColumns._ID), null, null, null)?.use { it.count > 0 }
        }.getOrNull()
    }

    /** `true` if the storage volume (internal storage, an SD card) is currently mounted. */
    fun isVolumeMounted(volume: String): Boolean = volume in MediaStore.getExternalVolumeNames(context)

    /** `true` if the user has already granted "Media management" to the app. */
    fun canManageMedia(): Boolean = MediaStore.canManageMedia(context)

    /**
     * "Typed" URI (Images or Video) of a file.
     * Trash and favorite requests require this form, not the Files table one.
     */
    private fun contentUri(volume: String, id: Long, mediaType: Int): Uri {
        val collection = if (mediaType == FileColumns.MEDIA_TYPE_VIDEO) {
            MediaStore.Video.Media.getContentUri(volume)
        } else {
            MediaStore.Images.Media.getContentUri(volume)
        }
        return ContentUris.withAppendedId(collection, id)
    }

    /**
     * Date used for sorting: the capture date when present; otherwise the
     * modification date (which MediaStore stores in seconds, not milliseconds).
     */
    private fun takenAt(dateTakenMs: Long, dateModifiedSec: Long): Long =
        if (dateTakenMs > 0) dateTakenMs else dateModifiedSec * 1000
}
