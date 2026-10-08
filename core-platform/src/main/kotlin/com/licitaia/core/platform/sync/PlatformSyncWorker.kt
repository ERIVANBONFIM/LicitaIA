package com.licitaia.core.platform.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.session.PlatformSession
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Sincronização periódica de LEITURA da plataforma (CONTRATO §4.3: pull a cada 15-30 min e ao abrir).
 * Só roda com sessão ativa; não é agendada automaticamente no boot do app (o modo plataforma é opt-in):
 * o fluxo de login chama [enqueue] e o logout chama [cancel].
 */
@HiltWorker
class PlatformSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: PlatformRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        repository.ensureSessionLoaded()
        if (repository.session.value !is PlatformSession.SignedIn) return Result.success()
        return repository.syncTenders().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val WORK_NAME = "licitapro_platform_sync"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<PlatformSyncWorker>(20, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
