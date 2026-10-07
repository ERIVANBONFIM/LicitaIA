package com.licitaia.app.daily

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.licitaia.core.data.di.DataScope
import com.licitaia.core.data.repository.ListingMaintenance
import com.licitaia.core.data.settings.DailySyncPrefs
import com.licitaia.domain.sync.DailySyncRepository
import com.licitaia.domain.sync.DailySyncSchedule
import com.licitaia.domain.sync.DailySyncSettings
import com.licitaia.domain.sync.DailySyncStatus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Atualização diária das licitações (padrão 05:30, fuso de Brasília):
 * - um alarme ([AlarmManager.setAndAllowWhileIdle]; exato só se [AlarmManager.canScheduleExactAlarms]) acorda
 *   [DailySyncAlarmReceiver], que enfileira [DailySyncWorker] (rede conectada; expedited quando houver cota);
 * - o Worker reagenda o próximo dia ao terminar; [DailySyncBootReceiver] reagenda após reiniciar/atualizar o app;
 * - perdeu o horário (desligado/sem rede): ao abrir o app ([onAppOpened]) roda a de recuperação em segundo plano;
 * - limpeza do cache local no fim da sincronização e, se ainda não rodou no dia, ao abrir o app.
 */
@Singleton
class DailySyncController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: DailySyncPrefs,
    private val maintenance: ListingMaintenance,
    @DataScope private val scope: CoroutineScope,
) : DailySyncRepository {

    override val settings: Flow<DailySyncSettings> = prefs.settings
    override val status: Flow<DailySyncStatus> = prefs.status

    private val cleanupLock = Mutex()

    override suspend fun updateSettings(transform: (DailySyncSettings) -> DailySyncSettings) {
        prefs.update(transform)
        reschedule()
    }

    override fun onAppOpened() {
        scope.launch {
            runCatching { reschedule() }
            runCatching { enqueueCatchUpIfDue() }
            runCatching { cleanupIfNotToday() }
        }
    }

    /** Agenda (ou cancela, se desligada) o alarme do próximo horário. */
    suspend fun reschedule(now: Long = System.currentTimeMillis()) {
        val s = prefs.currentSettings()
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        if (!s.enabled) {
            alarms.cancel(pending)
            return
        }
        val at = DailySyncSchedule.nextRun(now, s.hour, s.minute)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    /** Recuperação: a última sincronização completa é anterior ao último horário agendado que já passou. */
    suspend fun enqueueCatchUpIfDue(now: Long = System.currentTimeMillis()) {
        val s = prefs.currentSettings()
        val status = prefs.currentStatus()
        if (!status.running && DailySyncSchedule.isCatchUpDue(now, status.lastCompletedAt, s)) enqueue(context)
    }

    /** Limpeza ao abrir o app, se ainda não rodou hoje. */
    suspend fun cleanupIfNotToday(now: Long = System.currentTimeMillis()) {
        val last = prefs.currentStatus().lastCleanupAt
        if (last != null && DailySyncSchedule.sameDay(last, now)) return
        runCleanup(now)
    }

    /** Limpeza do cache local (canceladas, encerradas, antigas, editais órfãos); VACUUM no máximo 1x por semana. */
    suspend fun runCleanup(now: Long = System.currentTimeMillis()): ListingMaintenance.Result = cleanupLock.withLock {
        val lastVacuum = prefs.lastVacuumAt()
        val vacuum = lastVacuum == null || now - lastVacuum >= VACUUM_INTERVAL_MS
        maintenance.cleanup(now, DailySyncSchedule.monthStart(now), vacuum).also { prefs.markCleanup(now, it.vacuumed) }
    }

    companion object {
        const val WORK_NAME = "daily-full-sync"
        private const val REQUEST_ALARM = 5530
        const val ACTION_ALARM = "com.licitaia.app.action.DAILY_SYNC"
        private const val VACUUM_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

        fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_ALARM,
            Intent(context, DailySyncAlarmReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Enfileira a sincronização diária (uma por vez; com rede; expedited quando houver cota). */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DailySyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DailySyncModule {
    /** feature-radar/feature-settings usam a atualização diária sem depender do módulo app. */
    @Binds
    abstract fun bindDailySyncRepository(impl: DailySyncController): DailySyncRepository
}
