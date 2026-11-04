package app.exinder.ui.permissions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import app.exinder.R
import app.exinder.hasMediaPermission
import app.exinder.hasPartialMediaAccess
import app.exinder.mediaPermissions
import app.exinder.ui.theme.Brat
import app.exinder.ui.theme.ExinderLogo
import app.exinder.ui.theme.Ink

/**
 * First screen: requests the two permissions the app needs.
 *
 * 1. **Photos and videos**: required; without it there is nothing to review.
 * 2. **Media management**: recommended. Without it, Android asks
 *    "move to trash?" for every file, which kills the swipe rhythm.
 */
@Composable
fun PermissionsScreen(hasMedia: Boolean, canManage: Boolean, onRecheck: () -> Unit, onSkipManage: () -> Unit) {
    val context = LocalContext.current

    // After two denials, Android stops showing the dialog and the request comes
    // back denied immediately. In that case the button opens the app's settings.
    //
    // Telling "blocked" apart from "dialog dismissed with back": a dismissal
    // also returns denied with no rationale, but only a real denial flips the
    // rationale flag on. So the request counts as blocked when the rationale
    // was on before and is off after (the second denial), or when a request
    // returns denied without a rationale twice in a row (blocked from the start,
    // e.g. denied in Settings).
    var blocked by rememberSaveable { mutableStateOf(false) }
    var silentDenials by rememberSaveable { mutableIntStateOf(0) }
    var rationaleBefore by rememberSaveable { mutableStateOf(false) }
    val activity = context as? Activity
    fun rationale(): Boolean = activity != null &&
        ActivityCompat.shouldShowRequestPermissionRationale(activity, mediaPermissions().first())
    val requestMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onRecheck()
        val denied = !hasMediaPermission(context) && !hasPartialMediaAccess(context)
        val rationaleAfter = rationale()
        if (denied && !rationaleAfter) silentDenials++ else silentDenials = 0
        blocked = denied && !rationaleAfter && (rationaleBefore || silentDenials >= 2)
    }
    val launchRequest = {
        rationaleBefore = rationale()
        requestMedia.launch(mediaPermissions())
    }
    val partial = !hasMedia && hasPartialMediaAccess(context)
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ExinderLogo(size = 64.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tagline),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))

        PermissionStep(
            icon = Icons.Rounded.PhotoLibrary,
            title = stringResource(R.string.perm_media_title),
            description = when {
                // "Limited access": requesting again lets the user switch to "allow all".
                partial -> stringResource(R.string.perm_media_partial)

                blocked -> stringResource(R.string.perm_media_blocked)

                else -> stringResource(R.string.perm_media_desc)
            },
            granted = hasMedia,
            action = stringResource(if (blocked && !partial) R.string.action_open_settings else R.string.action_allow),
            onAction = { if (blocked && !partial) openAppSettings() else launchRequest() },
        )
        Spacer(Modifier.height(12.dp))
        PermissionStep(
            icon = Icons.Rounded.DeleteSweep,
            title = stringResource(R.string.perm_manage_title),
            description = stringResource(R.string.perm_manage_desc),
            granted = canManage,
            action = stringResource(R.string.action_open_settings),
            onAction = {
                // Some devices don't have this settings page; fall back to the
                // app's settings rather than crashing.
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, "package:${context.packageName}".toUri()),
                    )
                } catch (_: ActivityNotFoundException) {
                    openAppSettings()
                }
            },
        )

        // The second permission is optional, and only offered after the first.
        if (hasMedia && !canManage) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onSkipManage) {
                Text(stringResource(R.string.action_skip_manage), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A card explaining a permission, with the button that requests it. */
@Composable
private fun PermissionStep(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Brat, modifier = Modifier.size(32.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.cd_granted),
                tint = Brat,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Brat, contentColor = Ink),
            ) { Text(action, fontWeight = FontWeight.Bold) }
        }
    }
}
