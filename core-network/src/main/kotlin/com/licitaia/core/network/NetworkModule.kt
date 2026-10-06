package com.licitaia.core.network

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.licitaia.domain.network.ConnectivityMonitor
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Cabeçalhos que carregam credenciais e nunca podem aparecer em log. */
    val SENSITIVE_HEADERS = listOf("Authorization", "x-api-key", "x-goog-api-key", "Cookie", "Set-Cookie")

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context, connectivity: ConnectivityMonitor): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // Sem rede: falha imediatamente com OfflineException ("Sem internet…") em vez de esperar o timeout.
            .addInterceptor(OfflineFailFastInterceptor(connectivity))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            // Somente HTTPS: sem ConnectionSpec.CLEARTEXT qualquer chamada http:// falha.
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .retryOnConnectionFailure(true)

        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            // BASIC = método/URL/status. Corpos (prompts, dados da empresa) nunca são logados.
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
                SENSITIVE_HEADERS.forEach { redactHeader(it) }
            }
            builder.addInterceptor(logging)
        }
        return builder.build()
    }
}
