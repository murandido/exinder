package app.exinder.ui.albums

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.exinder.AppContainer
import app.exinder.R
import app.exinder.data.AlbumDestination
import app.exinder.data.AlbumItem
import app.exinder.data.AlbumItemState
import app.exinder.data.AlbumSender
import app.exinder.data.AlbumSummary
import app.exinder.ui.theme.Brat
import app.exinder.ui.theme.Ink
import app.exinder.util.formatCount
import coil3.compose.AsyncImage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The list of albums, and the album being sent. */
class AlbumsViewModel(private val container: AppContainer) : ViewModel() {

    val albums: StateFlow<List<AlbumSummary>?> = container.albums.summaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun items(albumId: Long) = container.albums.items(albumId)

    /** `done / total` while an album is being sent, `null` otherwise. */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    private val _outcomes = Channel<AlbumSender.Outcome>(Channel.BUFFERED)
    val outcomes = _outcomes.receiveAsFlow()

    fun send(albumId: Long, trashOriginals: Boolean) {
        if (_progress.value != null) return
        _progress.value = 0 to 0
        // App scope: leaving the screen doesn't stop an upload halfway.
        container.appScope.launch {
            try {
                val outcome = container.albumSender.send(albumId, trashOriginals) { done, total ->
                    _progress.value = done to total
                }
                _outcomes.trySend(outcome)
            } finally {
                _progress.value = null
            }
        }
    }

    fun remove(item: AlbumItem) {
        viewModelScope.launch {
            container.albums.removeItem(item.uri)
            // Back in the review queue: the file isn't reviewed anymore.
            container.decisions.get(item.uri)?.let { container.decisions.upsert(it.copy(refined = false)) }
        }
    }

    fun delete(albumId: Long) {
        viewModelScope.launch { container.albums.deleteAlbum(albumId) }
    }
}

/** Every album, newest first. Tapping one opens it. */
@Composable
fun AlbumsScreen(container: AppContainer, onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val vm: AlbumsViewModel = viewModel { AlbumsViewModel(container) }
    val albums by vm.albums.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Header(stringResource(R.string.albums_title), onBack)
        val list = albums ?: return@Column
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.albums_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { album -> AlbumRow(album, onClick = { onOpen(album.id) }) }
        }
    }
}

@Composable
private fun AlbumRow(album: AlbumSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = album.coverUri?.toUri(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(album.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                destinationLabel(album.destination, album.name),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.album_counts,
                    formatCount(album.pending + album.failed),
                    formatCount(album.done),
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DestinationIcon(album.destination)
    }
}

/**
 * One album: its files, and "send". Pending files can still be taken out.
 * For Google Photos albums, sending first asks what to do with the originals.
 */
@Composable
fun AlbumScreen(container: AppContainer, albumId: Long, onBack: () -> Unit) {
    val vm: AlbumsViewModel = viewModel { AlbumsViewModel(container) }
    val albums by vm.albums.collectAsStateWithLifecycle()
    val album = albums?.firstOrNull { it.id == albumId }
    val items by remember(albumId) { vm.items(albumId) }.collectAsStateWithLifecycle(emptyList())
    val progress by vm.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current

    var askOriginals by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.outcomes.collect { outcome ->
            val text = when (outcome) {
                is AlbumSender.Outcome.Done -> buildList {
                    add(resources.getQuantityString(R.plurals.sent_count, outcome.sent, outcome.sent))
                    if (outcome.failed >
                        0
                    ) {
                        add(resources.getQuantityString(R.plurals.failed_count, outcome.failed, outcome.failed))
                    }
                    if (outcome.trashed > 0) {
                        add(resources.getQuantityString(R.plurals.trashed_count, outcome.trashed, outcome.trashed))
                    }
                }.joinToString(" · ")

                AlbumSender.Outcome.NeedsSignIn -> resources.getString(R.string.needs_signin)

                AlbumSender.Outcome.WriteDenied -> resources.getString(R.string.write_denied)
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Header(album?.name.orEmpty(), onBack) {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete_album))
            }
        }
        if (album == null) return@Column

        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            DestinationIcon(album.destination)
            Text(
                destinationLabel(album.destination, album.name),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (album.failed > 0) {
            Text(
                stringResource(R.string.album_failed, formatCount(album.failed)),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(items, key = { it.uri }) { item -> AlbumTile(item, onRemove = { vm.remove(item) }) }
        }

        val unsent = album.pending + album.failed
        val sending = progress
        Column(Modifier.padding(16.dp)) {
            if (sending != null) {
                val (done, total) = sending
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    color = Brat,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.sending, done, total),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Button(
                    onClick = {
                        if (album.destination ==
                            AlbumDestination.GOOGLE_PHOTOS
                        ) {
                            askOriginals = true
                        } else {
                            vm.send(albumId, false)
                        }
                    },
                    enabled = unsent > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = Brat, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.action_send) + " (" + formatCount(unsent) + ")",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (askOriginals) {
        OriginalsDialog(
            onConfirm = { trash ->
                askOriginals = false
                vm.send(albumId, trash)
            },
            onDismiss = { askOriginals = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.action_delete_album)) },
            text = { Text(stringResource(R.string.delete_album_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(albumId)
                    onBack()
                }) { Text(stringResource(R.string.action_delete_album)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** A file in the album: sent, failed, or pending (with a button to take it out). */
@Composable
private fun AlbumTile(item: AlbumItem, onRemove: () -> Unit) {
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
        AsyncImage(
            model = item.uri.toUri(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val badge = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp)
        when (item.state) {
            AlbumItemState.DONE -> Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.state_sent),
                tint = Brat,
                modifier = badge,
            )

            AlbumItemState.FAILED -> Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = stringResource(R.string.state_failed),
                tint = MaterialTheme.colorScheme.error,
                modifier = badge.background(Color.Black.copy(alpha = 0.5f), CircleShape),
            )

            AlbumItemState.PENDING -> Box(
                badge
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_remove),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Google Photos albums: what happens to the originals once they're uploaded. */
@Composable
private fun OriginalsDialog(onConfirm: (trash: Boolean) -> Unit, onDismiss: () -> Unit) {
    var trash by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.send_google_title)) },
        text = {
            Column {
                Choice(!trash, stringResource(R.string.send_keep), null) { trash = false }
                Choice(trash, stringResource(R.string.send_trash), stringResource(R.string.send_trash_desc)) {
                    trash =
                        true
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(trash) }) { Text(stringResource(R.string.action_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Choice(selected: Boolean, title: String, subtitle: String?, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Back arrow, title, and optional actions on the right. */
@Composable
private fun Header(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_close))
        }
        Text(
            title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
