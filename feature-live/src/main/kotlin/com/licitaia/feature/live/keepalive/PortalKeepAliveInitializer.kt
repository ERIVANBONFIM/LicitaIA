package com.licitaia.feature.live.keepalive

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.Initializer

/**
 * Liga o [PortalKeepAliveController] na primeira vez que o app vai ao primeiro plano (após o
 * Application.onCreate, quando o grafo Hilt já existe). Não inicia serviço nenhum por si: o controlador
 * só age se houver portal com "Manter sessão ativa" ligado e sessão CONECTADA.
 */
class PortalKeepAliveInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        val app = context.applicationContext
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                runCatching { PortalKeepAliveService.controller(app).start() }
            }
        })
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(ProcessLifecycleInitializer::class.java)
}
