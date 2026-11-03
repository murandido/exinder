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
 * Ponte entre o código que roda em segundo plano e os pedidos do sistema
 * (lixeira e favorito), que só uma Activity consegue disparar.
 *
 * A Activity se registra em [attach]; a partir daí qualquer coroutine pode
 * chamar [run] e esperar o resultado como uma função `suspend` comum.
 */
object SystemRequests {

    private var owner: ComponentActivity? = null
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<Boolean>? = null

    /** Um pedido de cada vez: o Android não empilha esses intents. */
    private val mutex = Mutex()

    /** Chamado no `onCreate` da Activity, antes dela ficar visível. */
    fun attach(activity: ComponentActivity) {
        owner = activity
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            pending?.complete(result.resultCode == Activity.RESULT_OK)
            pending = null
        }
    }

    /** Chamado no `onDestroy`. Um pedido em andamento é dado como recusado. */
    fun detach(activity: ComponentActivity) {
        if (owner !== activity) return
        owner = null
        launcher = null
        pending?.complete(false)
        pending = null
    }

    /**
     * Dispara o [request] e espera a resposta.
     *
     * @return `true` se o sistema aplicou o pedido; `false` se o usuário
     *   recusou ou se não havia Activity para dispará-lo.
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
