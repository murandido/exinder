package app.exinder.ui.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.exinder.R
import app.exinder.data.MediaFile
import app.exinder.data.MediaKind
import app.exinder.util.formatBytes
import app.exinder.util.formatDateTime
import app.exinder.util.formatDuration
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Fullscreen view opened by tapping the card: pinch-to-zoom photo, animated GIF
 * or video with sound and controls. Metadata sits at the top, away from the
 * video controls. Dragging down (when not zoomed) closes it.
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
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        var height by remember { mutableIntStateOf(1) }
        var zoomed by remember { mutableStateOf(false) }

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { height = it.height }
                // The background fades as the view is dragged down, like in gallery apps.
                .background(Color.Black.copy(alpha = 1f - (drag.value / height).coerceIn(0f, 0.6f)))
                .dragDownToClose(
                    enabled = { !zoomed },
                    onDrag = { dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } },
                    onRelease = {
                        scope.launch {
                            if (drag.value > height * 0.15f) {
                                drag.animateTo(height.toFloat(), tween(160))
                                onClose()
                            } else {
                                drag.animateTo(0f, spring(stiffness = 500f))
                            }
                        }
                    },
                )
                .graphicsLayer { translationY = drag.value },
        ) {
            when (file.kind) {
                MediaKind.VIDEO -> LoopingVideo(file.uri, Modifier.fillMaxSize(), controls = true)
                else -> ZoomableImage(file, onZoomed = { zoomed = it })
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
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        tint = Color.White,
                    )
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

/** Image with pinch to zoom, panning when zoomed in, and double tap to reset. */
@Composable
private fun ZoomableImage(file: MediaFile, onZoomed: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        onZoomed(scale > 1f)
        // The offset is clamped to the zoomed image's overflow, so it never fully
        // leaves the screen; zooming out shrinks the limit along with it.
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
                    onZoomed(false)
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

/**
 * One-finger drag down. Reads touches in the initial pass, before the
 * children, because the video player is an Android View that consumes all
 * touches. Only takes over the gesture once the movement passes the threshold
 * and is clearly downward; until then, everything goes to the children as
 * usual (zoom, controls).
 */
private fun Modifier.dragDownToClose(
    enabled: () -> Boolean,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        var dragging = false
        var total = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            // Second finger: it's a pinch, not a drag.
            if (event.changes.size > 1) {
                if (dragging) onRelease()
                return@awaitEachGesture
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.position - change.previousPosition
            total += delta
            if (!dragging && total.y > viewConfiguration.touchSlop && total.y > abs(total.x) * 2) {
                dragging = true
            }
            if (dragging) {
                change.consume()
                onDrag(delta.y)
            }
        }
        if (dragging) onRelease()
    }
}
