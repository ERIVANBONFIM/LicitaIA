package com.licitaia.connector.comprasgov

import com.licitaia.connector.api.ListingRowStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Disponibiliza o conector real do Compras.gov.br reutilizando o OkHttpClient (HTTPS-only) e o Json de core-network
 * (ignoreUnknownKeys) e o cache persistente de linhas ([ListingRowStore], Room em core-data).
 */
@Module
@InstallIn(SingletonComponent::class)
object ComprasGovModule {

    @Provides
    @Singleton
    fun provideComprasGovConnector(client: OkHttpClient, json: Json, store: ListingRowStore): ComprasGovConnector =
        ComprasGovConnector(client, json, store = store)
}
