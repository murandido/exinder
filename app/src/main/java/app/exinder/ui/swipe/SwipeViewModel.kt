package app.exinder.ui.swipe

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.exinder.AppContainer
import app.exinder.TrashResult
import app.exinder.data.Decision
import app.exinder.data.FolderRef
import app.exinder.data.MediaFile
import app.exinder.data.Status
import app.exinder.data.SystemRequests
import app.exinder.data.TrashStats
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the card went (or where it comes back from on rewind). */
enum class Dir { LEFT, RIGHT, UP }

/**
 * How many decisions a file marked for deletion waits before going to the
 * trash: with 2, it goes once two more files have been decided after it.
 */
const val TRASH_DELAY = 2

/** Swipe screen state. */
data class SwipeUi(
    val loading: Boolean = true,
    /** All files in the folder, oldest to newest. */
    val items: List<MediaFile> = emptyList(),
    /** Decision for each file, by [MediaFile.key]. No entry = still pending. */
    val status: Map<String, Status> = emptyMap(),
    /** When each decision in [status] was made (a strictly increasing stamp). */
    val decidedAt: Map<String, Long> = emptyMap(),
    /** Files the app itself marked as favorites (see [Decision.appFavorited]). */
    val appFavorited: Set<String> = emptySet(),
    /**
     * Index of the card on screen. Always points to an undecided file, or
     * to `items.size` once the queue is done.
     */
    val cursor: Int = 0,
    /** Set right after a rewind, so the card animates back in from the correct side. */
    val enterFrom: Dir? = null,
    /**
     * Already-trashed files the last rewind passed over, from nearest to
     * farthest. The screen briefly shows each one as a gray "in the trash"
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
     * How many decisions were made after file [index] was marked: active
     * decisions (not already in the trash) with a later [decidedAt] stamp.
     *
     * Counting by time rather than by position keeps the grace period honest
     * when positions shift: after a rewind over trashed files the cursor skips
     * them, and between sessions files can be re-sorted (a changed date) or
     * moved to the end of the queue.
     */
    fun decisionsAfter(index: Int): Int {
        val since = decidedAt[items[index].key] ?: return 0
        return status.count { (key, s) -> s != Status.NA_LIXEIRA && (decidedAt[key] ?: 0) > since }
    }

    /** Pending files whose grace period is over: they go to the trash now. */
    val dueForTrash: List<Int>
        get() = pending.filter { decisionsAfter(it) >= TRASH_DELAY }

    /** How many more decisions until file [index] goes to the trash. */
    fun trashIn(index: Int): Int = (TRASH_DELAY - decisionsAfter(index)).coerceAtLeast(0)

    /** Rewind is possible while something behind hasn't gone to the trash yet. */
    val canRewind: Boolean
        get() = (0 until cursor.coerceAtMost(items.size))
            .any { statusOf(items[it]) != Status.NA_LIXEIRA }

    fun count(status: Status): Int = this.status.values.count { it == status }

    /**
     * State after a trash request for [keys] failed and the files are still on
     * the device: they become undecided again, and if one of them is behind the
     * cursor the stack goes back to it, so the user sees it right away instead
     * of a summary that says the folder is done.
     */
    fun afterTrashFailed(keys: Set<String>): SwipeUi {
        val reverted = copy(status = status - keys, decidedAt = decidedAt - keys)
        val back = reverted.nextUndecided(0)
        return if (back != null && back < cursor) reverted.copy(cursor = back, enterFrom = Dir.LEFT) else reverted
    }

    /** First undecided file starting at [from], or `null`. */
    fun nextUndecided(from: Int): Int? = (from until items.size).firstOrNull { statusOf(items[it]) == null }
}

/** A short message for the screen to show as a toast; the screen picks the text. */
sealed interface UiMessage {
    data class FavoriteFailed(val name: String) : UiMessage

    /** [name] is set when a single file failed; otherwise [count] files did. */
    data class TrashFailed(val name: String?, val count: Int) : UiMessage
}

/**
 * The heart of the app: a folder's queue, the decisions and the deferred trash.
 *
 * ### The 2-file rule
 * A left swipe only marks the file as [Status.LIXEIRA_PENDENTE].
 * It actually goes to the trash once [TRASH_DELAY] more files have been
 * decided after it. Until then, rewind undoes everything without the file ever having
 * moved.
 */
class SwipeViewModel(private val container: AppContainer, val folder: FolderRef) : ViewModel() {

