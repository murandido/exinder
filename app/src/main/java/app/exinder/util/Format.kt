package app.exinder.util

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatação de datas, tamanhos e números no jeito brasileiro. */

private val ptBR: Locale = Locale.forLanguageTag("pt-BR")

/** `14 mar 2019`: a linha grande do card, o "nome, idade" do Tinder. */
fun formatDate(ms: Long): String =
    SimpleDateFormat("d MMM yyyy", ptBR).format(Date(ms)).replace(".", "")

/** `14 mar 2019, 15:32`: usado na tela cheia. */
fun formatDateTime(ms: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", ptBR).format(Date(ms)).replace(".", "")

/** `3,2 MB`, `1,4 GB`… */
fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes < kb -> "$bytes B"
        bytes < mb -> String.format(ptBR, "%.0f KB", bytes / kb)
        bytes < gb -> String.format(ptBR, "%.1f MB", bytes / mb)
        else -> String.format(ptBR, "%.1f GB", bytes / gb)
    }
}

/** `1.234` */
fun formatCount(n: Int): String = NumberFormat.getIntegerInstance(ptBR).format(n)

/** `0:15`, `12:03`, `1:02:45` */
fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
