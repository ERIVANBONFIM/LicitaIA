package com.licitaia.core.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground Service curto (tipo `dataSync`) que mantém o processo do app em prioridade de primeiro plano
 * enquanto a Custom Tab do "Entrar com ChatGPT" está aberta, para o servidor de retorno em 127.0.0.1 não ser
 * congelado/morto. Não faz trabalho: só a notificação "Aguardando autorização do ChatGPT…" com "Cancelar".
 * Para sozinho em 6 min (o login expira em 5).
 */
class ChatGptLoginService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val selfStop = Runnable { stopForegroundAndSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundAndSelf(); return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                runCatching { onCancel?.invoke() }
                stopForegroundAndSelf(); return START_NOT_STICKY
            }
        }
        val started = try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException/SecurityException: o login segue sem o serviço.
            false
        }
        if (!started) {
            stopSelf(); return START_NOT_STICKY
        }
        handler.removeCallbacks(selfStop)
        handler.postDelayed(selfStop, MAX_LIFETIME_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(selfStop)
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
        private const val ACTION_STOP = "com.licitaia.core.ai.action.STOP_CHATGPT_LOGIN"
        private const val ACTION_CANCEL = "com.licitaia.core.ai.action.CANCEL_CHATGPT_LOGIN"
        private const val NOTIFICATION_ID = 7101
        private const val CHANNEL_ID = "licitaia.chatgpt_login"
        private const val MAX_LIFETIME_MS = 6L * 60 * 1000

        /** Ação "Cancelar" da notificação (definida pelo [ChatGptAuthorizer] ao iniciar). */
        @Volatile private var onCancel: (() -> Unit)? = null

        /** Inicia com o app em primeiro plano; qualquer falha é ignorada (o login continua). */
        fun start(context: Context, cancel: () -> Unit) {
            onCancel = cancel
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ChatGptLoginService::class.java)) }
        }

        fun stop(context: Context) {
            onCancel = null
            runCatching { context.stopService(Intent(context, ChatGptLoginService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Entrar com ChatGPT", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aviso enquanto o LicitaIA espera a autorização do ChatGPT no navegador"
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }

        private fun buildNotification(context: Context): Notification {
            ensureChannel(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Aguardando autorização do ChatGPT…")
                .setContentText("Conclua a entrada no navegador e volte ao LicitaIA.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                builder.setContentIntent(
                    PendingIntent.getActivity(context, NOTIFICATION_ID, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
            }
            val cancel = PendingIntent.getService(
                context, NOTIFICATION_ID + 1,
                Intent(context, ChatGptLoginService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Cancelar", cancel)
            return builder.build()
        }
    }
}
