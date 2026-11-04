package app.exinder.ui.swipe

import android.net.Uri
import app.exinder.data.MediaFile
import app.exinder.data.MediaKind
import app.exinder.data.Status
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trash queue rules in [SwipeUi]: which files go to the trash and when.
 * These are the rules that decide whether a real file leaves the user's
 * folder, so the scenarios found by the review courts are pinned here.
 */
class SwipeUiTest {

    private fun file(id: Int): MediaFile {
        val uri = mockk<Uri> { every { this@mockk.toString() } returns "content://media/external/images/media/$id" }
        return MediaFile(
            id = id.toLong(),
            uri = uri,
            name = "photo$id.jpg",
            mime = "image/jpeg",
            size = 1_000L * id,
            takenAt = id.toLong(),
            durationMs = 0,
            kind = MediaKind.IMAGE,
        )
    }

    /** A decision on the file at an index, made at a given moment. */
    private data class D(val index: Int, val status: Status, val at: Long)

    private fun ui(count: Int, cursor: Int, vararg decisions: D, trashedEarlier: Int = 0): SwipeUi {
        val items = (0 until count).map(::file)
        return SwipeUi(
            loading = false,
            items = items,
            status = decisions.associate { items[it.index].key to it.status },
            decidedAt = decisions.associate { items[it.index].key to it.at },
            cursor = cursor,
            trashedEarlier = trashedEarlier,
        )
    }

    @Test
    fun `a marked file waits for two more decisions`() {
        val marked = ui(5, cursor = 1, D(0, Status.LIXEIRA_PENDENTE, at = 1))
        assertEquals(listOf(0), marked.pending)
        assertEquals(2, marked.trashIn(0))
        assertTrue(marked.dueForTrash.isEmpty())

        val oneLater = ui(5, cursor = 2, D(0, Status.LIXEIRA_PENDENTE, 1), D(1, Status.MANTER, 2))
        assertEquals(1, oneLater.trashIn(0))
        assertTrue(oneLater.dueForTrash.isEmpty())

        val twoLater =
            ui(5, cursor = 3, D(0, Status.LIXEIRA_PENDENTE, 1), D(1, Status.MANTER, 2), D(2, Status.FAVORITO, 3))
        assertEquals(0, twoLater.trashIn(0))
        assertEquals(listOf(0), twoLater.dueForTrash)
    }

    @Test
    fun `other files marked for the trash count as decisions`() {
        val state = ui(
            4,
            cursor = 3,
            D(0, Status.LIXEIRA_PENDENTE, 1),
            D(1, Status.LIXEIRA_PENDENTE, 2),
            D(2, Status.LIXEIRA_PENDENTE, 3),
        )
        assertEquals(listOf(0), state.dueForTrash)
        assertEquals(1, state.trashIn(1))
        assertEquals(2, state.trashIn(2))
    }

    @Test
    fun `files already in the trash give no grace (first court)`() {
        // Files 1 and 2 went to the trash; the user rewound to file 0 and marked
        // it. The cursor skips the trashed files and lands on 3, but no decision
        // has been made after file 0 yet.
        val state = ui(
            6,
            cursor = 3,
            D(1, Status.NA_LIXEIRA, 1),
            D(2, Status.NA_LIXEIRA, 2),
            D(0, Status.LIXEIRA_PENDENTE, 10),
        )
        assertEquals(0, state.decisionsAfter(0))
        assertTrue(state.dueForTrash.isEmpty())
    }

    @Test
    fun `re-sorting the folder does not shorten the grace (second court)`() {
        // Files A and B were decided before F was marked, but a date change
        // moved F in front of them in the queue.
        val state = ui(
            4,
            cursor = 3,
            D(0, Status.LIXEIRA_PENDENTE, at = 30),
            D(1, Status.MANTER, at = 10),
            D(2, Status.MANTER, at = 20),
        )
        assertEquals(0, state.decisionsAfter(0))
        assertTrue(state.dueForTrash.isEmpty())
    }

    @Test
    fun `only files behind the cursor are pending`() {
        val state = ui(4, cursor = 1, D(0, Status.MANTER, 1), D(2, Status.LIXEIRA_PENDENTE, 2))
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun `rewind is possible only over files not in the trash`() {
        assertFalse(ui(3, cursor = 2, D(0, Status.NA_LIXEIRA, 1), D(1, Status.NA_LIXEIRA, 2)).canRewind)
        assertTrue(ui(3, cursor = 2, D(0, Status.MANTER, 1), D(1, Status.NA_LIXEIRA, 2)).canRewind)
        assertFalse(ui(3, cursor = 0).canRewind)
    }

    @Test
    fun `the stack skips decided files`() {
        val state = ui(5, cursor = 0, D(1, Status.MANTER, 1), D(2, Status.NA_LIXEIRA, 2))
        assertEquals("photo0.jpg", state.current?.name)
        assertEquals("photo3.jpg", state.next?.name)
        assertEquals("photo4.jpg", state.afterNext?.name)
        assertEquals(3, state.nextUndecided(1))
        assertNull(ui(2, cursor = 0, D(1, Status.MANTER, 1)).afterNext)
    }

    @Test
    fun `the counter includes files trashed in earlier sessions`() {
        val state = ui(8, cursor = 2, D(0, Status.MANTER, 1), D(1, Status.MANTER, 2), trashedEarlier = 2)
        assertEquals(10, state.total)
        assertEquals(5, state.position)

        val done = ui(2, cursor = 2, D(0, Status.MANTER, 1), D(1, Status.MANTER, 2), trashedEarlier = 1)
        assertEquals(3, done.position)
        assertNull(done.current)
    }
}
