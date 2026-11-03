package app.exinder.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Uma decisão gravada sobre um arquivo.
 *
 * Gravar a cada swipe é o que garante que matar o app no meio não perde nada
 * e não apaga o que ainda estava no buffer.
 */
@Entity(tableName = "decision", indices = [Index("folderKey")])
data class Decision(
    /** [MediaFile.key], ou seja, a URI do MediaStore. */
    @PrimaryKey val uri: String,
    /** [FolderRef.key] da pasta onde o arquivo estava. */
    val folderKey: String,
    val status: Status,
    /** Tamanho em bytes, guardado para somar o espaço liberado. */
    val size: Long,
    val decidedAt: Long,
)

/** Resumo do que já foi para a lixeira numa pasta. */
data class TrashStats(
    val count: Int,
    val bytes: Long,
)

@Dao
interface DecisionDao {

    @Query("SELECT * FROM decision WHERE folderKey = :folderKey")
    suspend fun forFolder(folderKey: String): List<Decision>

    /** Arquivos com decisão "viva" (os que estão na lixeira já não aparecem nas listas). */
    @Query("SELECT uri FROM decision WHERE status != 'NA_LIXEIRA'")
    suspend fun decidedUris(): List<String>

    @Upsert
    suspend fun upsert(decision: Decision)

    @Query("UPDATE decision SET status = :status WHERE uri = :uri")
    suspend fun setStatus(uri: String, status: Status)

    @Query("DELETE FROM decision WHERE uri = :uri")
    suspend fun delete(uri: String)

    /**
     * Esquece as decisões de uma pasta, sem mexer em arquivo nenhum.
     * O histórico da lixeira fica, para o contador de espaço liberado não zerar.
     */
    @Query("DELETE FROM decision WHERE folderKey = :folderKey AND status != 'NA_LIXEIRA'")
    suspend fun resetFolder(folderKey: String)

    @Query(
        "SELECT COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM decision " +
            "WHERE folderKey = :folderKey AND status = 'NA_LIXEIRA'",
    )
    fun trashStats(folderKey: String): Flow<TrashStats>
}

@Database(entities = [Decision::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun decisions(): DecisionDao
}