    private val _ui = MutableStateFlow(SwipeUi())
    val ui: StateFlow<SwipeUi> = _ui.asStateFlow()

    /** How many files from this folder have gone to the trash, and how many bytes that freed. */
    val trashStats: StateFlow<TrashStats> = container.decisions
        .trashStats(folder.key)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TrashStats(0, 0))

    // Channels rather than SharedFlows: an event sent while the screen isn't
    // collecting (the Activity is being recreated behind a system dialog) waits
    // in the buffer instead of being dropped.
    private val _messages = Channel<UiMessage>(Channel.BUFFERED)

    /** Short messages to show as toasts. */
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    /** Last decision stamp handed out; see [stamp]. */
    private var lastStamp = 0L

    /** Current time, but always later than the previous decision, even within the same millisecond. */
    private fun stamp(): Long = maxOf(System.currentTimeMillis(), lastStamp + 1).also { lastStamp = it }

    private val _trashed = Channel<Int>(Channel.BUFFERED)

    /** Reports how many files just went to the trash, so the screen can vibrate. */
    val trashed: Flow<Int> = _trashed.receiveAsFlow()

    init {
        // Trash requests outlive the ViewModel that started them (they run in the
        // app scope), so their outcome is published process-wide and applied by
        // whichever ViewModel is showing this folder now.
        viewModelScope.launch {
            container.trashResults.filter { it.folderKey == folder.key }.collect(::onTrashResult)
        }
        load()
    }

    /**
     * Loads the folder and the saved decisions, and puts the cursor on the first
     * unreviewed file. Runs once per visit to the folder (the screen gets a new
     * ViewModel every time the folder is opened) and after a reset.
     */
    fun load() {
        viewModelScope.launch {
            _ui.value = SwipeUi(loading = true)

            // Snapshot taken before reading MediaStore: a request that finishes
            // while the folder is being read still counts as in flight here, so its
            // file can't be mistaken for one the user restored from the trash.
            val inFlight = container.trashInFlight.toSet()
            val found = container.media.items(folder)
            val files = found.associateBy { it.key }

            // Decisions are matched by file, not only by folder: a kept file moved
            // here from another folder keeps its decision, which moves along with it.
            val all = container.decisions.all()
            val movedIn = all.filter {
                it.folderKey != folder.key && it.uri in files
            }.map { it.copy(folderKey = folder.key) }
            movedIn.forEach { container.decisions.upsert(it) }
            val decisions = all.filter { it.folderKey == folder.key } + movedIn

            // A decision whose file now has a different size belongs to some
            // other file that reused the MediaStore ID (the media database was
            // rebuilt, or the app data came from another phone). It is dropped
            // instead of being applied to a file the user never saw.
            val (foreign, matching) = decisions.partition { d -> files[d.uri]?.let { it.size != d.size } == true }
            foreign.forEach { container.decisions.delete(it.uri) }

            // "In the trash" but still in the folder: the user restored it from the
            // gallery. The old decision is forgotten and the file goes back in the queue.
            val restored = matching.filter { it.status == Status.NA_LIXEIRA && it.uri in files && it.uri !in inFlight }
            restored.forEach { container.decisions.delete(it.uri) }

            // Marked for the trash and gone from the folder. Only judged when the
            // folder's storage is mounted (an SD card may just be out).
            if (container.media.isVolumeMounted(folder.volume)) {
                matching
                    .filter { it.status == Status.LIXEIRA_PENDENTE && it.uri !in files && it.uri !in inFlight }
                    .forEach { d ->
                        when (container.media.exists(d.uri.toUri())) {
                            // Moved to another folder: it was never trashed.
                            true -> container.decisions.delete(d.uri)

                            // The app died between the file going to the trash and
                            // recording it. Record it now so the counters are right.
                            false -> container.decisions.upsert(d.copy(status = Status.NA_LIXEIRA))

                            // Couldn't tell: leave the record alone and decide next time.
                            null -> Unit
                        }
                    }
            }

            val live = matching.filter { it.uri in files && it !in restored }
            // A trash request still in progress (started before this load) counts
            // as done, so it isn't sent a second time.
            val status = live.associate { d -> d.uri to if (d.uri in inFlight) Status.NA_LIXEIRA else d.status }
            val decidedAt = live.associate { it.uri to it.decidedAt }
            lastStamp = maxOf(lastStamp, live.maxOfOrNull { it.decidedAt } ?: 0L)

            val trashedEarlier = container.decisions.forFolder(folder.key).count {
                it.status == Status.NA_LIXEIRA && it.uri !in files
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

            val ui = SwipeUi(
                loading = false,
                items = items,
                status = status,
                decidedAt = decidedAt,
                appFavorited = live.filter { it.appFavorited }.mapTo(HashSet()) { it.uri },
                trashedEarlier = trashedEarlier,
            )
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

        val at = stamp()
        // Already a favorite before the swipe: nothing to ask the system for.
        val setsFavorite = status == Status.FAVORITO && !file.isFavorite

        _ui.update { ui ->
            val decided = ui.copy(
                status = ui.status + (file.key to status),
                decidedAt = ui.decidedAt + (file.key to at),
                appFavorited = if (setsFavorite) ui.appFavorited + file.key else ui.appFavorited,
                enterFrom = null,
                // Moving on: any gray cards from an earlier rewind are no longer relevant.
                passedTrash = emptyList(),
            )
            decided.copy(cursor = decided.nextUndecided(ui.cursor + 1) ?: ui.items.size)
        }

        viewModelScope.launch {
            container.decisions.upsert(
                Decision(file.key, folder.key, status, file.size, at, appFavorited = setsFavorite),
            )
        }

        if (setsFavorite) {
            container.appScope.launch {
                val ok = runCatching {
                    SystemRequests.run(container.media.favoriteRequest(listOf(file.uri), true))
                }.getOrDefault(false)
                if (!ok) _messages.trySend(UiMessage.FavoriteFailed(file.name))
            }
        }

        commitDue()
    }

    /**
     * Goes back one card. If it was in the trash queue, the deletion is cancelled;
     * if the app favorited it, the favorite is undone (a favorite the file already
     * had stays). Files that already went to the
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

        val undoFavorite = previous == Status.FAVORITO && file.key in ui.appFavorited

        _ui.update {
            it.copy(
                status = it.status - file.key,
                decidedAt = it.decidedAt - file.key,
                appFavorited = it.appFavorited - file.key,
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
        // already had before the swipe stays put. Stored in the database, so
        // this holds across sessions too.
        if (undoFavorite) {
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

    /** The screen reports that it has shown the gray "in the trash" cards. */
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

    /** Sends to the trash every pending file whose grace period is over. */
    private fun commitDue() {
        val ui = _ui.value
        sendToTrash(ui.dueForTrash.map { ui.items[it] })
    }

    /** Applies the outcome of a trash request (possibly started by an earlier ViewModel). */
    private fun onTrashResult(result: TrashResult) {
        if (result.trashed > 0) _trashed.trySend(result.trashed)
        if (result.stayed.isNotEmpty()) {
            _ui.update { it.afterTrashFailed(result.stayed.map { f -> f.key }.toSet()) }
            _messages.trySend(UiMessage.TrashFailed(result.stayed.singleOrNull()?.name, result.stayed.size))
        }
    }

    private fun sendToTrash(candidates: List<MediaFile>) {
        // Never two requests for the same file at once (see AppContainer.trashInFlight).
        val files = candidates.filter { container.trashInFlight.add(it.key) }
        if (files.isEmpty()) return
        val keys = files.map { it.key }
        val stamps = _ui.value.decidedAt

        // Optimistic: the file leaves the trash queue right away, so rewind already skips it.
        _ui.update { ui -> ui.copy(status = ui.status + keys.associateWith { Status.NA_LIXEIRA }) }

        container.appScope.launch {
            try {
                val ok = runCatching {
                    SystemRequests.run(container.media.trashRequest(files.map { it.uri }, true))
                }.getOrDefault(false)

                // Whatever the request said, what counts is where each file is now:
                // a request reported as failed may still have gone through. If
                // MediaStore can't tell, the file is treated as still there.
                val (gone, stayed) = if (ok) {
                    files to emptyList()
                } else {
                    files.partition { container.media.exists(it.uri) == false }
                }

                // Written as full rows, so they land even if the folder was reset meanwhile.
                gone.forEach { f ->
                    container.decisions.upsert(
                        Decision(f.key, folder.key, Status.NA_LIXEIRA, f.size, stamps[f.key] ?: stamp()),
                    )
                }
                // Still here: the file becomes "unreviewed" again.
                stayed.forEach { container.decisions.delete(it.key) }

                container.trashResults.emit(TrashResult(folder.key, gone.size, stayed))
            } finally {
                container.trashInFlight.removeAll(keys.toSet())
            }
        }
    }
}
