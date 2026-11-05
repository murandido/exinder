package app.exinder.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumSenderTest {

    @Test
    fun `album names become safe folder names`() {
        assertEquals("Trip 2025", AlbumSender.folderName("  Trip 2025  "))
        assertEquals("memes_funny", AlbumSender.folderName("memes/funny"))
        assertEquals("a_b_c_d", AlbumSender.folderName("a:b*c?d"))
        assertEquals("Album", AlbumSender.folderName("..."))
        assertEquals("Album", AlbumSender.folderName("   "))
    }
}
