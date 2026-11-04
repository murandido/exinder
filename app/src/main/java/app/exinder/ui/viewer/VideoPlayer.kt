package app.exinder.ui.viewer

import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
fun LoopingVideo(uri: Uri, modifier: Modifier = Modifier, controls: Boolean = false, playing: Boolean = true) {
    val context = LocalContext.current

    var failed by remember(uri) { mutableStateOf(false) }
    val player = remember(uri) {
        ExoPlayer.Builder(context)
            // With sound (full screen), behave like a media app: pause for calls
            // and other audio, and when headphones are unplugged. The muted card
            // video doesn't take audio focus, so it doesn't stop the user's music.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                controls,
            )
            .setHandleAudioBecomingNoisy(controls)
            .build().apply {
                addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            failed = true
                        }
                    },
                )
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

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
        // A file that can't be played (corrupt, unsupported codec, no free
        // decoder) says so instead of leaving a black screen.
        if (failed) {
            Text(
                stringResource(R.string.video_error),
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
