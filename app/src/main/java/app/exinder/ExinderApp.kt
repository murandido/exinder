package app.exinder

import android.app.Application
import androidx.room.Room
import app.exinder.data.AppDatabase
import app.exinder.data.DecisionDao
import app.exinder.data.MediaRepository
import app.exinder.data.Prefs
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Ponto de entrada do processo.
 *
 * Também configura o Coil (carregador de imagens) para entender GIFs animados
 * e tirar um quadro dos vídeos para usar como miniatura.
 */
class ExinderApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
}

/**
 * Injeção de dependências feita à mão: o app é pequeno e não precisa de Hilt.
 */
class AppContainer(app: Application) {

    val decisions: DecisionDao = Room
        .databaseBuilder(app, AppDatabase::class.java, "exinder.db")
        .build()
        .decisions()

    val media = MediaRepository(app)

    val prefs = Prefs(app)

    /**
     * Escopo que vive tanto quanto o processo. O envio para a lixeira roda
     * aqui para não ser cancelado se o usuário trocar de tela no meio.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
