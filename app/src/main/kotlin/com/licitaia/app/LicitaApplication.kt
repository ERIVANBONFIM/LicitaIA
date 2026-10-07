package com.licitaia.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LicitaApplication : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.WEBVIEW_DEBUG) runCatching { android.webkit.WebView.setWebContentsDebuggingEnabled(true) }
        PersonalAlertsWorker.schedule(this)
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
