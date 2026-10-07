package com.licitaia.app.daily

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.licitaia.core.data.di.DataScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Alarme do horário diário (05:30): enfileira a sincronização completa e já agenda o próximo dia (salvaguarda). */
@AndroidEntryPoint
class DailySyncAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: DailySyncController
    @Inject @field:DataScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailySyncController.ACTION_ALARM) return
        DailySyncController.enqueue(context.applicationContext)
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) { controller.reschedule(System.currentTimeMillis() + 60_000L) }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Reagenda o alarme diário após reiniciar o aparelho, atualizar o app ou mudar hora/fuso/permissão de alarme exato
 * (o sistema apaga os alarmes nesses casos) e roda a recuperação se o horário de hoje já passou sem sincronização.
 */
@AndroidEntryPoint
class DailySyncBootReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: DailySyncController
    @Inject @field:DataScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    runCatching { controller.reschedule() }
                    runCatching { controller.enqueueCatchUpIfDue() }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

/** goAsync dá ~10 s ao receiver; ler o DataStore e agendar leva milissegundos. */
private const val RECEIVER_BUDGET_MS = 8_000L
