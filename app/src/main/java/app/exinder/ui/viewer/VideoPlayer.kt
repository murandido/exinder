package app.exinder.ui.viewer

import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.exinder.R

/**
 * Video playing in a loop.
 *
 * - On a card: muted, no controls, filling the card ([controls] = false).
 * - Fullscreen: with sound and controls, fitted to the screen ([controls] = true).
 *
 * The player is created when the composable enters the screen and released when
 * it leaves, and it pauses on its own when the app goes to the background.
 *
 * @param playing `false` keeps the player prepared and paused on the first frame.
 *   Used on the back card: when it becomes the front one, the video starts instantly.
 */
@OptIn(UnstableApi::class)
@Composable
fun LoopingVideo(
    uri: Uri,
    modifier: Modifier = Modifier,
    controls: Boolean = false,
    playing: Boolean = true,
) {
    val context = LocalContext.current

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = if (controls) 1f else 0f
            playWhenReady = playing
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LifecycleStartEffect(player, playing) {
        if (playing) player.play() else player.pause()
        onStopOrDispose { player.pause() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Inflated from XML because the surface type (TextureView) can only be
            // set through a layout attribute.
            (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                useController = controls
                resizeMode = if (controls) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                this.player = player
            }
        },
        update = { view -> view.player = player },
    )
}
