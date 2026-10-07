package com.licitaia.feature.live.automation

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.PortalRepository
import com.licitaia.feature.live.web.PortalWebViewHolder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Minhas licitações" a cada abertura do app (ON_START do processo): só com a sessão do Comprasnet marcada como
 * CONECTADA, no máximo a cada [MIN_INTERVAL_MS], com o portal FORA da tela e sem robô rodando. A leitura é a da lista
 * "Compras eletrônicas" → "Minhas participações" na aba retida (estacionada atrás do app), igual ao botão "Buscar".
 */
@Singleton
class MyTendersAutoRefresh @Inject constructor(
    private val sync: MyTendersSync,
    private val auth: AuthRepository,
    private val portals: PortalRepository,
    private val holder: PortalWebViewHolder,
    private val engine: PortalRobotEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun onAppForeground() {
        scope.launch {
            val session = auth.session.value ?: return@launch
            if (session.user.demo) return@launch
            val companyId = session.activeCompany.id
            if (System.currentTimeMillis() - sync.lastRunAt < MIN_INTERVAL_MS) return@launch
            val status = withTimeoutOrNull(5_000) { portals.observeSessions(companyId).first() }
                ?.firstOrNull { it.portal == Portal.COMPRAS_GOV }?.status
            if (status != PortalConnectionStatus.CONECTADO) return@launch
            // A janela da Activity precisa estar visível (o WebView só carrega anexado a ela).
            delay(START_DELAY_MS)
            if (engine.runs.value.values.any { it.companyId == companyId && it.active }) return@launch
            val visible = withContext(Dispatchers.Main) { holder.peek(companyId, Portal.COMPRAS_GOV)?.visible == true }
            if (visible) return@launch
            runCatching { sync.refreshMyTenders(companyId, includeElectronic = true) }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access {
        fun myTendersAutoRefresh(): MyTendersAutoRefresh
    }

    private companion object {
        const val MIN_INTERVAL_MS = 30 * 60_000L
        const val START_DELAY_MS = 6_000L
    }
}
