package app.exinder.ui.swipe

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.exinder.AppContainer
import app.exinder.data.FolderRef
import app.exinder.data.MediaFile
import app.exinder.data.Status
import app.exinder.data.TrashStats
import app.exinder.ui.theme.Brat
import app.exinder.ui.theme.Condensed
import app.exinder.ui.theme.ExinderLogo
import app.exinder.ui.theme.Ink
import app.exinder.ui.theme.Nope
import app.exinder.ui.theme.RewindGold
import app.exinder.ui.theme.StarBlue
import app.exinder.ui.viewer.FullscreenViewer
import app.exinder.util.formatBytes
import app.exinder.util.formatCount
import coil3.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

// ─── Física do swipe ────────────────────────────────────────────────────────

/** Fração da largura que o card precisa andar para a decisão valer. */
private const val SWIPE_X = 0.30f

/** Fração da altura para o swipe para cima (favoritar). */
private const val SWIPE_Y = 0.20f

/** Velocidade (px/s) que conta como "jogar o card", mesmo sem chegar no limiar. */
private const val FLING_VELOCITY = 1800f

/** Inclinação máxima do card, em graus, quando ele chega na borda da tela. */
private const val MAX_ROTATION = 15f

/** Posição do card de cima. Fica fora do card porque os botões também a leem. */
private class CardMotion {
    val x = Animatable(0f)
    val y = Animatable(0f)
}

/** Direção em que a posição atual do card está apontando, sem contar velocidade. */
private fun zoneOf(x: Float, y: Float, size: IntSize): Dir? {
    if (size == IntSize.Zero) return null
    return when {
        y < -size.height * SWIPE_Y && abs(y) > abs(x) -> Dir.UP
        x > size.width * SWIPE_X -> Dir.RIGHT
        x < -size.width * SWIPE_X -> Dir.LEFT
        else -> null
    }
}

/** Decide no fim do arrasto: vale a posição ou, se o gesto foi rápido, a velocidade. */
private fun releaseDir(x: Float, y: Float, vx: Float, vy: Float, size: IntSize): Dir? =
    zoneOf(x, y, size) ?: when {
        vy < -FLING_VELOCITY && abs(vy) > abs(vx) && y < 0 -> Dir.UP
        vx > FLING_VELOCITY && x > 0 -> Dir.RIGHT
        vx < -FLING_VELOCITY && x < 0 -> Dir.LEFT
        else -> null
    }

// ─── Tela ───────────────────────────────────────────────────────────────────

/**
 * A tela principal: topo com logo e contador, a pilha de cards, a fila da
 * lixeira e os botões redondos do Tinder.
 */
@Composable
fun SwipeScreen(
    container: AppContainer,
    folder: FolderRef,
    onChangeFolder: () -> Unit,
) {
    val vm: SwipeViewModel = viewModel(key = folder.key) { SwipeViewModel(container, folder) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val trash by vm.trashStats.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var fullscreen by remember { mutableStateOf<MediaFile?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        TopBar(
            folder = folder,
            onChangeFolder = onChangeFolder,
            onReset = { confirmReset = true },
        )
        CounterRow(ui, trash)

        when {
            ui.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brat)
            }

            ui.items.isEmpty() -> EmptyState(onChangeFolder)

            ui.current == null -> Summary(ui, trash, vm, onChangeFolder)

            else -> Deck(ui, vm, onOpen = { fullscreen = it })
        }
    }

    fullscreen?.let { FullscreenViewer(it, onClose = { fullscreen = null }) }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("resetar ${folder.name}?") },
            text = { Text("as decisões dessa pasta são esquecidas e a fila recomeça do início. nenhum arquivo é tocado.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetFolder()
                }) { Text("resetar", color = Nope) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("cancelar") }
            },
        )
    }
}

/** Logo à esquerda; nome da pasta e menu à direita. */
@Composable
private fun TopBar(folder: FolderRef, onChangeFolder: () -> Unit, onReset: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExinderLogo()
        Spacer(Modifier.weight(1f))
        Text(
            folder.name,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 180.dp)
                .clickable(onClick = onChangeFolder),
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "menu")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("trocar pasta") },
                    onClick = {
                        menu = false
                        onChangeFolder()
                    },
                )
                DropdownMenuItem(
                    text = { Text("resetar pasta") },
                    onClick = {
                        menu = false
                        onReset()
                    },
                )
            }
        }
    }
}

