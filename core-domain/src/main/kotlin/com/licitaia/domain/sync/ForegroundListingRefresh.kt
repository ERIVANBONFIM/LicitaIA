package com.licitaia.domain.sync

import java.util.concurrent.atomic.AtomicInteger

/**
 * Telas de lista (busca/resultados de radar) com atualização automática ativa em primeiro plano. Enquanto houver
 * alguma, o Worker de alertas em segundo plano não roda (a tela já mantém o cache das fontes atualizado), evitando
 * duas sincronizações concorrentes. Mesmo processo: contador em memória.
 */
object ForegroundListingRefresh {
    private val active = AtomicInteger(0)

    val isActive: Boolean get() = active.get() > 0

    fun enter() {
        active.incrementAndGet()
    }

    fun exit() {
        active.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** Marca a tela como atualizando enquanto [block] executa (cancelamento/saída da tela libera). */
    suspend fun <T> track(block: suspend () -> T): T {
        enter()
        try {
            return block()
        } finally {
            exit()
        }
    }
}
