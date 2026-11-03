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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Cantos do card, iguais em todas as camadas para o recorte bater. */
val CardShape = RoundedCornerShape(8.dp)

/**
 * Conteúdo de um card: a mídia ocupando tudo, o degradê escuro na base com
 * data e detalhes (o "nome, idade" do Tinder) e o ícone do tipo de arquivo.
 *
 * @param isTop só o card de cima toca vídeo; o de trás mostra um quadro parado.
 * @param stamps opacidade de cada carimbo (MANTÉM, APAGA, FAVORITA), lida a
 *   cada frame durante o arrasto.
 */
@Composable
fun MediaCard(
    file: MediaFile,
    isTop: Boolean,
    stamps: () -> Stamps,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black)) {
        CardMedia(file, isTop)

        // Degradê que dá leitura ao texto sobre qualquer foto.
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
                "MANTÉM", Brat, -18f, { stamps().keep },
                Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 40.dp),
            )
            Stamp(
                "APAGA", Nope, 18f, { stamps().trash },
                Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 40.dp),
            )
            Stamp(
                "FAVORITA", StarBlue, -8f, { stamps().favorite },
                Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp),
            )
        }
    }
}

/** Opacidade (0 a 1) de cada carimbo. */
data class Stamps(val keep: Float, val trash: Float, val favorite: Float)

/** Foto, GIF ou vídeo, com um aviso no lugar se o arquivo não abrir. */
@Composable
private fun CardMedia(file: MediaFile, isTop: Boolean) {
    // A imagem fica sempre por baixo: para vídeo ela é o primeiro quadro,
    // que aparece enquanto o player ainda está carregando.
    SubcomposeAsyncImage(
        model = file.uri,
        contentDescription = file.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
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
                Text("não deu pra abrir esse arquivo", color = Color.White.copy(alpha = 0.6f))
            }
        },
    )
    if (file.kind == MediaKind.VIDEO && isTop) {
        LoopingVideo(file.uri, Modifier.fillMaxSize())
    }
}

/** Ícone redondo no canto dizendo se é foto, GIF ou vídeo. */
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

/** Carimbo inclinado que aparece conforme o card é arrastado, como o LIKE / NOPE do Tinder. */
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
            .graphicsLayer {
                this.alpha = alpha()
                rotationZ = rotation
            }
            .border(4.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
    )
}