/** `1.234 / 5.678` à esquerda, `2,3 GB liberados` à direita. */
@Composable
private fun CounterRow(ui: SwipeUi, trash: TrashStats) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (ui.items.isEmpty()) "" else "${formatCount(ui.position)} / ${formatCount(ui.total)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${formatBytes(trash.bytes)} liberados",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

/**
 * A pilha de cards com os gestos, a fila da lixeira e os botões.
 *
 * O arrasto é lido numa caixa fixa (e não no card que se move), para as
 * coordenadas do dedo não mudarem junto com o card.
 */
@Composable
private fun ColumnScope.Deck(ui: SwipeUi, vm: SwipeViewModel, onOpen: (MediaFile) -> Unit) {
    val top = ui.current ?: return
    val next = ui.next
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var area by remember { mutableStateOf(IntSize.Zero) }
    val motion = remember(top.key) { CardMotion() }
    // Trava os gestos enquanto o card voa para fora, contra toque duplo.
    var busy by remember(top.key) { mutableStateOf(false) }

    // Depois de um rebobinar, o card volta animando pelo lado por onde saiu.
    LaunchedEffect(top.key) {
        val from = ui.enterFrom ?: return@LaunchedEffect
        vm.consumeEnter()
        when (from) {
            Dir.LEFT -> motion.x.snapTo(-area.width * 1.5f)
            Dir.RIGHT -> motion.x.snapTo(area.width * 1.5f)
            Dir.UP -> motion.y.snapTo(-area.height * 1.5f)
        }
        coroutineScope {
            launch { motion.x.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f)) }
            launch { motion.y.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f)) }
        }
    }

    /** Joga o card para fora da tela e só então registra a decisão. */
    fun fling(dir: Dir) {
        if (busy || area == IntSize.Zero) return
        busy = true
        scope.launch {
            val spec = tween<Float>(durationMillis = 220, easing = FastOutLinearInEasing)
            coroutineScope {
                when (dir) {
                    Dir.LEFT -> launch { motion.x.animateTo(-area.width * 1.5f, spec) }
                    Dir.RIGHT -> launch { motion.x.animateTo(area.width * 1.5f, spec) }
                    Dir.UP -> launch { motion.y.animateTo(-area.height * 1.5f, spec) }
                }
            }
            vm.decide(dir)
        }
    }

    fun springBack() {
        scope.launch {
            coroutineScope {
                launch { motion.x.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                launch { motion.y.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
            }
        }
    }

    // Vibração curta ao cruzar o limiar de uma decisão.
    val zone by remember(top.key) { derivedStateOf { zoneOf(motion.x.value, motion.y.value, area) } }
    LaunchedEffect(zone) {
        if (zone != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** 0 a 1: o quanto o card já andou em direção a cada decisão. */
    fun progress(dir: Dir): Float {
        if (area == IntSize.Zero) return 0f
        val x = motion.x.value
        val y = motion.y.value
        return when (dir) {
            Dir.RIGHT -> x / (area.width * SWIPE_X)
            Dir.LEFT -> -x / (area.width * SWIPE_X)
            Dir.UP -> if (abs(y) > abs(x)) -y / (area.height * SWIPE_Y) else 0f
        }.coerceIn(0f, 1f)
    }

    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .onSizeChanged { area = it }
            .pointerInput(top.key) {
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDrag = { change, amount ->
                        if (busy) return@detectDragGestures
                        change.consume()
                        tracker.addPointerInputChange(change)
                        scope.launch {
                            motion.x.snapTo(motion.x.value + amount.x)
                            motion.y.snapTo(motion.y.value + amount.y)
                        }
                    },
                    onDragEnd = {
                        if (busy) return@detectDragGestures
                        val v = tracker.calculateVelocity()
                        val dir = releaseDir(motion.x.value, motion.y.value, v.x, v.y, area)
                        if (dir != null) fling(dir) else springBack()
                    },
                    onDragCancel = { springBack() },
                )
            }
            .pointerInput(top.key) {
                detectTapGestures(onTap = { if (!busy) onOpen(top) })
            },
    ) {
        // De trás para a frente. O `key` faz o card de trás virar o da frente
        // sem recriar a imagem, então não pisca.
        listOfNotNull(next, top).forEach { file ->
            key(file.key) {
                val isTop = file.key == top.key
                MediaCard(
                    file = file,
                    isTop = isTop,
                    stamps = { Stamps(progress(Dir.RIGHT), progress(Dir.LEFT), progress(Dir.UP)) },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (isTop) {
                                translationX = motion.x.value
                                translationY = motion.y.value
                                rotationZ = if (size.width == 0f) 0f else motion.x.value / size.width * MAX_ROTATION
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            } else {
                                // O card de trás cresce enquanto o da frente sai.
                                val p = maxOf(progress(Dir.LEFT), progress(Dir.RIGHT), progress(Dir.UP))
                                val s = 0.94f + 0.06f * p
                                scaleX = s
                                scaleY = s
                            }
                        }
                        .shadow(if (isTop) 6.dp else 0.dp, CardShape)
                        .clip(CardShape),
                )
            }
        }
    }

    PendingRow(ui, vm)

    ActionButtons(
        canRewind = ui.canRewind,
        progress = ::progress,
        onRewind = { if (!busy) vm.rewind() },
        onTrash = { fling(Dir.LEFT) },
        onFavorite = { fling(Dir.UP) },
        onKeep = { fling(Dir.RIGHT) },
    )
}

