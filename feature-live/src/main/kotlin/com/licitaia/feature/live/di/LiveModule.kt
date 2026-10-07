package com.licitaia.feature.live.di

import com.licitaia.domain.portal.MyTendersRefresher
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.feature.live.automation.MyTendersSync
import com.licitaia.feature.live.notify.AppNotifierImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LiveModule {
    @Binds
    @Singleton
    abstract fun bindAppNotifier(impl: AppNotifierImpl): AppNotifier

    /** "Buscar minhas licitações" do Comprasnet (sessão logada): usado pelas telas e pela sincronização diária. */
    @Binds
    @Singleton
    abstract fun bindMyTendersRefresher(impl: MyTendersSync): MyTendersRefresher
}
