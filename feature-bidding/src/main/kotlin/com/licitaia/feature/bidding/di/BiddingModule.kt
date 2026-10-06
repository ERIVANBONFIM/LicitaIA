package com.licitaia.feature.bidding.di

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.PortalConnector
import com.licitaia.connector.comprasgov.ComprasGovConnector
import com.licitaia.connector.mock.MockConnectorRegistry
import com.licitaia.connector.pncp.PncpConnector
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.Portal
import com.licitaia.feature.bidding.engine.LiveSessionManagerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BiddingModule {

    @Binds
    @Singleton
    abstract fun bindLiveSessionManager(impl: LiveSessionManagerImpl): LiveSessionManager

    companion object {
        /**
         * PNCP e Compras.gov.br: conectores REAIS (consulta pública documentada, sem login). Demais portais:
         * sem API oficial validada, continuam atrás de conectores MOCK (isMock = true), que os repositórios de busca ignoram.
         * A BUSCA de Licitanet, BLL e Portal de Compras Públicas é feita pelo PNCP (searchablePortals), que classifica a
         * plataforma de origem de cada contratação; o registro continua devolvendo o mock para ações de portal.
         */
        @Provides
        @Singleton
        fun provideConnectorRegistry(pncp: PncpConnector, comprasGov: ComprasGovConnector): ConnectorRegistry =
            RealFirstConnectorRegistry(real = listOf(pncp, comprasGov), fallback = MockConnectorRegistry())
    }
}

/** Devolve o conector real quando existe para o portal; caso contrário, delega ao registro de fallback. */
internal class RealFirstConnectorRegistry(
    real: List<PortalConnector>,
    private val fallback: ConnectorRegistry,
) : ConnectorRegistry {

    private val realByPortal: Map<Portal, PortalConnector> = real.associateBy { it.portal }

    override fun get(portal: Portal): PortalConnector = realByPortal[portal] ?: fallback.get(portal)

    override fun all(): List<PortalConnector> =
        realByPortal.values.toList() + fallback.all().filter { it.portal !in realByPortal }
}
