package com.licitaia.feature.bidding.service

/**
 * Mantém o processo vivo enquanto houver pregões assistidos em acompanhamento ATIVO
 * (cronômetro rodando ou alerta ativo). Implementado por [AssistedSessionServiceController]
 * (Foreground Service); nos testes JVM usa-se [None].
 */
interface AssistedSessionKeepAlive {
    /**
     * Informa quantas sessões estão em acompanhamento ativo. 0 = parar o serviço e remover a
     * notificação; > 0 = iniciar/atualizar. Nunca lança exceção.
     */
    fun update(activeSessions: Int)

    /** Para imediatamente (logout, troca de empresa, "pausar alertas de todas"). */
    fun stop() = update(0)

    object None : AssistedSessionKeepAlive {
        override fun update(activeSessions: Int) = Unit
    }
}
