package app.exinder.data

import android.net.Uri

/**
 * Identifica uma pasta de mídia do aparelho.
 *
 * O MediaStore não tem o conceito de "pasta" em si: cada arquivo carrega o
 * volume onde está (`external_primary`, um cartão SD…) e o caminho relativo
 * (`DCIM/Camera/`). A dupla dos dois é o que chamamos de pasta.
 *
 * Subpastas são pastas independentes: `DCIM/` e `DCIM/Camera/` aparecem como
 * itens separados na lista e uma não inclui os arquivos da outra.
 */
data class FolderRef(
    val volume: String,
    val relativePath: String,
) {
    /** Chave estável usada no banco e nas preferências. */
    val key: String get() = "$volume|$relativePath"

    /** Último segmento do caminho, ex.: `Camera` para `DCIM/Camera/`. */
    val name: String
        get() = relativePath.trimEnd('/').substringAfterLast('/').ifEmpty { relativePath }

    companion object {
        /** Faz o caminho inverso de [key]. Devolve `null` se a chave estiver corrompida. */
        fun fromKey(key: String): FolderRef? {
            val split = key.indexOf('|')
            if (split <= 0) return null
            return FolderRef(key.substring(0, split), key.substring(split + 1))
        }
    }
}

/** Uma pasta como aparece na tela de escolha, já com o progresso da revisão. */
data class FolderInfo(
    val ref: FolderRef,
    /** Quantas fotos, vídeos e GIFs existem na pasta agora. */
    val total: Int,
    /** Quantos desses ainda não receberam decisão. */
    val remaining: Int,
    /** Miniatura da pasta: o arquivo mais antigo ainda não revisado. */
    val cover: Uri?,
)

/** Tipos de mídia que o app sabe mostrar num card. */
enum class MediaKind { IMAGE, GIF, VIDEO }

/** Um arquivo da fila de revisão. */
data class MediaFile(
    val id: Long,
    /** URI do MediaStore; é ela que vai para a lixeira ou para os favoritos. */
    val uri: Uri,
    val name: String,
    val mime: String,
    /** Tamanho em bytes. */
    val size: Long,
    /** Data de captura em milissegundos; sem ela, a data de modificação. */
    val takenAt: Long,
    /** Duração em milissegundos (só vídeos; 0 para o resto). */
    val durationMs: Long,
    val kind: MediaKind,
) {
    /** Chave usada no banco de decisões. */
    val key: String get() = uri.toString()
}

/**
 * Decisão tomada sobre um arquivo.
 *
 * Um arquivo sem decisão nenhuma ainda está pendente e não tem linha no banco.
 */
enum class Status {
    /** Swipe para a direita: fica no aparelho. */
    MANTER,

    /** Swipe para cima: fica no aparelho e vira favorito no Android. */
    FAVORITO,

    /**
     * Swipe para a esquerda, ainda dentro do buffer de 2 arquivos.
     * O arquivo continua no lugar e o rebobinar consegue salvá-lo.
     */
    LIXEIRA_PENDENTE,

    /** Já foi enviado para a lixeira do sistema. */
    NA_LIXEIRA,
}
