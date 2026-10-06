package com.licitaia.feature.live.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.licitaia.domain.model.AppNotification
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.NotificationRepository
import com.licitaia.domain.repository.SettingsRepository
import com.licitaia.feature.live.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notificador unificado: persiste, publica push local no canal da categoria e emite alerta in-app.
 * Nunca crasha por falta de permissão: sem POST_NOTIFICATIONS o push é apenas omitido.
 */
@Singleton
class AppNotifierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: NotificationRepository,
    private val settings: SettingsRepository,
) : AppNotifier {

    private val alerts = MutableSharedFlow<AppNotification>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inAppAlerts: SharedFlow<AppNotification> = alerts

    private val channelsReady = AtomicBoolean(false)

    override suspend fun notify(
        category: NotificationCategory,
        title: String,
        body: String,
        critical: Boolean,
        route: String?,
        sessionId: String?,
        companyId: Long?,
    ) {
        var notification = AppNotification(
            companyId = companyId, category = category, title = title, body = body,
            createdAt = System.currentTimeMillis(), critical = critical, route = route, sessionId = sessionId,
        )
        val id = try {
            repository.insert(notification)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0L
        }
        notification = notification.copy(id = id)
        alerts.tryEmit(notification)

        val current = try {
            settings.settings.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AppSettings()
        }
        if (current.pushEnabled) runCatching { publish(notification) }
        if (category == NotificationCategory.CAPTCHA && current.captchaVibrate) runCatching { vibrate() }
    }

    override fun cancelSessionAlerts(sessionId: String) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            SESSION_ALERT_CATEGORIES.forEach { manager.cancel(sessionTag(sessionId), SESSION_ID_BASE + it.ordinal) }
        }
    }

    // ------------------------------------------------------------------ push local

    private fun publish(n: AppNotification) {
        if (!canPost()) return
        ensureChannels()
        val builder = NotificationCompat.Builder(context, channelId(n.category))
            .setSmallIcon(R.drawable.ic_stat_licitaia)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setAutoCancel(true)
            .setWhen(n.createdAt)
            .setShowWhen(true)
            .setPriority(if (n.critical || n.category.priority <= 2) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (n.critical) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setColor(if (n.critical) 0xFFEF4444.toInt() else 0xFF3B82F6.toInt())
            .setColorized(false)
        if (n.critical) builder.setOngoing(false).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        contentIntent(n)?.let(builder::setContentIntent)

        val manager = NotificationManagerCompat.from(context)
        val sessionId = n.sessionId
        try {
            if (sessionId != null) {
                // Um id por (sessão, categoria): repetições do CAPTCHA substituem a anterior e podem ser canceladas em bloco.
                manager.notify(sessionTag(sessionId), SESSION_ID_BASE + n.category.ordinal, builder.build())
            } else {
                manager.notify((n.id.takeIf { it != 0L } ?: n.createdAt).toInt() and 0x7FFFFFFF, builder.build())
            }
        } catch (_: SecurityException) {
            // Permissão revogada entre a checagem (canPost) e o post: o push é apenas omitido.
        }
    }

    private fun contentIntent(n: AppNotification): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        n.route?.let { launch.putExtra(EXTRA_ROUTE, it) }
        n.sessionId?.let { launch.putExtra(EXTRA_SESSION_ID, it) }
        launch.putExtra(EXTRA_NOTIFICATION_ID, n.id)
        val requestCode = (n.route.orEmpty() + "|" + n.sessionId.orEmpty() + "|" + n.category.name).hashCode()
        return PendingIntent.getActivity(
            context, requestCode, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannels() {
        if (channelsReady.get()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        NotificationCategory.entries.forEach { category ->
            val importance = when (category) {
                NotificationCategory.CAPTCHA, NotificationCategory.CRITICA, NotificationCategory.LANCES -> NotificationManager.IMPORTANCE_HIGH
                NotificationCategory.MENSAGENS, NotificationCategory.DOCUMENTOS, NotificationCategory.SESSOES -> NotificationManager.IMPORTANCE_DEFAULT
                NotificationCategory.RADAR, NotificationCategory.GERAL -> NotificationManager.IMPORTANCE_LOW
            }
            val channel = NotificationChannel(channelId(category), category.label, importance).apply {
                description = channelDescription(category)
                when (category) {
                    // A vibração do CAPTCHA é controlada pela configuração do app (AppSettings.captchaVibrate).
                    NotificationCategory.CAPTCHA -> { enableVibration(false); enableLights(true); lightColor = 0xFFEF4444.toInt() }
                    NotificationCategory.CRITICA -> { enableVibration(true); vibrationPattern = longArrayOf(0, 250, 120, 250); enableLights(true); lightColor = 0xFFEF4444.toInt() }
                    NotificationCategory.LANCES -> enableVibration(true)
                    else -> enableVibration(false)
                }
            }
            manager.createNotificationChannel(channel)
        }
        channelsReady.set(true)
    }

    private fun vibrate() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() != true) return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 500), -1))
    }

    private fun channelId(category: NotificationCategory) = "licitaia.${category.name.lowercase()}"
    private fun sessionTag(sessionId: String) = "session:$sessionId"

    private fun channelDescription(c: NotificationCategory) = when (c) {
        NotificationCategory.CAPTCHA -> "CAPTCHA/MFA aguardando resolução manual em uma sessão de pregão"
        NotificationCategory.CRITICA -> "Erros críticos, paradas de emergência e alertas que exigem ação imediata"
        NotificationCategory.LANCES -> "Autorizações de lance, piso atingido e eventos do robô"
        NotificationCategory.MENSAGENS -> "Mensagens do pregoeiro"
        NotificationCategory.DOCUMENTOS -> "Documentos vencendo ou vencidos"
        NotificationCategory.SESSOES -> "Abertura e encerramento de sessões de pregão"
        NotificationCategory.RADAR -> "Novas oportunidades encontradas pelos radares"
        NotificationCategory.GERAL -> "Avisos gerais do LicitaIA"
    }

    companion object {
        /** Extra String lido pela MainActivity para navegar até a rota ao tocar na notificação. */
        const val EXTRA_ROUTE = "route"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        private const val SESSION_ID_BASE = 1000
        private val SESSION_ALERT_CATEGORIES = listOf(
            NotificationCategory.CAPTCHA, NotificationCategory.CRITICA, NotificationCategory.LANCES, NotificationCategory.SESSOES,
        )
    }
}
