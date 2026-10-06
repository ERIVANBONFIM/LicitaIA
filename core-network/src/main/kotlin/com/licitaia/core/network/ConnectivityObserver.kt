package com.licitaia.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.licitaia.domain.network.ConnectivityMonitor
import com.licitaia.domain.network.OfflineException
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observa a conectividade do aparelho com um NetworkCallback registrado durante toda a vida do processo.
 *
 * "Online" = existe ao menos uma rede com NET_CAPABILITY_INTERNET **e** NET_CAPABILITY_VALIDATED
 * (o Android confirmou acesso real à internet; Wi‑Fi sem internet/portal cativo não conta).
 * [hasNetwork] = existe alguma rede com NET_CAPABILITY_INTERNET (validada ou não).
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext context: Context,
) : ConnectivityMonitor {

    private val manager = context.getSystemService(ConnectivityManager::class.java)
    /** Redes com INTERNET conhecidas pelo callback → validada? */
    private val networks = ConcurrentHashMap<Network, Boolean>()
    private val _online = MutableStateFlow(activeValidated() ?: true)
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    override val hasNetwork: Boolean
        get() = networks.isNotEmpty() || _online.value || runCatching { manager?.activeNetwork != null }.getOrDefault(true)

    init {
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching {
            manager?.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        networks.putIfAbsent(network, false)
                        publish()
                    }

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        networks[network] = isValidated(caps)
                        publish()
                    }

                    override fun onLost(network: Network) {
                        networks.remove(network)
                        publish()
                    }

                    override fun onUnavailable() {
                        networks.clear()
                        publish()
                    }
                },
            )
        }
    }

    private fun publish() {
        _online.value = networks.values.any { it } || activeValidated() == true
    }

    /** null = não foi possível consultar (sem serviço): não bloqueia o app. */
    private fun activeValidated(): Boolean? = runCatching {
        val m = manager ?: return null
        val caps = m.getNetworkCapabilities(m.activeNetwork ?: return false) ?: return false
        isValidated(caps)
    }.getOrNull()

    private fun isValidated(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Falha na hora (sem esperar timeout) quando com certeza não há rede. Instalado no OkHttpClient compartilhado,
 * cobre busca (PNCP/Compras.gov.br), IA, verificação de atualização e logins OAuth que usam esse cliente.
 */
class OfflineFailFastInterceptor(private val connectivity: ConnectivityMonitor) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!connectivity.hasNetwork) throw OfflineException()
        return chain.proceed(chain.request())
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectivityModule {
    @Binds
    @Singleton
    abstract fun bindConnectivityMonitor(impl: ConnectivityObserver): ConnectivityMonitor
}
