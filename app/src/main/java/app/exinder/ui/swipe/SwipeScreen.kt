package app.exinder.ui.swipe

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.exinder.AppContainer
import app.exinder.R
import app.exinder.data.FolderRef
import app.exinder.data.MediaFile
import app.exinder.data.MediaKind
import app.exinder.data.Status
import app.exinder.data.ThemeMode
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
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── Swipe physics ──────────────────────────────────────────────────────────

/** Fraction of the width the card must travel for the decision to count. */
private const val SWIPE_X = 0.30f

/** Fraction of the height for the up swipe (favorite). */
private const val SWIPE_Y = 0.20f

/** Velocity (px/s) that counts as "flinging the card", even short of the threshold. */
private const val FLING_VELOCITY = 1800f

/** Maximum card tilt, in degrees, when it reaches the edge of the screen. */
private const val MAX_ROTATION = 15f

/** Position of the top card. Lives outside the card because the buttons read it too. */
private class CardMotion {
    val x = Animatable(0f)
    val y = Animatable(0f)
}

/** Direction the card's current position points to, ignoring velocity. */
private fun zoneOf(x: Float, y: Float, size: IntSize): Dir? {
    if (size == IntSize.Zero) return null
    return when {
        y < -size.height * SWIPE_Y && abs(y) > abs(x) -> Dir.UP
        x > size.width * SWIPE_X -> Dir.RIGHT
        x < -size.width * SWIPE_X -> Dir.LEFT
        else -> null
    }
}

/** Decides at the end of the drag: by position or, if the gesture was fast, by velocity. */
private fun releaseDir(x: Float, y: Float, vx: Float, vy: Float, size: IntSize): Dir? = zoneOf(x, y, size) ?: when {
    vy < -FLING_VELOCITY && abs(vy) > abs(vx) && y < 0 -> Dir.UP
    vx > FLING_VELOCITY && x > 0 -> Dir.RIGHT
    vx < -FLING_VELOCITY && x < 0 -> Dir.LEFT
    else -> null
}

// ─── Screen ─────────────────────────────────────────────────────────────────

/**
 * The main screen: header with logo and counter, the card stack, the trash
 * queue and the Tinder-style round buttons.
 */
@Composable
fun SwipeScreen(container: AppContainer, folder: FolderRef, owner: ViewModelStoreOwner, onChangeFolder: () -> Unit) {
    // Scoped to this visit to the folder (see ScreenStores): it loads the folder
    // when created and survives Activity recreation.
    val vm: SwipeViewModel = viewModel(viewModelStoreOwner = owner, key = folder.key) {
        SwipeViewModel(container, folder)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val trash by vm.trashStats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by container.prefs.theme.collectAsStateWithLifecycle(initialValue = ThemeMode.SISTEMA)

    var fullscreen by remember { mutableStateOf<MediaFile?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            val text = when (message) {
                is UiMessage.FavoriteFailed -> context.getString(R.string.msg_favorite_failed, message.name)

                is UiMessage.TrashFailed -> context.getString(
                    R.string.msg_trash_failed,
                    message.name ?: context.resources.getQuantityString(
                        R.plurals.files_count,
                        message.count,
                        formatCount(message.count),
                    ),
                )
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    // Short vibration when a file actually goes to the trash, two cards after
    // being marked: the signal that it can no longer be undone here.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(vm) {
        vm.trashed.collect { haptics.performHapticFeedback(HapticFeedbackType.Reject) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        TopBar(
            folder = folder,
            theme = theme,
            onTheme = { mode -> scope.launch { container.prefs.setTheme(mode) } },
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

            // While the fullscreen viewer is open, the card's video stops: otherwise two
            // players would be decoding the same file at the same time.
            else -> Deck(ui, vm, videoPaused = fullscreen != null, onOpen = { fullscreen = it })
        }
    }

    fullscreen?.let { FullscreenViewer(it, onClose = { fullscreen = null }) }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_title, folder.name)) },
            text = { Text(stringResource(R.string.reset_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetFolder()
                }) { Text(stringResource(R.string.action_reset), color = Nope) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Logo on the left; folder name and menu on the right. */
@Composable
private fun TopBar(
    folder: FolderRef,
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onChangeFolder: () -> Unit,
    onReset: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExinderLogo()
        // The folder name takes whatever room is left and shrinks first, so the
        // menu button keeps its size even with a long name and large fonts.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(
                folder.name,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable(onClick = onChangeFolder),
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_menu))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_change_folder)) },
                    onClick = {
                        menu = false
                        onChangeFolder()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_reset_folder)) },
                    onClick = {
                        menu = false
                        onReset()
                    },
                )
                // Each tap switches to the next: system → light → dark → system.
                // The menu stays open, so the user can see the result and keep switching.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_theme, stringResource(theme.label))) },
                    onClick = { onTheme(ThemeMode.entries[(theme.ordinal + 1) % ThemeMode.entries.size]) },
                )
            }
        }
    }
}

