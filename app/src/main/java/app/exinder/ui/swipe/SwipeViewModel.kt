package app.exinder.ui.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.exinder.AppContainer
import app.exinder.data.Decision
import app.exinder.data.FolderRef
import app.exinder.data.MediaFile
import app.exinder.data.Status
import app.exinder.data.SystemRequests
import app.exinder.data.TrashStats
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Para onde o card foi (ou de onde ele volta ao rebobinar). */
enum class Dir { LEFT, RIGHT, UP }

/**
 * Quantas posições um arquivo marcado para apagar espera antes de ir para a
 * lixeira. Com 2, o arquivo N vai quando o arquivo N+2 é decidido.
 */
const val TRASH_DELAY = 2

/** Estado da tela de swipe. */
data class SwipeUi(
    val loading: Boolean = true,
    /** Todos os arquivos da pasta, do mais antigo para o mais novo. */
    val items: List<MediaFile> = emptyList(),
    /** Decisão de cada arquivo, por [MediaFile.key]. Sem entrada = ainda pendente. */
    val status: Map<String, Status> = emptyMap(),
    /**
     * Índice do card na tela. Sempre aponta para um arquivo sem decisão, ou
     * para `items.size` quando a fila acabou.
     */
    val cursor: Int = 0,
    /** Preenchido logo após um rebobinar, para o card voltar animando do lado certo. */
    val enterFrom: Dir? = null,
) {
    fun statusOf(file: MediaFile): Status? = status[file.key]

    /** O card de cima da pilha. */
    val current: MediaFile? get() = items.getOrNull(cursor)

    /** O card que aparece logo atrás. */
    val next: MediaFile? get() = nextUndecided(cursor + 1)?.let(items::get)

    /** Índices dos arquivos no buffer, esperando a vez de ir para a lixeira. */
    val pending: List<Int>
        get() = (0 until cursor.coerceAtMost(items.size))
            .filter { statusOf(items[it]) == Status.LIXEIRA_PENDENTE }

    /** Dá para voltar enquanto existir algo atrás que não foi para a lixeira. */
    val canRewind: Boolean
        get() = (0 until cursor.coerceAtMost(items.size))
            .any { statusOf(items[it]) != Status.NA_LIXEIRA }

    fun count(status: Status): Int = this.status.values.count { it == status }

    /** Primeiro arquivo sem decisão a partir de [from], ou `null`. */
    fun nextUndecided(from: Int): Int? = (from until items.size).firstOrNull { statusOf(items[it]) == null }
}

/**
 * O coração do app: a fila de uma pasta, as decisões e a lixeira adiada.
 *
 * ### Regra dos 2 arquivos
 * Um swipe para a esquerda só marca o arquivo como [Status.LIXEIRA_PENDENTE].
 * Ele vai de fato para a lixeira quando o cursor passa [TRASH_DELAY] posições
 * à frente dele. Até lá, rebobinar desfaz tudo sem o arquivo nunca ter saído
 * do lugar.
 */
