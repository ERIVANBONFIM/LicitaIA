package com.licitaia.core.data.backup

import com.licitaia.core.security.AuditHashChain
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditEvent
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditRestoreChainTest {
    private fun event(id: Long, details: String = "evento $id", prevHash: String = "", hash: String = "") = AuditEvent(
        id = id, timestamp = 1_700_000_000_000L + id, user = "Ana", companyId = 1L, companyName = "ACME",
        action = AuditAction.LOGIN, origin = AuditOrigin.USUARIO, result = AuditResult.SUCESSO, details = details,
        prevHash = prevHash, hash = hash,
    )

    private fun localChain(vararg events: AuditEvent): List<AuditEvent> {
        var prev: String? = null
        return events.map { e -> AuditHashChain.chain(prev, e).also { prev = it.hash } }
    }

    @Test fun restoredEventsContinueTheLocalChainAndVerifyEndToEnd() {
        val local = localChain(event(1), event(2))
        // Hashes "de origem" (de outro aparelho) são descartados no re-encadeamento.
        val imported = listOf(event(10, prevHash = "deadbeef", hash = "cafebabe"), event(11, prevHash = "cafebabe", hash = "feedface"))

        val rechained = AuditRestoreChain.rechain(local.last().hash, imported)

        assertEquals(local.last().hash, rechained.first().prevHash)
        assertEquals(rechained.first().hash, rechained.last().prevHash)
        assertNotEquals("cafebabe", rechained.first().hash)
        val report = AuditHashChain.verify(local + rechained.map { it.copy(id = 100 + it.id) })
        assertNull(report.firstBroken)
        assertEquals(4, report.verified)
    }

    @Test fun withoutRechainingImportedEventsBreakVerification() {
        val local = localChain(event(1), event(2))
        val imported = localChain(event(10), event(11)) // cadeia válida, mas de outro aparelho
        val report = AuditHashChain.verify(local + imported)
        assertEquals(imported.first().id, report.firstBroken)
    }

    @Test fun emptyOrLegacyHeadStartsFromGenesis() {
        val fromNull = AuditRestoreChain.rechain(null, listOf(event(1)))
        val fromEmpty = AuditRestoreChain.rechain("", listOf(event(1)))
        assertEquals(AuditHashChain.GENESIS, fromNull.single().prevHash)
        assertEquals(fromNull.single().hash, fromEmpty.single().hash)
        assertEquals(AuditHashChain.hashOf("", event(1)), fromNull.single().hash)
    }

    @Test fun preservesOrderAndContentOfEvents() {
        val imported = listOf(event(3, "c"), event(1, "a"), event(2, "b"))
        val rechained = AuditRestoreChain.rechain("abc", imported)
        assertEquals(listOf("c", "a", "b"), rechained.map { it.details })
        assertTrue(rechained.zip(imported).all { (r, i) -> r.copy(prevHash = "", hash = "") == i.copy(prevHash = "", hash = "") })
        assertEquals("abc", rechained.first().prevHash)
    }

    @Test fun emptyImportIsNoOp() {
        assertTrue(AuditRestoreChain.rechain("abc", emptyList()).isEmpty())
    }
}
