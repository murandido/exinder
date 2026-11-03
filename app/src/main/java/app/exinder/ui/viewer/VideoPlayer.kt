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
 * Vídeo tocando em loop.
 *
 * - No card: sem som, sem controles, preenchendo o card ([controls] = false).
 * - Na tela cheia: com som, com controles, inteiro na tela ([controls] = true).
 *
 * O player é criado quando o composable entra na tela e liberado quando sai,
 * e pausa sozinho quando o app vai para o fundo.
 */
@OptIn(UnstableApi::class)
@Composable
fun LoopingVideo(uri: Uri, modifier: Modifier = Modifier, controls: Boolean = false) {
    val context = LocalContext.current

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = if (controls) 1f else 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LifecycleStartEffect(player) {
        player.play()
        onStopOrDispose { player.pause() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Inflado do XML porque o tipo de superfície (TextureView) só é
            // configurável por atributo de layout.
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
