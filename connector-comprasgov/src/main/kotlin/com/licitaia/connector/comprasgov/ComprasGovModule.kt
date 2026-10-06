package com.licitaia.connector.comprasgov

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Disponibiliza o conector real do Compras.gov.br reutilizando o OkHttpClient (HTTPS-only) e o Json de core-network. */
@Module
@InstallIn(SingletonComponent::class)
object ComprasGovModule {

    @Provides
    @Singleton
    fun provideComprasGovConnector(client: OkHttpClient, json: Json): ComprasGovConnector = ComprasGovConnector(client, json)
}
