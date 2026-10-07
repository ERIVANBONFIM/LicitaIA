package com.licitaia.feature.bidding.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liga/desliga o [AssistedSessionService] conforme o número de sessões assistidas ativas.
 *
 * Regras de segurança (Android 12+ restringe FGS iniciado em segundo plano):
 * - só chama startForegroundService com o app em primeiro plano ([ProcessLifecycleOwner] ≥ STARTED);
 *   em segundo plano, guarda o pedido e dispara no próximo ON_START;
 * - com o serviço já rodando, só atualiza a notificação (sem novo start);
 * - tudo em try/catch: falha aqui nunca derruba o motor de sessões.
 *
 * Toda a lógica roda na thread principal (exigência do Lifecycle).
 */
@Singleton
class AssistedSessionServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : AssistedSessionKeepAlive, com.licitaia.feature.live.automation.PortalRobotForeground {

    private val main = Handler(Looper.getMainLooper())
    private val lifecycle: Lifecycle get() = ProcessLifecycleOwner.get().lifecycle

    /** Último pedido (sessões ativas). */
    private var requestedSessions = 0
    /** Robôs do Comprasnet em execução (proposta/lance). */
    private var requestedRobots = 0
    private val requested: Int get() = requestedSessions + requestedRobots
    /** true = startForegroundService já foi aceito e o serviço não foi parado por nós. */
    private var running = false
    private var observing = false

    private val onForeground = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // Assíncrono: addObserver com o processo já STARTED despacha ON_START na hora; evita reentrância.
            main.post { runCatching { apply() } }
        }
    }

    override fun update(activeSessions: Int) {
        val count = activeSessions.coerceAtLeast(0)
        runOnMain {
            requestedSessions = count
            apply()
        }
    }

    /** Robôs em execução contam como acompanhamento ativo (notificação com a ação PARAR). */
    override fun updateRobots(active: Int) {
        val count = active.coerceAtLeast(0)
        runOnMain {
            requestedRobots = count
            apply()
        }
    }

    /** Progresso do robô de proposta ("Item 12 de 77 · …") na notificação persistente. */
    override fun updateProgress(text: String?) {
        runOnMain {
            if (robotProgress == text) return@runOnMain
            robotProgress = text
            if (running && requested > 0) {
                runCatching { NotificationManagerCompat.from(context).notify(AssistedSessionService.NOTIFICATION_ID, AssistedSessionService.buildNotification(context, requestedSessions, requestedRobots, robotProgress)) }
            }
        }
    }

    private var robotProgress: String? = null

    private fun runOnMain(block: () -> Unit) {
        val safe = { runCatching(block) }
        if (Looper.myLooper() == Looper.getMainLooper()) safe() else main.post { safe() }
    }

    /** Reconcilia o estado desejado ([requested]) com o serviço. Sempre na thread principal. */
    private fun apply() {
        if (requested <= 0) {
            stopService()
            return
        }
        if (running) {
            // Atualização do contador: não exige novo start (que poderia ser bloqueado em segundo plano).
            runCatching { NotificationManagerCompat.from(context).notify(AssistedSessionService.NOTIFICATION_ID, AssistedSessionService.buildNotification(context, requestedSessions, requestedRobots, robotProgress)) }
            return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            // App em segundo plano: iniciar FGS agora lançaria ForegroundServiceStartNotAllowedException.
            observe()
            return
        }
        running = try {
            ContextCompat.startForegroundService(context, AssistedSessionService.startIntent(context, requestedSessions, requestedRobots))
            true
        } catch (e: Exception) {
            // IllegalStateException / ForegroundServiceStartNotAllowedException: tenta de novo no próximo ON_START.
            false
        }
        if (running) unobserve() else observe()
    }

    private fun stopService() {
        unobserve()
        if (!running) {
            // Garantia extra: se um processo anterior deixou a notificação, remove-a.
            runCatching { NotificationManagerCompat.from(context).cancel(AssistedSessionService.NOTIFICATION_ID) }
            return
        }
        running = false
        // stopService é permitido em segundo plano; o onDestroy do serviço remove a notificação.
        runCatching { context.stopService(AssistedSessionService.stopIntent(context)) }
        runCatching { NotificationManagerCompat.from(context).cancel(AssistedSessionService.NOTIFICATION_ID) }
    }

    private fun observe() {
        if (observing) return
        observing = true
        if (runCatching { lifecycle.addObserver(onForeground) }.isFailure) observing = false
    }

    private fun unobserve() {
        if (!observing) return
        observing = false
        runCatching { lifecycle.removeObserver(onForeground) }
    }
}
