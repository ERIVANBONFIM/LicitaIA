package com.licitaia.connector.pncp

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Disponibiliza o conector real do PNCP reutilizando o OkHttpClient (HTTPS-only) e o Json de core-network. */
@Module
@InstallIn(SingletonComponent::class)
object PncpModule {

    @Provides
    @Singleton
    fun providePncpConnector(client: OkHttpClient, json: Json): PncpConnector = PncpConnector(client, json)
}
