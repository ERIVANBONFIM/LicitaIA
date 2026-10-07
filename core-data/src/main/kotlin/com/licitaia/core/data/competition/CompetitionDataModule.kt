package com.licitaia.core.data.competition

import com.licitaia.domain.bidding.BidStrategyConfigRepository
import com.licitaia.domain.competition.CompetitionResultsSync
import com.licitaia.domain.competition.TrackedTenderProvider
import com.licitaia.domain.portal.PortalRobotRepository
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Concorrência operacional (resultados públicos) e configuração padrão do robô de lances. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CompetitionDataModule {
    @Binds abstract fun competitionResultsSync(impl: CompetitionResultsSyncImpl): CompetitionResultsSync
    @Binds abstract fun bidStrategyConfigRepository(impl: BidStrategyConfigRepositoryImpl): BidStrategyConfigRepository

    /** Ponto de extensão: outros módulos acrescentam fontes de licitações com `@Binds @IntoSet`. */
    @Binds @IntoSet abstract fun savedTendersProvider(impl: SavedTendersProvider): TrackedTenderProvider
    @Binds @IntoSet abstract fun portalMyTendersProvider(impl: PortalMyTendersProvider): TrackedTenderProvider

    /** O repositório de "minhas licitações" é opcional (vinculado pelo módulo do robô de portais quando existir). */
    @BindsOptionalOf abstract fun optionalPortalRobotRepository(): PortalRobotRepository
}
