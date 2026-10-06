package com.licitaia.domain.network

import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/**
 * Estado global da conexão com a internet (implementação Android em `core-network`, via NetworkCallback
 * considerando NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED).
 *
 * Telas e repositórios que dependem de rede consultam [isOnline] para FALHAR RÁPIDO quando offline, em vez de
 * esperar o timeout do OkHttp.
 */
interface ConnectivityMonitor {
    /** true quando há rede validada (com acesso real à internet). */
    val online: StateFlow<Boolean>

    val isOnline: Boolean get() = online.value

    /**
     * Existe ALGUMA rede com capacidade de internet (mesmo ainda não validada). Usado para falhar rápido nas
     * chamadas HTTP: só bloqueia quando com certeza não há rede, para não travar o app em redes que o Android
     * nunca valida (algumas VPNs/redes corporativas).
     */
    val hasNetwork: Boolean get() = isOnline
}

/** Falha imediata por falta de internet (lançada antes de abrir qualquer conexão). */
class OfflineException(message: String = OFFLINE_MESSAGE) : IOException(message) {
    companion object {
        const val OFFLINE_MESSAGE = "Sem internet — reconecte para continuar."
    }
}