class SwipeViewModel(
    private val container: AppContainer,
    val folder: FolderRef,
) : ViewModel() {

    private val _ui = MutableStateFlow(SwipeUi())
    val ui: StateFlow<SwipeUi> = _ui.asStateFlow()

    /** Quantos arquivos dessa pasta já foram para a lixeira, e quantos bytes isso liberou. */
    val trashStats: StateFlow<TrashStats> = container.decisions
        .trashStats(folder.key)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TrashStats(0, 0))

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Avisos curtos para mostrar em toast. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        load()
    }

    /** Lê a pasta e as decisões salvas, e posiciona o cursor no primeiro arquivo não revisado. */
    fun load() {
        viewModelScope.launch {
            _ui.value = SwipeUi(loading = true)

            val items = container.media.items(folder)
            val present = items.mapTo(HashSet()) { it.key }
            val status = container.decisions.forFolder(folder.key)
                .filter { it.uri in present }
                .associate { it.uri to it.status }

            val ui = SwipeUi(loading = false, items = items, status = status)
            _ui.value = ui.copy(cursor = ui.nextUndecided(0) ?: items.size)

            // Se o app morreu no meio de um envio, termina o que ficou para trás.
            commitDue()
        }
    }

    /** Decide o card de cima: direita mantém, cima favorita, esquerda marca para a lixeira. */
    fun decide(dir: Dir) {
        val before = _ui.value
        val file = before.current ?: return
        val status = when (dir) {
            Dir.RIGHT -> Status.MANTER
            Dir.UP -> Status.FAVORITO
            Dir.LEFT -> Status.LIXEIRA_PENDENTE
        }

        _ui.update { ui ->
            val decided = ui.copy(status = ui.status + (file.key to status), enterFrom = null)
            decided.copy(cursor = decided.nextUndecided(ui.cursor + 1) ?: ui.items.size)
        }

        viewModelScope.launch {
            container.decisions.upsert(
                Decision(file.key, folder.key, status, file.size, System.currentTimeMillis()),
            )
        }

        if (status == Status.FAVORITO) {
            container.appScope.launch {
                val ok = runCatching {
                    SystemRequests.run(container.media.favoriteRequest(listOf(file.uri), true))
                }.getOrDefault(false)
                if (!ok) _messages.tryEmit("não deu pra favoritar ${file.name}")
            }
        }

        commitDue()
    }

    /**
     * Volta um card. Se ele estava no buffer da lixeira, a exclusão é cancelada;
     * se era favorito, o favorito é desfeito. Arquivos que já foram para a
     * lixeira são pulados.
     *
     * @return `false` se não havia para onde voltar.
     */
    fun rewind(): Boolean {
        val ui = _ui.value
        val target = (minOf(ui.cursor, ui.items.size) - 1 downTo 0)
            .firstOrNull { ui.statusOf(ui.items[it]) != Status.NA_LIXEIRA }
            ?: return false
        val file = ui.items[target]
        val previous = ui.statusOf(file)

        _ui.update {
            it.copy(
                status = it.status - file.key,
                cursor = target,
                // O card volta pelo mesmo lado por onde saiu.
                enterFrom = when (previous) {
                    Status.MANTER -> Dir.RIGHT
                    Status.FAVORITO -> Dir.UP
                    else -> Dir.LEFT
                },
            )
        }

        viewModelScope.launch { container.decisions.delete(file.key) }

        if (previous == Status.FAVORITO) {
            container.appScope.launch {
                runCatching {
                    SystemRequests.run(container.media.favoriteRequest(listOf(file.uri), false))
                }
            }
        }
        return true
    }

    /** Rebobina até o card no índice [index] (usado ao tocar numa miniatura do buffer). */
    fun rewindTo(index: Int) {
        while (_ui.value.cursor > index) {
            if (!rewind()) break
        }
    }

    /** A tela avisa que já animou a volta do card. */
    fun consumeEnter() {
        _ui.update { it.copy(enterFrom = null) }
    }

    /** No fim da fila, manda para a lixeira o que ainda está no buffer. */
    fun flushPending() {
        val ui = _ui.value
        sendToTrash(ui.pending.map { ui.items[it] })
    }

    /** Esquece as decisões da pasta (os arquivos não são tocados) e recomeça do início. */
    fun resetFolder() {
        viewModelScope.launch {
            container.decisions.resetFolder(folder.key)
            load()
        }
    }

    /** Envia para a lixeira todo arquivo que já está [TRASH_DELAY] posições atrás do cursor. */
    private fun commitDue() {
        val ui = _ui.value
        val due = ui.pending
            .filter { it < ui.cursor - TRASH_DELAY }
            .map { ui.items[it] }
        sendToTrash(due)
    }

    private fun sendToTrash(files: List<MediaFile>) {
        if (files.isEmpty()) return
        val keys = files.map { it.key }

        // Otimista: o arquivo sai do buffer na hora, para o rebobinar já pular por cima.
        _ui.update { ui -> ui.copy(status = ui.status + keys.associateWith { Status.NA_LIXEIRA }) }

        container.appScope.launch {
            val ok = runCatching {
                SystemRequests.run(container.media.trashRequest(files.map { it.uri }, true))
            }.getOrDefault(false)

            if (ok) {
                keys.forEach { container.decisions.setStatus(it, Status.NA_LIXEIRA) }
            } else {
                // Não foi: o arquivo volta a ser "não revisado" e aparece de novo depois.
                keys.forEach { container.decisions.delete(it) }
                _ui.update { ui -> ui.copy(status = ui.status - keys.toSet()) }
                val what = files.singleOrNull()?.name ?: "${files.size} arquivos"
                _messages.tryEmit("não deu pra mandar $what pra lixeira")
            }
        }
    }
}