/**
 * Miniaturas dos arquivos no buffer ("lixeira em 2", "lixeira em 1").
 * Tocar numa delas rebobina até ela. A altura é fixa para a tela não pular.
 */
@Composable
private fun PendingRow(ui: SwipeUi, vm: SwipeViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        ui.pending.takeLast(TRASH_DELAY).forEach { index ->
            val file = ui.items[index]
            val left = ui.trashIn(index)
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    // Travado enquanto um card voa para fora (ver `busy` no Deck).
                    .clickable(enabled = enabled) { onRewindTo(index) }
                    .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = file.uri,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .border(2.dp, Nope, CircleShape),
                )
                Text(
                    "lixeira em $left",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** A fileira de botões redondos do Tinder: rebobinar, X, estrela e coração. */
@Composable
private fun ActionButtons(
    canRewind: Boolean,
    progress: (Dir) -> Float,
    onRewind: () -> Unit,
    onTrash: () -> Unit,
    onFavorite: () -> Unit,
    onKeep: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction(Icons.Rounded.Replay, "rebobinar", RewindGold, 52.dp, { 0f }, canRewind, onRewind)
        RoundAction(Icons.Rounded.Close, "apagar", Nope, 66.dp, { progress(Dir.LEFT) }, true, onTrash)
        RoundAction(Icons.Rounded.Star, "favoritar", StarBlue, 52.dp, { progress(Dir.UP) }, true, onFavorite)
        RoundAction(Icons.Rounded.Favorite, "manter", Brat, 66.dp, { progress(Dir.RIGHT) }, true, onKeep)
    }
}

/**
 * Botão redondo com sombra. Como no Tinder, ele "acende" (fundo colorido,
 * ícone branco) conforme o card é arrastado na direção dele.
 */
@Composable
private fun RoundAction(
    icon: ImageVector,
    label: String,
    color: Color,
    size: Dp,
    highlight: () -> Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val h = highlight()
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = lerp(base, color, h),
        shadowElevation = 6.dp,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = label,
                tint = lerp(color, Color.White, h).copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** Pasta sem fotos nem vídeos. */
@Composable
private fun ColumnScope.EmptyState(onChangeFolder: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("nada pra revisar aqui", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        BratButton("escolher outra pasta", onChangeFolder)
    }
}

/**
 * Fim da fila: números da pasta e, se ainda houver arquivos no buffer, a
 * confirmação para mandá-los para a lixeira. Nada vai sem esse toque.
 */
@Composable
private fun ColumnScope.Summary(
    ui: SwipeUi,
    trash: TrashStats,
    vm: SwipeViewModel,
    onChangeFolder: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "acabou essa pasta",
            fontFamily = Condensed,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            color = Brat,
        )
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatBlock(ui.count(Status.MANTER), "mantidos")
            StatBlock(ui.count(Status.FAVORITO), "favoritos")
            StatBlock(trash.count, "na lixeira")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "${formatBytes(trash.bytes)} liberados",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val pending = ui.pending.size
        if (pending > 0) {
            Spacer(Modifier.height(28.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Nope.copy(alpha = 0.12f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (pending == 1) "1 arquivo vai pra lixeira" else "$pending arquivos vão pra lixeira",
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { vm.rewind() }) { Text("voltar") }
                    Button(
                        onClick = { vm.flushPending() },
                        colors = ButtonDefaults.buttonColors(containerColor = Nope, contentColor = Color.White),
                    ) { Text("mandar agora", fontWeight = FontWeight.Bold) }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        BratButton("escolher outra pasta", onChangeFolder)
    }
}

@Composable
private fun StatBlock(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(formatCount(value), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun BratButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Brat, contentColor = Ink),
    ) { Text(text, fontWeight = FontWeight.Bold) }
}
