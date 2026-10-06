package com.licitaia.feature.live.keepalive

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
import com.licitaia.feature.live.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Foreground Service (`dataSync`) do "Manter sessão ativa": mantém o processo vivo enquanto algum portal
 * está com a recarga periódica ligada e mostra a notificação baixa/silenciosa "Mantendo sessão do … ativa"
 * com a ação "Parar". Não faz o trabalho em si — o ciclo fica no [PortalKeepAliveController].
 */
class PortalKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Toque em "Parar": desliga a preferência (auditado) e encerra o serviço.
            runCatching { controller(this).stopFromNotification() }
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        val portals = intent?.getStringArrayListExtra(EXTRA_PORTALS).orEmpty()
        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 8) ?: 8
        if (portals.isEmpty()) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        val started = try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(this, portals, minutes),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+) / SecurityException: nunca crashar.
            false
        }
        if (!started) stopSelf()
        // Processo morto: não religa sozinho; o controlador retoma quando o app voltar ao primeiro plano.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        runCatching { controller(this).onServiceStopped() }
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface KeepAliveEntryPoint {
        fun keepAliveController(): PortalKeepAliveController
    }

    companion object {
        const val ACTION_STOP = "com.licitaia.feature.live.action.STOP_PORTAL_KEEP_ALIVE"
        const val EXTRA_PORTALS = "portals"
        const val EXTRA_MINUTES = "minutes"
        /** Id fixo: a notificação é sempre substituída, nunca duplicada. */
        const val NOTIFICATION_ID = 7101
        /** Mesmo canal da categoria SESSOES usado pelo AppNotifier ("licitaia.sessoes"). */
        private val CHANNEL_ID = "licitaia.${NotificationCategory.SESSOES.name.lowercase()}"
        private const val EXTRA_ROUTE = "route"

        internal fun controller(context: Context): PortalKeepAliveController =
            EntryPointAccessors.fromApplication(context.applicationContext, KeepAliveEntryPoint::class.java).keepAliveController()

        fun startIntent(context: Context, portalNames: List<String>, minutes: Int): Intent =
            Intent(context, PortalKeepAliveService::class.java)
                .putStringArrayListExtra(EXTRA_PORTALS, ArrayList(portalNames))
                .putExtra(EXTRA_MINUTES, minutes)

        fun stopIntent(context: Context): Intent =
            Intent(context, PortalKeepAliveService::class.java).setAction(ACTION_STOP)

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, NotificationCategory.SESSOES.label, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Abertura e encerramento de sessões de pregão"
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        fun buildNotification(context: Context, portalNames: List<String>, minutes: Int): Notification {
            ensureChannel(context)
            val title = "Mantendo sessão do ${portalNames.joinToString(", ")} ativa"
            val body = "Recarrega sua página do portal a cada $minutes min. O portal ainda pode encerrar a sessão pelo tempo máximo dele; nesse caso você recebe um alerta."
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_licitaia)
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
            openIntent(context)?.let(builder::setContentIntent)
            val stop = PendingIntent.getService(
                context, NOTIFICATION_ID, stopIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Parar", stop)
            return builder.build()
        }

        private fun openIntent(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            launch.putExtra(EXTRA_ROUTE, Routes.PORTALS)
            return PendingIntent.getActivity(
                context, NOTIFICATION_ID, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
