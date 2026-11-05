package app.exinder.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import app.exinder.R
import app.exinder.data.MediaFile
import app.exinder.data.MediaKind

/**
 * Opens Android's share sheet with [file] itself (not a copy): the receiving app
 * gets temporary read access to the MediaStore URI, so nothing leaves the device
 * unless the user picks an app that sends it somewhere.
 */
fun shareMedia(context: Context, file: MediaFile) {
    val type = file.mime.ifEmpty { if (file.kind == MediaKind.VIDEO) "video/*" else "image/*" }
    val send = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        putExtra(Intent.EXTRA_STREAM, file.uri)
        // The read grant travels with the ClipData, which the chooser passes on
        // to whichever app the user picks.
        clipData = ClipData.newRawUri(file.name, file.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_title)))
    } catch (_: ActivityNotFoundException) {
        // No app on the device can receive it; nothing to do.
    }
}
