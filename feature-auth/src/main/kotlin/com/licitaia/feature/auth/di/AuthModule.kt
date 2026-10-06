package com.licitaia.feature.auth.di

import com.licitaia.domain.auth.IdentitySignOut
import com.licitaia.feature.auth.google.GoogleCredentialClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds
    abstract fun bindIdentitySignOut(impl: GoogleCredentialClient): IdentitySignOut
}
