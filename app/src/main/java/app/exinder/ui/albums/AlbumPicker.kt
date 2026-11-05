package app.exinder.ui.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.exinder.R
import app.exinder.data.AlbumDestination
import app.exinder.data.AlbumSender
import app.exinder.data.AlbumSummary

/**
 * Bottom sheet to put the current file in an album: the existing albums, plus
 * "new album". Picking one calls [onPick]; dismissing it leaves the card alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPicker(
    albums: List<AlbumSummary>,
    onPick: (albumId: Long) -> Unit,
    onCreate: (name: String, destination: AlbumDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.picker_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn {
                item {
                    PickerRow(
                        icon = {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        title = stringResource(R.string.picker_new),
                        subtitle = null,
                        onClick = { creating = true },
                    )
                }
                items(albums, key = { it.id }) { album ->
                    PickerRow(
                        icon = { DestinationIcon(album.destination) },
                        title = album.name,
                        subtitle = destinationLabel(album.destination, album.name),
                        onClick = { onPick(album.id) },
                    )
                }
            }
        }
    }

    if (creating) {
        NewAlbumDialog(
            onCreate = { name, destination ->
                creating = false
                onCreate(name, destination)
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun PickerRow(icon: @Composable () -> Unit, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon()
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Phone or cloud icon for an album's destination. */
@Composable
fun DestinationIcon(destination: AlbumDestination, modifier: Modifier = Modifier) {
    Icon(
        if (destination == AlbumDestination.PHONE) Icons.Rounded.PhoneAndroid else Icons.Rounded.Cloud,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(24.dp),
    )
}

/** "moved to Pictures/Trip" or "Google Photos". */
@Composable
fun destinationLabel(destination: AlbumDestination, name: String): String = when (destination) {
    AlbumDestination.PHONE -> stringResource(R.string.dest_phone_desc, AlbumSender.folderName(name))
    AlbumDestination.GOOGLE_PHOTOS -> stringResource(R.string.dest_google)
}

/** Name and destination for a new album. */
@Composable
private fun NewAlbumDialog(onCreate: (String, AlbumDestination) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf(AlbumDestination.PHONE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_album_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.album_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                DestinationOption(
                    selected = destination == AlbumDestination.PHONE,
                    title = stringResource(R.string.dest_phone),
                    subtitle = stringResource(R.string.dest_phone_desc, AlbumSender.folderName(name)),
                    onSelect = { destination = AlbumDestination.PHONE },
                )
                DestinationOption(
                    selected = destination == AlbumDestination.GOOGLE_PHOTOS,
                    title = stringResource(R.string.dest_google),
                    subtitle = stringResource(R.string.dest_google_desc),
                    onSelect = { destination = AlbumDestination.GOOGLE_PHOTOS },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, destination) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DestinationOption(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
