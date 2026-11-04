package app.exinder.data

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tudo o que o app lê ou altera no MediaStore.
 *
 * O MediaStore é o índice de mídia do Android. Usar ele (em vez de varrer o
 * sistema de arquivos) dá três coisas de graça:
 * - a data de captura já extraída do EXIF / metadado do vídeo;
 * - a lixeira do sistema, que guarda o arquivo por 30 dias;
 * - a marcação de favorito, que a galeria do aparelho também enxerga.
 */
class MediaRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** Tabela que junta imagens e vídeos de todos os volumes externos. */
    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Filtro de "só fotos, GIFs e vídeos". */
    private val mediaSelection =
        "(${FileColumns.MEDIA_TYPE} = ${FileColumns.MEDIA_TYPE_IMAGE} " +
            "OR ${FileColumns.MEDIA_TYPE} = ${FileColumns.MEDIA_TYPE_VIDEO})"

    /**
     * Lista todas as pastas que têm mídia, com quantos arquivos ainda faltam revisar.
     *
     * @param decided chaves ([MediaFile.key]) dos arquivos que já têm decisão.
     */
    suspend fun folders(decided: Set<String>): List<FolderInfo> = withContext(Dispatchers.IO) {
        // Acumulador por pasta, preenchido numa única passada pelo cursor.
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
            .map { (ref, acc) -> FolderInfo(ref, acc.total, acc.remaining, acc.cover ?: acc.anyUri) }
            .sortedBy { it.ref.relativePath.lowercase() }
    }

    /**
     * Arquivos de uma pasta (sem subpastas), do mais antigo para o mais novo.
     *
     * Arquivos que já estão na lixeira do sistema não aparecem: o MediaStore
     * os esconde por padrão.
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

        // Empate de data (rajadas de fotos, por exemplo) é resolvido pelo nome.
        result.sortedWith(compareBy<MediaFile> { it.takenAt }.thenBy { it.name })
    }

    /**
     * Pedido para mandar arquivos para a lixeira do sistema (ou tirar de lá).
     *
     * O Android sempre devolve um [PendingIntent] que precisa ser disparado por
     * uma Activity. Com a permissão "Gerenciamento de mídia" concedida, ele
     * volta na hora, sem mostrar diálogo nenhum.
     */
    fun trashRequest(uris: List<Uri>, trashed: Boolean): PendingIntent =
        MediaStore.createTrashRequest(resolver, uris, trashed)

    /** Pedido para marcar (ou desmarcar) arquivos como favoritos. Mesmo esquema da lixeira. */
    fun favoriteRequest(uris: List<Uri>, favorite: Boolean): PendingIntent =
        MediaStore.createFavoriteRequest(resolver, uris, favorite)

    /** `true` se o usuário já liberou o "Gerenciamento de mídia" para o app. */
    fun canManageMedia(): Boolean = MediaStore.canManageMedia(context)

    /**
     * URI "tipada" (Images ou Video) de um arquivo.
     * Os pedidos de lixeira e favorito exigem esse formato, não o da tabela Files.
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
     * Data usada para ordenar: a de captura quando existe; senão, a de
     * modificação (que o MediaStore guarda em segundos, não milissegundos).
     */
    private fun takenAt(dateTakenMs: Long, dateModifiedSec: Long): Long =
        if (dateTakenMs > 0) dateTakenMs else dateModifiedSec * 1000
}
