package app.exinder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.exinder.data.FolderRef
import app.exinder.data.SystemRequests
import app.exinder.data.ThemeMode
import app.exinder.ui.folders.FoldersScreen
import app.exinder.ui.permissions.PermissionsScreen
import app.exinder.ui.swipe.SwipeScreen
import app.exinder.ui.theme.ExinderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

/** The app's only Activity. Navigation between screens is done in Compose. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Must happen before the Activity becomes visible.
        SystemRequests.attach(this)

        val container = (application as ExinderApp).container
        // Read once, synchronously, so the first frame already has the saved
        // theme instead of flashing the system one. It is a tiny local file.
        val savedTheme = runBlocking { container.prefs.theme.first() }
        setContent {
            val mode by container.prefs.theme.collectAsState(initial = savedTheme)
            val dark = when (mode) {
                ThemeMode.SISTEMA -> isSystemInDarkTheme()
                ThemeMode.CLARO -> false
                ThemeMode.ESCURO -> true
            }
            // Status and navigation bar icons follow the app's theme,
            // which may differ from the system theme.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                // The window background (seen briefly during transitions) follows
                // the app's theme too, not the system's.
                window.setBackgroundDrawable(ColorDrawable(if (dark) DARK_WINDOW else Color.WHITE))
            }
            ExinderTheme(dark) {
                ExinderRoot(container)
            }
        }
    }

    override fun onDestroy() {
        SystemRequests.detach(this)
        super.onDestroy()
    }
}

/** Window background in dark mode; matches the night `windowBackground`. */
private const val DARK_WINDOW = 0xFF111111.toInt()

/**
 * One [ViewModelStore] per folder visit. Each visit gets a fresh SwipeViewModel
 * (so the folder is read again), the ViewModel survives Activity recreation
 * (so a theme change doesn't reload the folder mid-request), and leaving the
 * folder frees it (so ViewModels don't pile up for every folder ever opened).
 */
class ScreenStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun ownerFor(key: String): ViewModelStoreOwner {
        val store = stores.getOrPut(key) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
    }

    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/** The app's screens. */
private sealed interface Screen {
    data object Folders : Screen
    data class Swipe(val folder: FolderRef) : Screen
}

/**
 * Navigation root:
 * 1. no permission → permissions screen;
 * 2. with permission → last opened folder, or the folder list.
 */
@Composable
private fun ExinderRoot(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Current screen, stored as a string to survive Activity recreation
    // (a theme change, for example): "" is the folder list, anything else is the
    // key of the open folder. It lives up here, before the permissions screen's
    // `return`s, so it isn't forgotten while that screen is showing.
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    val stores: ScreenStores = viewModel()
    fun go(screen: Screen) {
        // Leaving a folder frees its ViewModel; the next visit starts fresh.
        saved?.takeIf { it.isNotEmpty() }?.let { leaving ->
            if (screen !is Screen.Swipe || screen.folder.key != leaving) stores.clear(leaving)
        }
        saved = when (screen) {
            Screen.Folders -> ""
            is Screen.Swipe -> screen.folder.key
        }
    }

    // Permissions can change outside the app (in Settings), so they are
    // checked again every time the app returns to the foreground.
    var hasMedia by remember { mutableStateOf(hasMediaPermission(context)) }
    var canManage by remember { mutableStateOf(container.media.canManageMedia()) }
    LifecycleResumeEffect(Unit) {
        hasMedia = hasMediaPermission(context)
        canManage = container.media.canManageMedia()
        onPauseOrDispose { }
    }
    val skipManage by container.prefs.skipManageMedia.collectAsState(initial = null)

    val background = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)

    // Still reading preferences: an empty frame, without flashing the permissions screen.
    val skip = skipManage ?: run { Box(background); return }

    if (!hasMedia || (!canManage && !skip)) {
        PermissionsScreen(
            hasMedia = hasMedia,
            canManage = canManage,
            onRecheck = {
                hasMedia = hasMediaPermission(context)
                canManage = container.media.canManageMedia()
            },
            onSkipManage = { scope.launch { container.prefs.setSkipManageMedia(true) } },
        )
        return
    }

    LaunchedEffect(Unit) {
        if (saved == null) {
            val last = container.prefs.lastFolder.first()
            go(if (last != null) Screen.Swipe(last) else Screen.Folders)
        }
    }
    val screen = saved?.let { key -> FolderRef.fromKey(key)?.let(Screen::Swipe) ?: Screen.Folders }

    when (val current = screen) {
        null -> Box(background)

        Screen.Folders -> FoldersScreen(
            container = container,
            onOpen = { folder ->
                scope.launch { container.prefs.setLastFolder(folder) }
                go(Screen.Swipe(folder))
            },
        )

        is Screen.Swipe -> {
            BackHandler { go(Screen.Folders) }
            SwipeScreen(
                container = container,
                folder = current.folder,
                owner = stores.ownerFor(current.folder.key),
                onChangeFolder = { go(Screen.Folders) },
            )
        }
    }
}

/** Read permissions that together grant full access. They were renamed in Android 13. */
private fun fullAccessPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/**
 * What to request from the system. On Android 14+ this includes the "limited
 * access" permission, so the dialog works in normal mode (see the manifest).
 */
fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        fullAccessPermissions() + Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    } else {
        fullAccessPermissions()
    }

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * `true` only with full access. Android 14's "limited access" (picking
 * a few photos) isn't enough: the app needs to see the whole folder.
 */
fun hasMediaPermission(context: Context): Boolean =
    fullAccessPermissions().all { granted(context, it) }

/** The user chose "limited access": the app would only see the selected photos. */
fun hasPartialMediaAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        !hasMediaPermission(context) &&
        granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
