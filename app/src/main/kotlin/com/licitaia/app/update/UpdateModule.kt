package com.licitaia.app.update

import com.licitaia.domain.update.AppUpdateChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    /** Permite que feature-settings dispare a verificação manual sem depender do módulo app. */
    @Binds
    abstract fun bindAppUpdateChecker(impl: UpdateChecker): AppUpdateChecker
}
