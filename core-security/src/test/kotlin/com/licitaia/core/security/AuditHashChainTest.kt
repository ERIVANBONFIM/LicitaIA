package com.licitaia.core.security

import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditEvent
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditHashChainTest {
    private fun event(id: Long, details: String = "evento $id", companyId: Long? = 1L) = AuditEvent(
        id = id, timestamp = 1_700_000_000_000L + id, user = "Ana", companyId = companyId, companyName = "ACME",
        action = AuditAction.LOGIN, origin = AuditOrigin.USUARIO, result = AuditResult.SUCESSO, details = details,
    )

    private fun chainOf(vararg events: AuditEvent): List<AuditEvent> {
        var prev: String? = null
        return events.map { e -> AuditHashChain.chain(prev, e).also { prev = it.hash } }
    }

    @Test fun hashIsDeterministicHexSha256() {
        val h1 = AuditHashChain.hashOf("", event(1))
        val h2 = AuditHashChain.hashOf("", event(1))
        assertEquals(h1, h2)
        assertEquals(64, h1.length)
        assertTrue(h1.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun hashIgnoresIdButCoversEssentialFieldsAndPrev() {
        val base = event(1)
        assertEquals(AuditHashChain.hashOf("", base), AuditHashChain.hashOf("", base.copy(id = 99)))
        assertNotEquals(AuditHashChain.hashOf("", base), AuditHashChain.hashOf("abc", base))
        assertNotEquals(AuditHashChain.hashOf("", base), AuditHashChain.hashOf("", base.copy(details = "x")))
        assertNotEquals(AuditHashChain.hashOf("", base), AuditHashChain.hashOf("", base.copy(result = AuditResult.FALHA)))
        assertNotEquals(AuditHashChain.hashOf("", base), AuditHashChain.hashOf("", base.copy(timestamp = base.timestamp + 1)))
    }

    @Test fun nullAndEmptyAreDistinguished() {
        val a = event(1).copy(reason = null)
        val b = event(1).copy(reason = "")
        assertNotEquals(AuditHashChain.hashOf("", a), AuditHashChain.hashOf("", b))
        // Deslocar conteúdo entre campos adjacentes também muda o hash (codificação com tamanho).
        val c = event(1).copy(previousValue = "ab", newValue = "c")
        val d = event(1).copy(previousValue = "a", newValue = "bc")
        assertNotEquals(AuditHashChain.hashOf("", c), AuditHashChain.hashOf("", d))
    }

    @Test fun intactChainVerifies() {
        val chain = chainOf(event(1), event(2), event(3))
        val report = AuditHashChain.verify(chain)
        assertNull(report.firstBroken)
        assertEquals(3, report.total)
        assertEquals(3, report.verified)
        assertEquals(0, report.unhashed)
        assertTrue(report.ok)
        assertEquals("", chain[0].prevHash)
        assertEquals(chain[0].hash, chain[1].prevHash)
    }

    @Test fun tamperedFieldBreaksChainAtThatEvent() {
        val chain = chainOf(event(1), event(2), event(3), event(4))
        val tampered = chain.toMutableList().also { it[2] = it[2].copy(details = "alterado") }
        val report = AuditHashChain.verify(tampered)
        assertEquals(3L, report.firstBroken)
        assertEquals(2, report.verified)
        assertEquals(4, report.total)
    }

    @Test fun deletedMiddleEventBreaksLink() {
        val chain = chainOf(event(1), event(2), event(3))
        val report = AuditHashChain.verify(listOf(chain[0], chain[2]))
        assertEquals(3L, report.firstBroken)
        assertEquals(1, report.verified)
    }

    @Test fun legacyEventsWithoutHashAreSkippedUntilFirstHashed() {
        val legacy = listOf(event(1), event(2)) // hash == ""
        val chained = chainOf(event(3), event(4))
        val report = AuditHashChain.verify(legacy + chained)
        assertNull(report.firstBroken)
        assertEquals(2, report.unhashed)
        assertEquals(2, report.verified)
        assertEquals(4, report.total)
    }

    @Test fun unhashedEventAfterChainStartIsABreak() {
        val chained = chainOf(event(1), event(2))
        val report = AuditHashChain.verify(chained + event(3))
        assertEquals(3L, report.firstBroken)
    }
}
