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

/** Where the card went (or where it comes back from on rewind). */
enum class Dir { LEFT, RIGHT, UP }

/**
 * How many positions a file marked for deletion waits before going to the
 * trash. With 2, file N goes when file N+2 is decided.
 */
const val TRASH_DELAY = 2

/** Swipe screen state. */
data class SwipeUi(
    val loading: Boolean = true,
    /** All files in the folder, oldest to newest. */
    val items: List<MediaFile> = emptyList(),
    /** Decision for each file, by [MediaFile.key]. No entry = still pending. */
    val status: Map<String, Status> = emptyMap(),
    /**
     * Index of the card on screen. Always points to an undecided file, or
     * to `items.size` once the queue is done.
     */
    val cursor: Int = 0,
    /** Set right after a rewind, so the card animates back in from the correct side. */
    val enterFrom: Dir? = null,
    /**
     * Already-trashed files the last rewind passed over, from nearest to
     * farthest. The screen briefly shows each one as a gray "na lixeira" (in trash)
     * card, then clears the list.
     */
    val passedTrash: List<MediaFile> = emptyList(),
    /**
     * Files from this folder sent to the trash in earlier sessions. They no
     * longer appear in [items], but they count toward the "N / total" counter so
     * the total doesn't shrink every time the app is opened.
     */
    val trashedEarlier: Int = 0,
) {
    /** Total files in the folder, including those already sent to the trash. */
    val total: Int get() = trashedEarlier + items.size

    /** Position of the on-screen card within [total], starting at 1. */
    val position: Int get() = trashedEarlier + minOf(cursor + 1, items.size)

    fun statusOf(file: MediaFile): Status? = status[file.key]

    /** The top card of the stack. */
    val current: MediaFile? get() = items.getOrNull(cursor)

    /** The card showing right behind it. */
    val next: MediaFile? get() = nextUndecided(cursor + 1)?.let(items::get)

    /** The card after the back one, not on screen yet (it is preloaded). */
    val afterNext: MediaFile?
        get() = nextUndecided(cursor + 1)?.let { nextUndecided(it + 1) }?.let(items::get)

    /** Indices of the files in the trash queue, waiting their turn to go to the trash. */
    val pending: List<Int>
        get() = (0 until cursor.coerceAtMost(items.size))
            .filter { statusOf(items[it]) == Status.LIXEIRA_PENDENTE }

    /**
     * How many decisions have been made after file [index]: counts the files
     * between it and the cursor that have an active decision. Files already in
     * the trash don't count, since they were decided earlier (the cursor skips
     * over them after a rewind). Counting by position would give a file
     * zero grace period in that case.
     */
    fun decisionsAfter(index: Int): Int =
        (index + 1 until cursor.coerceAtMost(items.size)).count {
            val s = statusOf(items[it])
            s != null && s != Status.NA_LIXEIRA
        }

    /** How many more decisions until file [index] goes to the trash. */
    fun trashIn(index: Int): Int = (TRASH_DELAY - decisionsAfter(index)).coerceAtLeast(0)

    /** Rewind is possible while something behind hasn't gone to the trash yet. */
    val canRewind: Boolean
        get() = (0 until cursor.coerceAtMost(items.size))
            .any { statusOf(items[it]) != Status.NA_LIXEIRA }

    fun count(status: Status): Int = this.status.values.count { it == status }

    /** First undecided file starting at [from], or `null`. */
    fun nextUndecided(from: Int): Int? = (from until items.size).firstOrNull { statusOf(items[it]) == null }
}

/**
 * The heart of the app: a folder's queue, the decisions and the deferred trash.
 *
 * ### The 2-file rule
 * A left swipe only marks the file as [Status.LIXEIRA_PENDENTE].
 * It actually goes to the trash once the cursor is [TRASH_DELAY] positions
 * past it. Until then, rewind undoes everything without the file ever having
 * moved.
 */
