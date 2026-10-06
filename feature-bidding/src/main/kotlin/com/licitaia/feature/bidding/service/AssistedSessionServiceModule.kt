package com.licitaia.feature.bidding.service

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistedSessionServiceModule {
    @Binds
    @Singleton
    abstract fun bindKeepAlive(impl: AssistedSessionServiceController): AssistedSessionKeepAlive
}
