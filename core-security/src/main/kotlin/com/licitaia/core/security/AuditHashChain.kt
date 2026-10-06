package com.licitaia.core.security

import com.licitaia.domain.model.AuditEvent
import com.licitaia.domain.model.IntegrityReport
import java.security.MessageDigest

/**
 * Encadeamento de hashes da trilha de auditoria (função pura, sem I/O).
 *
 * `hash = SHA-256(prevHash || campos essenciais)`, em hex minúsculo. O `id` (autogerado) não entra no hash;
 * a ordem é dada pelo encadeamento. Campos nulos e vazios são distinguidos por codificação com tamanho.
 * Eventos anteriores ao encadeamento têm `hash == ""` e não são verificáveis; a verificação começa no
 * primeiro evento com hash.
 */
object AuditHashChain {
    const val GENESIS = ""

    fun hashOf(prevHash: String, event: AuditEvent): String {
        val canonical = buildString {
            field(prevHash)
            field(event.timestamp.toString())
            field(event.user)
            field(event.companyId?.toString())
            field(event.companyName)
            field(event.portal)
            field(event.tenderNumber)
            field(event.item)
            field(event.action.name)
            field(event.previousValue)
            field(event.newValue)
            field(event.reason)
            field(event.origin.name)
            field(event.result.name)
            field(event.details)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Devolve o evento com `prevHash`/`hash` preenchidos a partir do hash do evento anterior. */
    fun chain(previousHash: String?, event: AuditEvent): AuditEvent {
        val prev = previousHash ?: GENESIS
        return event.copy(prevHash = prev, hash = hashOf(prev, event))
    }

    /**
     * Verifica [events] em ordem de inserção (id crescente). Eventos sem hash antes do primeiro encadeado são
     * apenas contados; a partir do primeiro com hash, cada evento deve (a) ter hash recalculável e (b) apontar
     * para o hash do evento anterior. O primeiro que falhar interrompe a verificação.
     */
    fun verify(events: List<AuditEvent>): IntegrityReport {
        var unhashed = 0
        var verified = 0
        var previous: AuditEvent? = null
        for (event in events) {
            if (previous == null && event.hash.isEmpty()) { unhashed++; continue }
            val linkOk = previous == null || event.prevHash == previous.hash
            val selfOk = event.hash.isNotEmpty() && event.hash == hashOf(event.prevHash, event)
            if (!linkOk || !selfOk) {
                return IntegrityReport(total = events.size, verified = verified, firstBroken = event.id, unhashed = unhashed)
            }
            verified++
            previous = event
        }
        return IntegrityReport(total = events.size, verified = verified, firstBroken = null, unhashed = unhashed)
    }

    /** Codificação não ambígua: `<tamanho>:<valor>|`, com `-1:` para null. */
    private fun StringBuilder.field(value: String?) {
        if (value == null) append("-1:|") else append(value.length).append(':').append(value).append('|')
    }
}