class SwipeViewModel(
    private val container: AppContainer,
    val folder: FolderRef,
) : ViewModel() {

    private val _ui = MutableStateFlow(SwipeUi())
    val ui: StateFlow<SwipeUi> = _ui.asStateFlow()

    /** How many files from this folder have gone to the trash, and how many bytes that freed. */
    val trashStats: StateFlow<TrashStats> = container.decisions
        .trashStats(folder.key)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TrashStats(0, 0))

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Short messages to show as toasts. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _trashed = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** Reports how many files just went to the trash, so the screen can vibrate. */
    val trashed: SharedFlow<Int> = _trashed.asSharedFlow()

    /**
     * Loads the folder and the saved decisions, and puts the cursor on the first
     * unreviewed file. The screen calls this every time the folder is opened, to
     * pick up files added or deleted elsewhere since last time.
     */
    fun load() {
        viewModelScope.launch {
            _ui.value = SwipeUi(loading = true)

            val found = container.media.items(folder)
            val present = found.mapTo(HashSet()) { it.key }
            val decisions = container.decisions.forFolder(folder.key)

            // "In trash" but still in the folder: the user restored the file from the
            // gallery. The old decision is forgotten and it goes back into the queue.
            val restored = decisions.filter { it.status == Status.NA_LIXEIRA && it.uri in present }
            restored.forEach { container.decisions.delete(it.uri) }

            val status = decisions
                .filter { it.uri in present && it !in restored }
                .associate { it.uri to it.status }

            // Marked for the trash and gone from the folder: the app died between the
            // file going to the trash and recording it. Record it now so the counter
            // and the "liberados" (freed) total are right (and so the file, if restored
            // later, hits the case above instead of going back to the trash).
            decisions
                .filter { it.status == Status.LIXEIRA_PENDENTE && it.uri !in present }
                .forEach { container.decisions.setStatus(it.uri, Status.NA_LIXEIRA) }
            val trashedEarlier = decisions.count {
                it.uri !in present && (it.status == Status.NA_LIXEIRA || it.status == Status.LIXEIRA_PENDENTE)
            }

            // Undecided files that sit behind the point where the user left off
            // (added later with an old date, or restored) go to the end of the
            // queue, instead of interrupting the review back at the start.
            val lastDecided = found.indexOfLast { it.key in status }
            val items = if (lastDecided < 0) {
                found
            } else {
                val (late, rest) = found.withIndex().partition { (i, f) -> i < lastDecided && f.key !in status }
                rest.map { it.value } + late.map { it.value }
            }

            val ui = SwipeUi(loading = false, items = items, status = status, trashedEarlier = trashedEarlier)
            _ui.value = ui.copy(cursor = ui.nextUndecided(0) ?: items.size)

            // If the app died mid-send, finish what was left behind.
            commitDue()
        }
    }

    /**
     * Decides the top card: right keeps, up favorites, left marks for the trash.
     *
     * @param key the card the user decided on. The decision arrives after the
     *   exit animation; if the top card changed in the meantime (a tap on a
     *   trash queue thumbnail, for example), it is dropped instead of landing
     *   on the wrong card.
     */
    fun decide(dir: Dir, key: String) {
        val before = _ui.value
        val file = before.current ?: return
        if (file.key != key) return
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

        // Already a favorite: nothing to ask the system for.
        if (status == Status.FAVORITO && !file.isFavorite) {
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
     * Goes back one card. If it was in the trash queue, the deletion is cancelled;
     * if it was favorited, the favorite is undone. Files that already went to the
     * trash are skipped.
     *
     * @return `false` if there was nothing to go back to.
     */
    fun rewind(): Boolean {
        val ui = _ui.value
        val target = (minOf(ui.cursor, ui.items.size) - 1 downTo 0)
            .firstOrNull { ui.statusOf(ui.items[it]) != Status.NA_LIXEIRA }
            ?: return false
        val file = ui.items[target]
        val previous = ui.statusOf(file)
        val skipped = (minOf(ui.cursor, ui.items.size) - 1 downTo target + 1).map { ui.items[it] }

        _ui.update {
            it.copy(
                status = it.status - file.key,
                cursor = target,
                // Accumulates: rewinding to a thumbnail calls this function several times.
                passedTrash = it.passedTrash + skipped,
                // The card comes back from the same side it left.
                enterFrom = when (previous) {
                    Status.MANTER -> Dir.RIGHT
                    Status.FAVORITO -> Dir.UP
                    else -> Dir.LEFT
                },
            )
        }

        viewModelScope.launch { container.decisions.delete(file.key) }

        // Only undo a favorite the app itself set; a favorite the file
        // already had before the swipe stays put.
        if (previous == Status.FAVORITO && !file.isFavorite) {
            container.appScope.launch {
                runCatching {
                    SystemRequests.run(container.media.favoriteRequest(listOf(file.uri), false))
                }
            }
        }
        return true
    }

    /** Rewinds to the card at [index] (used when tapping a trash queue thumbnail). */
    fun rewindTo(index: Int) {
        while (_ui.value.cursor > index) {
            if (!rewind()) break
        }
    }

    /** The screen reports that it has animated the card back in. */
    fun consumeEnter() {
        _ui.update { it.copy(enterFrom = null) }
    }

    /** The screen reports that it has shown the gray "na lixeira" cards. */
    fun consumePassedTrash() {
        _ui.update { it.copy(passedTrash = emptyList()) }
    }

    /** At the end of the queue, sends whatever is still in the trash queue to the trash. */
    fun flushPending() {
        val ui = _ui.value
        sendToTrash(ui.pending.map { ui.items[it] })
    }

    /** Forgets the folder's decisions (files are left untouched) and starts over. */
    fun resetFolder() {
        viewModelScope.launch {
            container.decisions.resetFolder(folder.key)
            load()
        }
    }

    /** Sends to the trash every file that is already [TRASH_DELAY] positions behind the cursor. */
    private fun commitDue() {
        val ui = _ui.value
        val due = ui.pending
            .filter { ui.decisionsAfter(it) >= TRASH_DELAY }
            .map { ui.items[it] }
        sendToTrash(due)
    }

    private fun sendToTrash(files: List<MediaFile>) {
        if (files.isEmpty()) return
        val keys = files.map { it.key }

        // Optimistic: the file leaves the trash queue right away, so rewind already skips it.
        _ui.update { ui -> ui.copy(status = ui.status + keys.associateWith { Status.NA_LIXEIRA }) }

        container.appScope.launch {
            val ok = runCatching {
                SystemRequests.run(container.media.trashRequest(files.map { it.uri }, true))
            }.getOrDefault(false)

            if (ok) {
                _trashed.tryEmit(files.size)
                keys.forEach { container.decisions.setStatus(it, Status.NA_LIXEIRA) }
            } else {
                // It didn't go: the file becomes "unreviewed" again and shows up later.
                keys.forEach { container.decisions.delete(it) }
                _ui.update { ui -> ui.copy(status = ui.status - keys.toSet()) }
                val what = files.singleOrNull()?.name ?: "${files.size} arquivos"
                _messages.tryEmit("não deu pra mandar $what pra lixeira")
            }
        }
    }
}
