package com.licitaia.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {

    @Test
    fun `lista vazia explica a causa real e nao um generico nenhum passou nos filtros`() {
        // Caso do aparelho: 3 candidatas, todas dispensas sem disputa ocultas.
        val hidden = SearchOutcome(
            items = emptyList(), sourceCounts = mapOf(Portal.PNCP to 31, Portal.COMPRAS_GOV to 3),
            sourceDiagnostics = mapOf(Portal.COMPRAS_GOV to SourceDiagnostics(316, 3, 3, unknownDeadline = 3)), hiddenNoDispute = 3,
        )
        assertEquals("as que casaram estão ocultas: 3 dispensas sem disputa", hidden.emptyReason)

        val syncing = hidden.copy(sourceDiagnostics = mapOf(Portal.COMPRAS_GOV to SourceDiagnostics(316, 3, 3, syncing = true)))
        assertEquals(true, syncing.syncing)
        assertEquals(true, syncing.emptyReason!!.startsWith("sincronização do Compras.gov.br em andamento"))
        assertEquals(true, syncing.sourceSummary!!.contains("316 lidas · 3 candidatas · 3 abertas · sincronização em andamento (parcial)"))

        val failed = hidden.copy(sourceDiagnostics = mapOf(Portal.COMPRAS_GOV to SourceDiagnostics(316, 3, 3, syncFailure = "HTTP 429")))
        assertEquals(true, failed.emptyReason!!.contains("leitura completa do Compras.gov.br não terminou (HTTP 429)"))

        val none = SearchOutcome(
            items = emptyList(), sourceCounts = mapOf(Portal.PNCP to 0, Portal.COMPRAS_GOV to 0),
            sourceDiagnostics = mapOf(Portal.COMPRAS_GOV to SourceDiagnostics(7153, 0, 0, truncated = true)),
        )
        assertEquals("nenhuma licitação aberta das fontes casou com as palavras/filtros", none.emptyReason)

        val belowScore = none.copy(sourceDiagnostics = mapOf(Portal.COMPRAS_GOV to SourceDiagnostics(7153, 33, 33, truncated = true)))
        assertEquals("nenhuma atingiu o score mínimo ou os demais filtros", belowScore.emptyReason)
        assertNull(belowScore.copy(items = listOf(ScoredOpportunity(sample(), 80, false))).emptyReason)
    }

    private fun sample() = Opportunity(
        id = "PNCP:x", portal = Portal.PNCP, number = "1", agency = "a", objectDescription = "o", modality = Modality.PREGAO_ELETRONICO,
        segment = Segment.TI, uf = "MG", city = "BH", estimatedValue = 0.0, publishedAt = 0L, proposalDeadline = 1L, sessionAt = 1L,
    )

    private val now = 1_700_000_000_000L
    private val day = CompanyDocument.DAY_MS

    private fun doc(
        issuedAt: Long? = now - 30 * day,
        expiresAt: Long? = null,
        attachmentUri: String? = null,
    ) = CompanyDocument(companyId = 1, type = DocumentType.CNPJ, title = "CNPJ", issuedAt = issuedAt, expiresAt = expiresAt, attachmentUri = attachmentUri)

    // ---------------------------------------------------------------- CompanyDocument.status

    @Test
    fun `documento sem anexo nem emissao esta ausente`() {
        assertEquals(DocumentStatus.AUSENTE, doc(issuedAt = null, attachmentUri = null).status(now))
        assertEquals(DocumentStatus.AUSENTE, doc(issuedAt = null, attachmentUri = null, expiresAt = now - day).status(now))
    }

    @Test
    fun `documento com anexo mas sem emissao nao e ausente`() {
        assertEquals(DocumentStatus.VALIDO, doc(issuedAt = null, attachmentUri = "content://x").status(now))
    }

    @Test
    fun `documento sem validade e valido`() {
        assertEquals(DocumentStatus.VALIDO, doc(expiresAt = null).status(now))
        assertNull(doc(expiresAt = null).daysToExpire(now))
    }

    @Test
    fun `documento vencido`() {
        assertEquals(DocumentStatus.VENCIDO, doc(expiresAt = now - 1).status(now))
        assertEquals(DocumentStatus.VENCIDO, doc(expiresAt = now - 10 * day).status(now))
    }

    @Test
    fun `documento vence em breve dentro da janela`() {
        assertEquals(DocumentStatus.VENCE_EM_BREVE, doc(expiresAt = now).status(now))
        assertEquals(DocumentStatus.VENCE_EM_BREVE, doc(expiresAt = now + 30 * day).status(now))
        assertEquals(DocumentStatus.VALIDO, doc(expiresAt = now + 30 * day + 1).status(now))
        assertEquals(DocumentStatus.VENCE_EM_BREVE, doc(expiresAt = now + 5 * day).status(now, soonDays = 7))
        assertEquals(DocumentStatus.VALIDO, doc(expiresAt = now + 5 * day).status(now, soonDays = 3))
    }

    @Test
    fun `daysToExpire conta dias inteiros e fica negativo quando vencido`() {
        assertEquals(10L, doc(expiresAt = now + 10 * day).daysToExpire(now))
        assertEquals(0L, doc(expiresAt = now + day - 1).daysToExpire(now))
        assertEquals(-2L, doc(expiresAt = now - 2 * day).daysToExpire(now))
    }

    // ---------------------------------------------------------------- BidRule.marginPct

    private val rule = BidRule(
        initialPrice = 100_000.0, floorPrice = 80_000.0, costPrice = 70_000.0,
        reductionValue = 500.0, minMarginPct = 5.0,
    )

    @Test
    fun `marginPct e a margem sobre o preco de venda`() {
        assertEquals(30.0, rule.marginPct(100_000.0), 1e-9)
        assertEquals(12.5, rule.marginPct(80_000.0), 1e-9)
        assertEquals(0.0, rule.marginPct(70_000.0), 1e-9)
    }

    @Test
    fun `marginPct negativa abaixo do custo`() {
        assertEquals(-16.666666666, rule.marginPct(60_000.0), 1e-6)
    }

    @Test
    fun `marginPct devolve zero para preco invalido`() {
        assertEquals(0.0, rule.marginPct(0.0), 0.0)
        assertEquals(0.0, rule.marginPct(-1.0), 0.0)
    }

    @Test
    fun `regra e sempre simulacao por padrao`() {
        assertEquals(true, rule.simulation)
    }
}