/** `1.234 / 5.678` on the left, `2,3 GB liberados` (freed) on the right. */
@Composable
private fun CounterRow(ui: SwipeUi, trash: TrashStats) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (ui.items.isEmpty()) {
                ""
            } else {
                stringResource(
                    R.string.counter,
                    formatCount(ui.position),
                    formatCount(ui.total),
                )
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.freed, formatBytes(trash.bytes)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

/**
 * The card stack with its gestures, the trash queue and the buttons.
 *
 * Drags are read on a fixed box (not on the moving card), so the finger's
 * coordinates don't shift along with the card.
 */
@Composable
private fun ColumnScope.Deck(ui: SwipeUi, vm: SwipeViewModel, videoPaused: Boolean, onOpen: (MediaFile) -> Unit) {
    val top = ui.current ?: return
    val next = ui.next
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var area by remember { mutableStateOf(IntSize.Zero) }
    val motion = remember(top.key) { CardMotion() }
    // Lock gestures while the card flies off, to guard against double taps.
    var busy by remember(top.key) { mutableStateOf(false) }
    // While a card returns from a rewind, the spring overshoots the center a bit;
    // without this, a "kept" card would flash the DELETE stamp on its way back.
    var entering by remember(top.key) { mutableStateOf(false) }

    // After a rewind, the card animates back in from the side it left.
    LaunchedEffect(top.key) {
        val from = ui.enterFrom ?: return@LaunchedEffect
        vm.consumeEnter()
        when (from) {
            Dir.LEFT -> motion.x.snapTo(-area.width * 1.5f)
            Dir.RIGHT -> motion.x.snapTo(area.width * 1.5f)
            Dir.UP -> motion.y.snapTo(-area.height * 1.5f)
        }
        entering = true
        try {
            coroutineScope {
                launch { motion.x.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f)) }
                launch { motion.y.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f)) }
            }
        } finally {
            entering = false
        }
    }

    // Rewinding over files already in the trash: each one passes by as an
    // inert gray card before the target card appears.
    // Shows at most 3; the last one says how many more were skipped.
    var ghost by remember { mutableStateOf<Ghost?>(null) }
    // If the stack leaves the screen mid-animation (the last card was decided),
    // the list is dropped so it isn't replayed by a later rewind.
    DisposableEffect(vm) { onDispose { vm.consumePassedTrash() } }
    val ghostX = remember { Animatable(0f) }
    val ghostAlpha = remember { Animatable(0f) }
    LaunchedEffect(ui.passedTrash) {
        val passed = ui.passedTrash
        if (passed.isEmpty() || area == IntSize.Zero) return@LaunchedEffect
        // The list is only cleared at the end: clearing it changes this effect's key,
        // which would cancel the animation midway. A new rewind during the animation
        // restarts the effect with the accumulated list, and the `finally` keeps a
        // gray card from getting stuck on screen.
        try {
            val shown = passed.take(3)
            shown.forEachIndexed { i, file ->
                ghost = Ghost(file, more = if (i == shown.lastIndex) passed.size - shown.size else 0)
                ghostX.snapTo(-area.width * 0.6f)
                ghostAlpha.snapTo(1f)
                ghostX.animateTo(0f, tween(160, easing = FastOutSlowInEasing))
                delay(220)
                ghostAlpha.animateTo(0f, tween(140))
            }
        } finally {
            ghost = null
        }
        vm.consumePassedTrash()
    }

    /** Flings the card off screen and only then records the decision. */
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
            vm.decide(dir, top.key)
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

    // Preload the third card's image (the back one is already on screen) at
    // card size, so it comes straight out of the memory cache with no wait.
    val context = LocalContext.current
    val third = ui.afterNext
    LaunchedEffect(third?.key, area) {
        // Animated GIFs aren't kept in Coil's memory cache, so decoding one ahead
        // of time would only be thrown away.
        if (third == null || area == IntSize.Zero || third.kind == MediaKind.GIF) return@LaunchedEffect
        SingletonImageLoader.get(context).enqueue(
            ImageRequest.Builder(context)
                .data(third.uri)
                .size(area.width, area.height)
                // Same scale and precision the card's image will ask for, so the
                // cached result is accepted instead of decoded again.
                .scale(if (third.isSvg) Scale.FIT else Scale.FILL)
                .precision(Precision.INEXACT)
                .build(),
        )
    }

    // Short vibration when crossing a decision threshold.
    val zone by remember(top.key) { derivedStateOf { zoneOf(motion.x.value, motion.y.value, area) } }
    LaunchedEffect(zone) {
        if (zone != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** 0 to 1: how far the card has moved toward each decision. */
    fun progress(dir: Dir): Float {
        if (area == IntSize.Zero || entering) return 0f
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
                    onDragCancel = { if (!busy) springBack() },
                )
            }
            .pointerInput(top.key) {
                detectTapGestures(onTap = { if (!busy) onOpen(top) })
            },
    ) {
        // Back to front. The `key` lets the back card become the front one
        // without recreating the image, so it doesn't flicker.
        listOfNotNull(next, top).forEach { file ->
            key(file.key) {
                val isTop = file.key == top.key
                MediaCard(
                    file = file,
                    isTop = isTop,
                    playVideo = isTop && !videoPaused,
                    // Both cards in the stack keep their player ready, except while
                    // the full screen viewer is open: then they let go of their
                    // decoders, which are a limited resource on some phones.
                    prepareVideo = !videoPaused,
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
                                // The back card grows as the front one leaves.
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

        // Passing gray "in the trash" cards on top of the stack.
        ghost?.let {
            TrashedGhost(
                it.file,
                it.more,
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = ghostX.value
                    alpha = ghostAlpha.value
                },
            )
        }
    }

    PendingRow(ui, enabled = !busy, onRewindTo = vm::rewindTo)

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
 * Thumbnails of the files in the trash queue ("trash in 2", "trash in 1").
 * Tapping one rewinds to it. The height is fixed so the
 * screen doesn't jump.
 */
@Composable
private fun PendingRow(ui: SwipeUi, enabled: Boolean, onRewindTo: (Int) -> Unit) {
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
                    // Locked while a card flies off (see `busy` in Deck).
                    .clickable(enabled = enabled) { onRewindTo(index) }
                    .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    // Cached under its own key: the same URI's card-sized image
                    // stays in the cache for when the user rewinds to it.
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(file.uri)
                        .memoryCacheKey("${file.key}#thumb")
                        .build(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .border(2.dp, Nope, CircleShape),
                )
                Text(
                    stringResource(R.string.trash_in, left),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** The row of Tinder-style round buttons: rewind, X, star and heart. */
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
        RoundAction(Icons.Rounded.Replay, stringResource(R.string.cd_rewind), RewindGold, 52.dp, {
            0f
        }, canRewind, onRewind)
        RoundAction(Icons.Rounded.Close, stringResource(R.string.cd_trash), Nope, 66.dp, {
            progress(Dir.LEFT)
        }, true, onTrash)
        RoundAction(Icons.Rounded.Star, stringResource(R.string.cd_favorite), StarBlue, 52.dp, {
            progress(Dir.UP)
        }, true, onFavorite)
        RoundAction(Icons.Rounded.Favorite, stringResource(R.string.cd_keep), Brat, 66.dp, {
            progress(Dir.RIGHT)
        }, true, onKeep)
    }
}

/**
 * Round button with a shadow. As in Tinder, it "lights up" (colored background,
 * white icon) as the card is dragged toward it.
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

/** Folder with no photos or videos. */
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
        Text(stringResource(R.string.empty_folder), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        BratButton(stringResource(R.string.action_pick_another), onChangeFolder)
    }
}

/**
 * End of the queue: the folder's stats and, if files remain in the trash queue,
 * the confirmation to send them to the trash. Nothing goes without that tap.
 */
@Composable
private fun ColumnScope.Summary(ui: SwipeUi, trash: TrashStats, vm: SwipeViewModel, onChangeFolder: () -> Unit) {
    // Centered when it fits, scrollable when it doesn't (large fonts, small screens).
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.summary_title),
                fontFamily = Condensed,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                // The theme's green: brat green itself is too faint as text on white.
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(28.dp))
            // Wraps to a second line instead of squeezing big numbers.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val kept = ui.count(Status.MANTER)
                val favorites = ui.count(Status.FAVORITO)
                StatBlock(kept, pluralStringResource(R.plurals.stat_kept, labelCount(kept)))
                StatBlock(favorites, pluralStringResource(R.plurals.stat_favorites, labelCount(favorites)))
                StatBlock(trash.count, stringResource(R.string.stat_in_trash))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.freed, formatBytes(trash.bytes)),
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
                        pluralStringResource(R.plurals.summary_pending, pending, pending),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { vm.rewind() }) { Text(stringResource(R.string.action_back)) }
                        Button(
                            onClick = { vm.flushPending() },
                            colors = ButtonDefaults.buttonColors(containerColor = Nope, contentColor = Color.White),
                        ) { Text(stringResource(R.string.action_send_now), fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            BratButton(stringResource(R.string.action_pick_another), onChangeFolder)
        }
    }
}

/**
 * Quantity used to pick a label's plural form. Zero reads as plural in both
 * languages ("0 favorites", "0 favoritos"), but Portuguese grammar files put 0
 * in the singular group, which would print "0 favorito".
 */
private fun labelCount(n: Int): Int = if (n == 0) 2 else n

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

/** A passing gray card, and how many other trashed files were skipped with it. */
private data class Ghost(val file: MediaFile, val more: Int)

/** Gray "in the trash" card: the photo in darkened black and white, inert. */
@Composable
private fun TrashedGhost(file: MediaFile, more: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(CardShape)
            .background(Color(0xFF2A2C31)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = file.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.35f },
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(48.dp),
            )
            Text(
                stringResource(R.string.ghost_in_trash),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = Condensed,
            )
            if (more > 0) {
                Text(
                    stringResource(R.string.ghost_more, formatCount(more)),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                )
            }
        }
    }
}
