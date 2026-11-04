package app.exinder.data

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bridge between code running in the background and system requests
 * (trash and favorite), which only an Activity can launch.
 *
 * The Activity registers itself in [attach]; from then on any coroutine can
 * call [run] and await the result like a regular `suspend` function.
 */
object SystemRequests {

    private var owner: ComponentActivity? = null
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<Boolean>? = null

    /** One request at a time: Android doesn't queue these intents. */
    private val mutex = Mutex()

    /** Called from the Activity's `onCreate`, before it becomes visible. */
    fun attach(activity: ComponentActivity) {
        owner = activity
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            pending?.complete(result.resultCode == Activity.RESULT_OK)
            pending = null
        }
    }

    /**
     * Called from `onDestroy`.
     *
     * If the Activity is only being recreated (light/dark theme change, language
     * change...), a pending request keeps waiting: Android delivers the result
     * to the launcher the new Activity registers in [attach]. Treating the
     * request as denied here would make the app record the opposite of what the
     * user answered in the dialog.
     *
     * The same goes for the system destroying the Activity without finishing it
     * (low memory, "Don't keep activities"): it comes back and gets the result.
     * Only an Activity finishing for good treats the request as denied.
     */
    fun detach(activity: ComponentActivity) {
        if (owner !== activity) return
        owner = null
        launcher = null
        if (activity.isFinishing) {
            pending?.complete(false)
            pending = null
        }
    }

    /**
     * Launches [request] and waits for the response.
     *
     * @return `true` if the system applied the request; `false` if the user
     *   declined or there was no Activity to launch it.
     */
    suspend fun run(request: PendingIntent): Boolean = mutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        val started = withContext(Dispatchers.Main) {
            val current = launcher ?: return@withContext false
            pending = deferred
            current.launch(IntentSenderRequest.Builder(request.intentSender).build())
            true
        }
        if (started) deferred.await() else false
    }
}
