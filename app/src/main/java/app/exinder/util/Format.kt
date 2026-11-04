package app.exinder.util

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Date, size and number formatting in the app's current language. The locale is
 * read on every call, so a language change (per-app language on Android 13+,
 * which recreates the Activity) is picked up right away.
 */

private val locale: Locale get() = Locale.getDefault()

/** `14 Mar 2019`: the card's big line, Tinder's "name, age". */
fun formatDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", locale).format(Date(ms)).replace(".", "")

/** `14 Mar 2019, 15:32`: used in the fullscreen viewer. */
fun formatDateTime(ms: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(ms)).replace(".", "")

/** `3.2 MB`, `1.4 GB`… (decimal separator follows the locale). */
fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    // The thresholds sit slightly below the next unit, so rounding doesn't
    // produce "1024 KB" or "1024.0 MB" instead of "1.0 MB" or "1.0 GB".
    return when {
        bytes < kb -> "$bytes B"
        bytes < mb - kb / 2 -> String.format(locale, "%.0f KB", bytes / kb)
        bytes < gb - mb / 20 -> String.format(locale, "%.1f MB", bytes / mb)
        else -> String.format(locale, "%.1f GB", bytes / gb)
    }
}

/** `1,234` (grouping follows the locale). */
fun formatCount(n: Int): String = NumberFormat.getIntegerInstance(locale).format(n)

/** `0:15`, `12:03`, `1:02:45` */
fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
