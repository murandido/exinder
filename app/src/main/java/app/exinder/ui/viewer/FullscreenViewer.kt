package app.exinder.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.exinder.data.MediaFile
import app.exinder.data.MediaKind
import app.exinder.util.formatBytes
import app.exinder.util.formatDateTime
import app.exinder.util.formatDuration
import coil3.compose.AsyncImage

/**
 * Tela cheia aberta ao tocar no card: foto com zoom de pinça, GIF animado ou
 * vídeo com som e controles. Os metadados ficam no topo, longe dos controles
 * do vídeo.
 */
@Composable
fun FullscreenViewer(file: MediaFile, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            when (file.kind) {
                MediaKind.VIDEO -> LoopingVideo(file.uri, Modifier.fillMaxSize(), controls = true)
                else -> ZoomableImage(file)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)),
                    )
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalAlignment = Alignment.Top,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "fechar", tint = Color.White)
                }
                Column(Modifier.padding(top = 10.dp)) {
                    Text(
                        file.name,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildList {
                            add(formatDateTime(file.takenAt))
                            add(formatBytes(file.size))
                            if (file.kind == MediaKind.VIDEO) add(formatDuration(file.durationMs))
                            add(file.mime)
                        }.joinToString(" · "),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

/** Imagem com pinça para zoom, arrasto quando ampliada e toque duplo para voltar. */
@Composable
private fun ZoomableImage(file: MediaFile) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        // O deslocamento fica limitado à sobra da imagem ampliada, para ela
        // nunca sair inteira da tela; ao diminuir o zoom, o limite encolhe junto.
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        val moved = offset + pan
        offset = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
    }

    AsyncImage(
        model = file.uri,
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .then(if (file.isSvg) Modifier.background(Color.White) else Modifier)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                })
            }
            .transformable(state)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
