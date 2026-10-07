package com.licitaia.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.licitaia.app.daily.DailySyncController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LicitaApplication : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.WEBVIEW_DEBUG) runCatching { android.webkit.WebView.setWebContentsDebuggingEnabled(true) }
        PersonalAlertsWorker.schedule(this)
        // Concorrência: resultados públicos (PNCP) das licitações da empresa, 1x/dia às 06:00.
        CompetitionResultsWorker.schedule(this)
        // Atualização diária (05:30): agenda o alarme, roda a recuperação se o horário passou e a limpeza do dia.
        dailySync.onAppOpened()
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var dailySync: DailySyncController

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
