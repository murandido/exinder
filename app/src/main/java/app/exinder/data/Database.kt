package app.exinder.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A stored decision about a file.
 *
 * Writing on every swipe is what guarantees that killing the app midway loses
 * nothing and doesn't delete what was still in the trash queue.
 */
@Entity(tableName = "decision", indices = [Index("folderKey")])
data class Decision(
    /** [MediaFile.key], i.e. the MediaStore URI. */
    @PrimaryKey val uri: String,
    /** [FolderRef.key] of the folder the file was in. */
    val folderKey: String,
    val status: Status,
    /** Size in bytes, stored to total up the space freed. */
    val size: Long,
    /**
     * When the decision was made. The trash grace period is counted in
     * decisions made *after* a file was marked, by this timestamp, so it
     * doesn't depend on the files' order (which can change between sessions).
     */
    val decidedAt: Long,
    /**
     * The app marked this file as a favorite (it wasn't one before the swipe).
     * Undoing the decision only removes favorites the app itself set.
     */
    @ColumnInfo(defaultValue = "0") val appFavorited: Boolean = false,
)

/** Summary of what has already gone to the trash in a folder. */
data class TrashStats(val count: Int, val bytes: Long)

@Dao
interface DecisionDao {

    @Query("SELECT * FROM decision WHERE folderKey = :folderKey")
    suspend fun forFolder(folderKey: String): List<Decision>

    /**
     * Files reviewed for good: kept or favorited. Files marked for the trash
     * that haven't gone yet count as "to review", so the folder doesn't show
     * as done while files are waiting for "send now".
     */
    @Query("SELECT uri FROM decision WHERE status IN ('MANTER', 'FAVORITO')")
    suspend fun decidedUris(): List<String>

    @Upsert
    suspend fun upsert(decision: Decision)

    @Query("UPDATE decision SET status = :status WHERE uri = :uri")
    suspend fun setStatus(uri: String, status: Status)

    @Query("DELETE FROM decision WHERE uri = :uri")
    suspend fun delete(uri: String)

    /**
     * Forgets a folder's decisions without touching any file.
     * The trash history is kept so the freed-space counter doesn't reset.
     */
    @Query("DELETE FROM decision WHERE folderKey = :folderKey AND status != 'NA_LIXEIRA'")
    suspend fun resetFolder(folderKey: String)

    @Query(
        "SELECT COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM decision " +
            "WHERE folderKey = :folderKey AND status = 'NA_LIXEIRA'",
    )
    fun trashStats(folderKey: String): Flow<TrashStats>
}

/**
 * The schema is exported to `app/schemas/` so every version is checked into the
 * repository. Each version bump needs a migration in [MIGRATIONS]; without one,
 * Room would crash existing installs on first access.
 */
@Database(entities = [Decision::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun decisions(): DecisionDao
}

/** Version 1 → 2: adds [Decision.appFavorited]. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE decision ADD COLUMN appFavorited INTEGER NOT NULL DEFAULT 0")
    }
}

/** Every schema migration, in order. */
val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
