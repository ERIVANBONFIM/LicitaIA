package com.licitaia.core.platform.di

import android.content.Context
import androidx.room.Room
import com.licitaia.core.platform.db.PlatformDatabase
import com.licitaia.core.platform.db.PlatformMutationDao
import com.licitaia.core.platform.db.PlatformTenderDao
import com.licitaia.core.platform.net.PlatformApi
import com.licitaia.core.platform.queue.OfflineMutationQueue
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Disponibiliza a camada de plataforma reusando o [OkHttpClient] HTTPS-only e o [Json] de core-network.
 * O banco é SEPARADO do banco do app local (ver [PlatformDatabase]).
 */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides
    @Singleton
    fun providePlatformApi(client: OkHttpClient, json: Json): PlatformApi = PlatformApi(client, json)

    @Provides
    @Singleton
    fun providePlatformDatabase(@ApplicationContext context: Context): PlatformDatabase =
        Room.databaseBuilder(context, PlatformDatabase::class.java, PlatformDatabase.NAME)
            .fallbackToDestructiveMigration() // espelho de leitura descartável: recriar é seguro
            .build()

    @Provides
    @Singleton
    fun provideTenderDao(db: PlatformDatabase): PlatformTenderDao = db.tenderDao()

    @Provides
    @Singleton
    fun provideMutationDao(db: PlatformDatabase): PlatformMutationDao = db.mutationDao()

    @Provides
    @Singleton
    fun provideOfflineMutationQueue(dao: PlatformMutationDao): OfflineMutationQueue = OfflineMutationQueue(dao)
}
