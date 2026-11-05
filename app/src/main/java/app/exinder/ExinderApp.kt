package app.exinder

import android.app.Application
import androidx.room.Room
import app.exinder.data.AppDatabase
import app.exinder.data.DecisionDao
import app.exinder.data.MIGRATIONS
import app.exinder.data.MediaFile
import app.exinder.data.MediaRepository
import app.exinder.data.Prefs
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Process entry point.
 *
 * Also configures Coil (the image loader) to decode animated GIFs and grab a
 * frame from videos to use as a thumbnail.
 */
class ExinderApp :
    Application(),
    SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
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
        .addMigrations(*MIGRATIONS)
        .build()
        .decisions()

    val media = MediaRepository(app)

    val prefs = Prefs(app)

    /**
     * Scope that lives as long as the process. Sending to the trash runs here
     * so it isn't cancelled if the user switches screens halfway through.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Keys of files with a trash request in progress, across the whole process.
     * A folder reloaded while a request is still waiting (for example behind
     * the system confirmation dialog) must not send the same file again.
     */
    val trashInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Outcome of every trash request, for whichever ViewModel shows the folder
     * when it finishes (the one that started it may be gone by then).
     */
    val trashResults = MutableSharedFlow<TrashResult>(extraBufferCapacity = 64)
}

/**
 * How a trash request for a folder ended: [trashed] files went to the trash,
 * [stayed] are still on the device (the request was denied or failed).
 */
data class TrashResult(val folderKey: String, val trashed: Int, val stayed: List<MediaFile>)
