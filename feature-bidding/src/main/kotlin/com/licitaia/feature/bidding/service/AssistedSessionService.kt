package com.licitaia.feature.bidding.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.feature.bidding.R

/**
 * Foreground Service que mantém o processo vivo enquanto houver pregões assistidos com cronômetro
 * em andamento ou alertas ativos. Não processa nada: só publica a notificação persistente
 * "Pregão em acompanhamento" (canal SESSOES, silenciosa, baixa prioridade) com a ação "Abrir".
 *
 * Quem decide iniciar/parar é [AssistedSessionServiceController] (a partir do motor de sessões).
 * Tipo `dataSync` (Android 14): declarado no manifest do módulo e passado ao startForeground.
 */
class AssistedSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        if (count <= 0) {
            // Reinício sem extras (ou pedido vazio): não há o que acompanhar.
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        val started = try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(this, count),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            true
        } catch (e: Exception) {
            // Inclui ForegroundServiceStartNotAllowedException (Android 12+) e SecurityException: nunca crashar.
            false
        }
        if (!started) stopSelf()
        // Processo morto pelo sistema: não religa sozinho (o motor reinicia quando o usuário reabrir o app).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.licitaia.feature.bidding.action.STOP_ASSISTED_SESSIONS"
        const val EXTRA_COUNT = "count"
        /** Id fixo: a notificação é sempre substituída, nunca duplicada. */
        const val NOTIFICATION_ID = 7001
        /** Mesmo id de canal usado pelo AppNotifier para a categoria SESSOES ("licitaia.sessoes"). */
        private val CHANNEL_ID = "licitaia.${NotificationCategory.SESSOES.name.lowercase()}"
        /** Extra lido pela MainActivity para navegar até a rota ao tocar na notificação. */
        private const val EXTRA_ROUTE = "route"

        fun startIntent(context: Context, count: Int): Intent =
            Intent(context, AssistedSessionService::class.java).putExtra(EXTRA_COUNT, count)

        fun stopIntent(context: Context): Intent =
            Intent(context, AssistedSessionService::class.java).setAction(ACTION_STOP)

        /** Garante o canal SESSOES (idempotente; se o AppNotifier já criou, nada muda). */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, NotificationCategory.SESSOES.label, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Abertura e encerramento de sessões de pregão"
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        fun buildNotification(context: Context, count: Int): Notification {
            ensureChannel(context)
            val title = "Pregão em acompanhamento: $count sessão(ões)"
            val body = "Cronômetro e alertas das sessões assistidas continuam ativos em segundo plano."
            val open = openIntent(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_assisted_session)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(0xFF3B82F6.toInt())
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            if (open != null) {
                builder.setContentIntent(open)
                builder.addAction(0, "Abrir", open)
            }
            return builder.build()
        }

        private fun openIntent(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            launch.putExtra(EXTRA_ROUTE, Routes.LIVE)
            return PendingIntent.getActivity(
                context, NOTIFICATION_ID, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
