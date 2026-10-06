package com.licitaia.core.data.backup

import com.licitaia.core.security.AuditHashChain
import com.licitaia.domain.model.AuditEvent

/**
 * Re-encadeamento dos eventos de auditoria restaurados de backup.
 *
 * Os hashes gravados no backup pertencem à cadeia do aparelho de origem; reinseridos aqui fora de ordem,
 * quebrariam `verifyIntegrity`. Por isso cada evento importado é re-encadeado a partir da cabeça atual da cadeia
 * local (`prevHash`/`hash` recalculados com [AuditHashChain], na ordem original de inserção), de modo que a trilha
 * local continua íntegra do primeiro ao último evento. Função pura, sem I/O.
 */
object AuditRestoreChain {
    /**
     * @param headHash hash do último evento local antes da restauração (`null`/"" se a trilha está vazia ou só tem
     *   eventos legados sem hash).
     * @param events eventos do backup, na ordem em que foram gravados na origem (id original crescente).
     */
    fun rechain(headHash: String?, events: List<AuditEvent>): List<AuditEvent> {
        var previous = headHash ?: AuditHashChain.GENESIS
        return events.map { event ->
            AuditHashChain.chain(previous, event).also { previous = it.hash }
        }
    }
}
