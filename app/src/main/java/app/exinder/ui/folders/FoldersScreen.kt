package app.exinder.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.exinder.R
import app.exinder.AppContainer
import app.exinder.data.FolderInfo
import app.exinder.data.FolderRef
import app.exinder.ui.theme.Brat
import app.exinder.ui.theme.ExinderLogo
import app.exinder.util.formatCount
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads the folder list with each folder's progress. */
class FoldersViewModel(private val container: AppContainer) : ViewModel() {

    private val _folders = MutableStateFlow<List<FolderInfo>?>(null)

    /** `null` while loading. */
    val folders: StateFlow<List<FolderInfo>?> = _folders.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val decided = container.decisions.decidedUris().toSet()
            _folders.value = container.media.folders(decided)
        }
    }
}

/**
 * Lists every folder on the device that has media. Each subfolder is a
 * separate item, with its file count and how much has been reviewed.
 */
@Composable
fun FoldersScreen(container: AppContainer, onOpen: (FolderRef) -> Unit) {
    val vm: FoldersViewModel = viewModel { FoldersViewModel(container) }
    val folders by vm.folders.collectAsStateWithLifecycle()

    // Reload every time the screen appears, so progress is up to date.
    LaunchedEffect(Unit) { vm.load() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ExinderLogo(Modifier.padding(start = 16.dp, top = 8.dp))
        Text(
            text = stringResource(R.string.folders_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )

        val list = folders
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brat)
            }

            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.folders_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.ref.key }) { folder ->
                    FolderRow(folder, onClick = { onOpen(folder.ref) })
                }
            }
        }
    }
}

/** A list row: thumbnail, name, path and progress bar. */
@Composable
private fun FolderRow(folder: FolderInfo, onClick: () -> Unit) {
    val reviewed = folder.total - folder.remaining
    val done = folder.remaining == 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (folder.cover != null) {
                AsyncImage(
                    model = folder.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = Color.White)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                folder.ref.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Truncate at the start: the end of the path is what tells apart folders
            // with the same name (e.g. the two "WhatsApp Images").
            Text(
                folder.ref.relativePath.trimEnd('/'),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (folder.total == 0) 0f else reviewed.toFloat() / folder.total },
                color = Brat,
                trackColor = MaterialTheme.colorScheme.background,
                drawStopIndicator = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (done) {
                    pluralStringResource(R.plurals.folder_reviewed, folder.total, formatCount(folder.total))
                } else {
                    stringResource(R.string.folder_remaining, formatCount(folder.remaining), formatCount(folder.total))
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (done) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.cd_reviewed),
                tint = Brat,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
