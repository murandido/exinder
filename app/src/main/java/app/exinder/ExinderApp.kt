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
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process entry point.
 *
 * Also configures Coil (the image loader) to decode animated GIFs and grab a
 * frame from videos to use as a thumbnail.
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
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
}

/**
 * Hand-rolled dependency injection: the app is small and doesn't need Hilt.
 */
class AppContainer(app: Application) {

    val decisions: DecisionDao = Room
        .databaseBuilder(app, AppDatabase::class.java, "exinder.db")
        .build()
        .decisions()

    val media = MediaRepository(app)

    val prefs = Prefs(app)

    /**
     * Scope that lives as long as the process. Sending to the trash runs here
     * so it isn't cancelled if the user switches screens halfway through.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
