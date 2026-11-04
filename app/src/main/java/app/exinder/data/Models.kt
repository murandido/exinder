package app.exinder.data

import android.net.Uri

/**
 * Identifies a media folder on the device.
 *
 * MediaStore has no notion of a "folder" as such: each file carries the
 * volume it lives on (`external_primary`, an SD card…) and its relative path
 * (`DCIM/Camera/`). That pair is what we call a folder.
 *
 * Subfolders are independent folders: `DCIM/` and `DCIM/Camera/` show up as
 * separate items in the list, and neither includes the other's files.
 */
data class FolderRef(val volume: String, val relativePath: String) {
    /** Stable key used in the database and in preferences. */
    val key: String get() = "$volume|$relativePath"

    /** Last path segment, e.g. `Camera` for `DCIM/Camera/`. */
    val name: String
        get() = relativePath.trimEnd('/').substringAfterLast('/').ifEmpty { relativePath }

    companion object {
        /** Inverse of [key]. Returns `null` if the key is malformed. */
        fun fromKey(key: String): FolderRef? {
            val split = key.indexOf('|')
            if (split <= 0) return null
            return FolderRef(key.substring(0, split), key.substring(split + 1))
        }
    }
}

/** A folder as shown on the picker screen, including its review progress. */
data class FolderInfo(
    val ref: FolderRef,
    /** How many photos, videos and GIFs are in the folder right now. */
    val total: Int,
    /** How many of those have no decision yet. */
    val remaining: Int,
    /** Folder thumbnail: the oldest file not yet reviewed. */
    val cover: Uri?,
)

/** Media types the app can show on a card. */
enum class MediaKind { IMAGE, GIF, VIDEO }

/** A file in the review queue. */
data class MediaFile(
    val id: Long,
    /** MediaStore URI; this is what gets sent to the trash or to favorites. */
    val uri: Uri,
    val name: String,
    val mime: String,
    /** Size in bytes. */
    val size: Long,
    /** Capture date in milliseconds; falls back to the modification date. */
    val takenAt: Long,
    /** Duration in milliseconds (videos only; 0 otherwise). */
    val durationMs: Long,
    val kind: MediaKind,
    /** System favorite flag at load time, before any swipe. */
    val isFavorite: Boolean = false,
) {
    /** Key used in the decisions database. */
    val key: String get() = uri.toString()

    /**
     * SVG: vector drawing, almost always with a transparent background and dark strokes.
     * Shown whole (uncropped) on a white background, or it vanishes on the black card.
     */
    val isSvg: Boolean get() = mime == "image/svg+xml"
}

/**
 * Decision made about a file.
 *
 * A file with no decision at all is still pending and has no row in the database.
 */
enum class Status {
    /** Swipe right: stays on the device. */
    MANTER,

    /** Swipe up: stays on the device and becomes an Android favorite. */
    FAVORITO,

    /**
     * Swipe left, still inside the 2-file trash queue.
     * The file stays where it is and rewind can still save it.
     */
    LIXEIRA_PENDENTE,

    /** Already sent to the system trash. */
    NA_LIXEIRA,
}
