package app.exinder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.exinder.data.FolderRef
import app.exinder.data.SystemRequests
import app.exinder.ui.folders.FoldersScreen
import app.exinder.ui.permissions.PermissionsScreen
import app.exinder.ui.swipe.SwipeScreen
import app.exinder.ui.theme.ExinderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A única Activity do app. A navegação entre telas é feita em Compose. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Precisa acontecer antes da Activity ficar visível.
        SystemRequests.attach(this)

        val container = (application as ExinderApp).container
        setContent {
            ExinderTheme {
                ExinderRoot(container)
            }
        }
    }

    override fun onDestroy() {
        SystemRequests.detach(this)
        super.onDestroy()
    }
}

/** As telas do app. */
private sealed interface Screen {
    data object Folders : Screen
    data class Swipe(val folder: FolderRef) : Screen
}

/**
 * Raiz da navegação:
 * 1. sem permissão → tela de permissões;
 * 2. com permissão → última pasta aberta, ou a lista de pastas.
 */
@Composable
private fun ExinderRoot(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // As permissões podem mudar fora do app (nas configurações), então são
    // conferidas de novo toda vez que o app volta para a frente.
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

    // Ainda lendo as preferências: um frame vazio, sem piscar a tela de permissões.
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

    var screen by remember { mutableStateOf<Screen?>(null) }
    LaunchedEffect(Unit) {
        if (screen == null) {
            val last = container.prefs.lastFolder.first()
            screen = if (last != null) Screen.Swipe(last) else Screen.Folders
        }
    }

    when (val current = screen) {
        null -> Box(background)

        Screen.Folders -> FoldersScreen(
            container = container,
            onOpen = { folder ->
                scope.launch { container.prefs.setLastFolder(folder) }
                screen = Screen.Swipe(folder)
            },
        )

        is Screen.Swipe -> {
            BackHandler { screen = Screen.Folders }
            SwipeScreen(
                container = container,
                folder = current.folder,
                onChangeFolder = { screen = Screen.Folders },
            )
        }
    }
}

/** Permissões de leitura que, juntas, dão acesso total. Mudaram de nome no Android 13. */
private fun fullAccessPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/**
 * O que pedir ao sistema. No Android 14+ inclui a permissão do "acesso
 * limitado", para o diálogo funcionar no modo normal (ver o manifesto).
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
 * `true` só com acesso total. O "acesso limitado" do Android 14 (escolher
 * algumas fotos) não serve: o app precisa ver a pasta inteira.
 */
fun hasMediaPermission(context: Context): Boolean =
    fullAccessPermissions().all { granted(context, it) }

/** O usuário escolheu "acesso limitado": o app só veria as fotos selecionadas. */
fun hasPartialMediaAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        !hasMediaPermission(context) &&
        granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
