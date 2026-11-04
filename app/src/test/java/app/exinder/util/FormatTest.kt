package app.exinder.util

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FormatTest {

    private lateinit var original: Locale

    @Before
    fun setUp() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(original)
    }

    @Test
    fun `sizes pick the right unit`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2048))
        assertEquals("3.5 MB", formatBytes(3_670_016))
        assertEquals("1.4 GB", formatBytes(1_503_238_554))
    }

    @Test
    fun `rounding moves up to the next unit instead of showing 1024`() {
        assertEquals("1.0 MB", formatBytes(1_048_575))
        assertEquals("1.0 GB", formatBytes(1_073_700_000))
    }

    @Test
    fun `numbers and sizes follow the locale`() {
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
        assertEquals("1.234", formatCount(1234))
        assertEquals("3,5 MB", formatBytes(3_670_016))
    }

    @Test
    fun `durations`() {
        assertEquals("0:15", formatDuration(15_000))
        assertEquals("12:03", formatDuration(723_000))
        assertEquals("1:02:45", formatDuration(3_765_000))
    }
}
