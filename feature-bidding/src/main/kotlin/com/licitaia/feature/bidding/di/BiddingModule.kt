package com.licitaia.feature.bidding.di

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.PortalConnector
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
         * PNCP: conector REAL (consulta pública documentada). Demais portais: sem API oficial validada,
         * continuam atrás de conectores MOCK (isMock = true), que os repositórios de busca ignoram.
         */
        @Provides
        @Singleton
        fun provideConnectorRegistry(pncp: PncpConnector): ConnectorRegistry =
            RealFirstConnectorRegistry(real = listOf(pncp), fallback = MockConnectorRegistry())
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
