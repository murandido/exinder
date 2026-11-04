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
 *
 * @param playing `false` deixa o player preparado e parado no primeiro quadro.
 *   Usado no card de trás: quando ele vira o da frente, o vídeo já começa na hora.
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
