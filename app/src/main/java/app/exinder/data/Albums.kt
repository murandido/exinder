package app.exinder.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Where an album's photos end up. */
enum class AlbumDestination {
    /** A folder on the phone, `Pictures/<album name>`; files are moved there. */
    PHONE,

    /** An album in the user's Google Photos; files are uploaded to it. */
    GOOGLE_PHOTOS,
}

/** Progress of one file in an album. */
enum class AlbumItemState {
    /** Picked for the album, not sent yet (the user can still take it out). */
    PENDING,

    /** Moved to the album's folder, or uploaded to Google Photos. */
    DONE,

    /** The last attempt failed; sending the album again retries it. */
    FAILED,
}

/**
 * An album the user creates while reviewing kept files. Files are collected
 * first and sent in one go ("send"), so picking an album is always undoable.
 */
@Entity(tableName = "album")
data class Album(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val destination: AlbumDestination,
    /** The Google Photos album id, once it has been created there. */
    val googleAlbumId: String? = null,
    val createdAt: Long,
)

/** A file assigned to an album. A file belongs to one album at most. */
@Entity(
    tableName = "album_item",
    foreignKeys = [
        ForeignKey(
            entity = Album::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("albumId")],
)
data class AlbumItem(
    /** [MediaFile.key]: the MediaStore URI. */
    @PrimaryKey val uri: String,
    val albumId: Long,
    /** Display name, kept for uploads and for the album screen. */
    val name: String,
    val mime: String,
    val size: Long,
    val state: AlbumItemState,
    val addedAt: Long,
)

/** An album with how many of its files are waiting to be sent and how many were sent. */
data class AlbumSummary(
    val id: Long,
    val name: String,
    val destination: AlbumDestination,
    val googleAlbumId: String?,
    @ColumnInfo(name = "pending") val pending: Int,
    @ColumnInfo(name = "done") val done: Int,
    @ColumnInfo(name = "failed") val failed: Int,
    /** Any file of the album, for the cover. */
    val coverUri: String?,
)

@Dao
interface AlbumDao {

    @Query(
        "SELECT a.id, a.name, a.destination, a.googleAlbumId, " +
            "SUM(CASE WHEN i.state = 'PENDING' THEN 1 ELSE 0 END) AS pending, " +
            "SUM(CASE WHEN i.state = 'DONE' THEN 1 ELSE 0 END) AS done, " +
            "SUM(CASE WHEN i.state = 'FAILED' THEN 1 ELSE 0 END) AS failed, " +
            "MAX(i.uri) AS coverUri " +
            "FROM album a LEFT JOIN album_item i ON i.albumId = a.id " +
            "GROUP BY a.id ORDER BY a.createdAt DESC",
    )
    fun summaries(): Flow<List<AlbumSummary>>

    @Query("SELECT * FROM album WHERE id = :id")
    suspend fun album(id: Long): Album?

    @Query("SELECT * FROM album_item WHERE albumId = :albumId ORDER BY addedAt")
    fun items(albumId: Long): Flow<List<AlbumItem>>

    @Query("SELECT * FROM album_item WHERE albumId = :albumId AND state != 'DONE' ORDER BY addedAt")
    suspend fun unsent(albumId: Long): List<AlbumItem>

    @Query("SELECT * FROM album_item WHERE uri = :uri")
    suspend fun itemFor(uri: String): AlbumItem?

    @Upsert
    suspend fun upsert(album: Album): Long

    @Upsert
    suspend fun upsertItem(item: AlbumItem)

    @Query("UPDATE album_item SET state = :state WHERE uri = :uri")
    suspend fun setState(uri: String, state: AlbumItemState)

    @Query("UPDATE album SET googleAlbumId = :googleId WHERE id = :id")
    suspend fun setGoogleId(id: Long, googleId: String)

    @Query("DELETE FROM album_item WHERE uri = :uri")
    suspend fun removeItem(uri: String)

    @Query("DELETE FROM album WHERE id = :id")
    suspend fun deleteAlbum(id: Long)
}
