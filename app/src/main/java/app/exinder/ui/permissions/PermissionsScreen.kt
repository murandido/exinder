package app.exinder.ui.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.exinder.mediaPermissions
import app.exinder.ui.theme.Brat
import app.exinder.ui.theme.ExinderLogo
import app.exinder.ui.theme.Ink

/**
 * Primeira tela: pede as duas permissões que o app precisa.
 *
 * 1. **Fotos e vídeos**: obrigatória, sem ela não há o que revisar.
 * 2. **Gerenciamento de mídia**: recomendada. Sem ela, o Android pergunta
 *    "mover para a lixeira?" a cada arquivo, o que acaba com o ritmo do swipe.
 */
@Composable
fun PermissionsScreen(
    hasMedia: Boolean,
    canManage: Boolean,
    onRecheck: () -> Unit,
    onSkipManage: () -> Unit,
) {
    val context = LocalContext.current
    val requestMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onRecheck() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ExinderLogo(size = 64.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "faxina na galeria, um swipe por vez",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))

        PermissionStep(
            icon = Icons.Rounded.PhotoLibrary,
            title = "fotos e vídeos",
            description = "pra montar a fila de cada pasta",
            granted = hasMedia,
            action = "permitir",
            onAction = { requestMedia.launch(mediaPermissions()) },
        )
        Spacer(Modifier.height(12.dp))
        PermissionStep(
            icon = Icons.Rounded.DeleteSweep,
            title = "gerenciamento de mídia",
            description = "pra mandar pra lixeira sem o Android perguntar a cada arquivo",
            granted = canManage,
            action = "abrir ajustes",
            onAction = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_MANAGE_MEDIA,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
        )

        // A segunda permissão é opcional, mas só depois da primeira.
        if (hasMedia && !canManage) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onSkipManage) {
                Text("seguir sem essa", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Um cartão com a explicação de uma permissão e o botão que a pede. */
@Composable
private fun PermissionStep(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Brat, modifier = Modifier.size(32.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "concedida",
                tint = Brat,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Brat, contentColor = Ink),
            ) { Text(action, fontWeight = FontWeight.Bold) }
        }
    }
}
