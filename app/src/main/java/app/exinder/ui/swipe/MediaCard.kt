package app.exinder.ui.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Gif
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.exinder.R
import app.exinder.data.MediaFile
import app.exinder.data.MediaKind
import app.exinder.ui.theme.Brat
import app.exinder.ui.theme.Condensed
import app.exinder.ui.theme.Nope
import app.exinder.ui.theme.StarBlue
import app.exinder.ui.viewer.LoopingVideo
import app.exinder.util.formatBytes
import app.exinder.util.formatDate
import app.exinder.util.formatDuration
import coil3.compose.SubcomposeAsyncImage

/** Card corners, the same on every layer so the clipping lines up. */
val CardShape = RoundedCornerShape(8.dp)

/**
 * Card content: the media filling the whole card, a dark gradient at the bottom
 * with date and details (Tinder's "name, age") and the file type icon.
 *
 * @param isTop the top card, the only one with stamps.
 * @param playVideo whether the video plays. Only the top card plays, and not
 *   even that one while the fullscreen viewer is open.
 * @param prepareVideo creates the player even when not playing, paused on the
 *   first frame (the back card, preloaded so it starts without delay).
 * @param stamps opacity of each stamp (KEEP, DELETE, FAVORITE), read every
 *   frame during the drag.
 */
@Composable
fun MediaCard(
    file: MediaFile,
    isTop: Boolean,
    playVideo: Boolean,
    prepareVideo: Boolean,
    stamps: () -> Stamps,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black)) {
        CardMedia(file, playVideo, prepareVideo)

        // Gradient that keeps the text readable over any photo.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.38f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                formatDate(file.takenAt),
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = buildList {
                    add(file.name)
                    add(formatBytes(file.size))
                    if (file.kind == MediaKind.VIDEO) add(formatDuration(file.durationMs))
                }.joinToString(" · "),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        KindBadge(file.kind, Modifier.align(Alignment.TopEnd).padding(12.dp))

        if (isTop) {
            Stamp(
                stringResource(R.string.stamp_keep), Brat, -18f, { stamps().keep },
                Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 40.dp),
            )
            Stamp(
                stringResource(R.string.stamp_trash), Nope, 18f, { stamps().trash },
                Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 40.dp),
            )
            Stamp(
                stringResource(R.string.stamp_favorite), StarBlue, -8f, { stamps().favorite },
                Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp),
            )
        }
    }
}

/** Opacity (0 to 1) of each stamp. */
data class Stamps(val keep: Float, val trash: Float, val favorite: Float)

/** Photo, GIF or video, with a notice in its place if the file fails to open. */
@Composable
private fun CardMedia(file: MediaFile, playVideo: Boolean, prepareVideo: Boolean) {
    // The image always sits underneath: for videos it is the first frame,
    // shown while the player is still loading.
    SubcomposeAsyncImage(
        model = file.uri,
        contentDescription = file.name,
        contentScale = if (file.isSvg) ContentScale.Fit else ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .then(if (file.isSvg) Modifier.background(Color.White).padding(24.dp) else Modifier),
        error = {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.BrokenImage,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(64.dp),
                )
                Text(stringResource(R.string.card_error), color = Color.White.copy(alpha = 0.6f))
            }
        },
    )
    if (file.kind == MediaKind.VIDEO && (playVideo || prepareVideo)) {
        LoopingVideo(file.uri, Modifier.fillMaxSize(), playing = playVideo)
    }
}

/** Round corner icon showing whether it's a photo, GIF or video. */
@Composable
private fun KindBadge(kind: MediaKind, modifier: Modifier = Modifier) {
    val icon = when (kind) {
        MediaKind.IMAGE -> Icons.Rounded.Image
        MediaKind.GIF -> Icons.Rounded.Gif
        MediaKind.VIDEO -> Icons.Rounded.Videocam
    }
    Box(
        modifier
            .size(34.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Tilted stamp that appears as the card is dragged, like Tinder's LIKE / NOPE. */
@Composable
private fun Stamp(
    text: String,
    color: Color,
    rotation: Float,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        fontSize = 42.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = Condensed,
        letterSpacing = 1.sp,
        modifier = modifier
            // Invisible most of the time: kept out of TalkBack.
            .clearAndSetSemantics { }
            .graphicsLayer {
                this.alpha = alpha()
                rotationZ = rotation
            }
            .border(4.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
    )
}
