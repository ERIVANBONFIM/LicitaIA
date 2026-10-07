package com.licitaia.connector.comprasgov

import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Mapeamento JSON REAL do Compras.gov.br (fixtures capturadas em 06/10/2026) → [com.licitaia.domain.model.Opportunity]. */
class ComprasGovMapperTest {

    private val json: Json = ComprasGovConnector.defaultJson()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/comprasgov/$name")) { "fixture $name ausente" }.bufferedReader().readText()

    private fun saoPaulo(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()

    @Test
    fun `numero de controle PNCP gera id COMPRAS_GOV com o mesmo sufixo do conector PNCP`() {
        val ref = ComprasGovPncpRef.parse("05055128000176-1-000108/2026")
        assertNotNull(ref)
        assertEquals("05055128000176", ref!!.cnpj)
        assertEquals(2026, ref.ano)
        assertEquals(108, ref.sequencial)
        assertEquals("05055128000176-1-000108/2026", ref.raw)
        assertEquals("COMPRAS_GOV:05055128000176-1-000108/2026", ref.opportunityId)
        // mesmo sufixo que "PNCP:05055128000176-1-000108/2026" → chave de deduplicação no repositório
        assertEquals("PNCP:${ref.raw}".substringAfter(':'), ref.opportunityId.substringAfter(':'))
        assertEquals("https://pncp.gov.br/app/editais/05055128000176/2026/108", ref.publicPageUrl)
        assertEquals(ref, ComprasGovPncpRef.fromOpportunityId("COMPRAS_GOV:05055128000176-1-000108/2026"))
        assertNull(ComprasGovPncpRef.parse("152005-05-00027/2023"))
        assertNull(ComprasGovPncpRef.parse(null))
    }

    /** Campos reais de `1_consultarContratacoes_PNCP_14133?codigoModalidade=6` (MG, 20/09–01/10/2026). */
    @Test
    fun `dispensa sem disputa e marcada pelo modo de disputa Nao se aplica`() {
        val semDisputa = """{"resultado":[{"numeroControlePNCP":"18338194000127-1-000123/2026","codigoModalidade":6,
            "modalidadeIdPncp":8,"modalidadeNome":"Dispensa","modoDisputaIdPncp":5,"modoDisputaNomePncp":"Não se aplica",
            "tipoInstrumentoConvocatorioCodigoPncp":3,"tipoInstrumentoConvocatorioNome":"Ato que autoriza a Contratação Direta",
            "objetoCompra":"Aquisição de material","dataPublicacaoPncp":"2026-09-22T10:00:00",
            "dataAberturaPropostaPncp":null,"dataEncerramentoPropostaPncp":null}],"totalRegistros":1}"""
        val comDisputa = """{"resultado":[{"numeroControlePNCP":"18338194000127-1-000124/2026","codigoModalidade":6,
            "modalidadeIdPncp":8,"modoDisputaIdPncp":4,"modoDisputaNomePncp":"Dispensa Com Disputa",
            "tipoInstrumentoConvocatorioCodigoPncp":2,"objetoCompra":"Aquisição de material",
            "dataAberturaPropostaPncp":"2026-09-22T08:00:00","dataEncerramentoPropostaPncp":"2026-09-25T08:00:00"}],"totalRegistros":1}"""
        val a = ComprasGovMapper.toOpportunity(json.decodeFromString(ComprasGovPage.serializer(ComprasGovContratacao.serializer()), semDisputa).resultado.single())!!
        val b = ComprasGovMapper.toOpportunity(json.decodeFromString(ComprasGovPage.serializer(ComprasGovContratacao.serializer()), comDisputa).resultado.single())!!
        assertTrue(a.noDispute)
        assertEquals(false, b.noDispute)
        // Dispensa com disputa ainda sem prazo publicado continua "com disputa" (modo informado).
        val semPrazo = json.decodeFromString(ComprasGovPage.serializer(ComprasGovContratacao.serializer()), comDisputa).resultado.single()
            .copy(dataEncerramentoPropostaPncp = null)
        assertEquals(false, ComprasGovMapper.toOpportunity(semPrazo)!!.noDispute)
        // Sem modo de disputa no payload: dispensa sem encerramento = sem disputa; pregão nunca.
        val semModo = semPrazo.copy(modoDisputaIdPncp = null, modoDisputaNomePncp = null, tipoInstrumentoConvocatorioCodigoPncp = null)
        assertTrue(ComprasGovMapper.toOpportunity(semModo)!!.noDispute)
        assertEquals(false, ComprasGovMapper.toOpportunity(semModo.copy(codigoModalidade = 5, modalidadeIdPncp = 6))!!.noDispute)
    }

    @Test
    fun `referencia legada e reversivel para id_compra`() {
        val ref = ComprasGovLegacyRef.fromIdCompra("15200505000272023")
        assertNotNull(ref)
        assertEquals(152005, ref!!.uasg)
        assertEquals(5, ref.modalidade)
        assertEquals(27, ref.numero)
        assertEquals(2023, ref.ano)
        assertEquals("152005-05-00027/2023", ref.raw)
        assertEquals("15200505000272023", ref.idCompra)
        assertEquals("COMPRAS_GOV:152005-05-00027/2023", ref.opportunityId)
        assertEquals(ref, ComprasGovLegacyRef.fromOpportunityId("COMPRAS_GOV:152005-05-00027/2023"))
        // numero_aviso real "762023" = 76/2023; uasg 90016 é zero-preenchida (id_compra "09001605000762023")
        val fromFields = ComprasGovLegacyRef.fromFields(90016, 5, 762023)
        assertEquals("09001605000762023", fromFields!!.idCompra)
        assertNull(ComprasGovLegacyRef.fromIdCompra("05055128000176-1-000108/2026"))
        assertNull(ComprasGovLegacyRef.fromOpportunityId("COMPRAS_GOV:05055128000176-1-000108/2026"))
    }

    @Test
    fun `codigos de modalidade do Compras gov br confirmados pela API (diferentes do PNCP)`() {
        assertEquals(5, ComprasGovModalities.codeOf(Modality.PREGAO_ELETRONICO))
        assertEquals(6, ComprasGovModalities.codeOf(Modality.DISPENSA_ELETRONICA))
        assertEquals(3, ComprasGovModalities.codeOf(Modality.CONCORRENCIA))
        assertNull("Credenciamento não tem código observado", ComprasGovModalities.codeOf(Modality.CREDENCIAMENTO))
        assertEquals(listOf(5, 6, 3), ComprasGovModalities.SEARCHED_CODES)
        assertNull("Inexigibilidade não é representada", ComprasGovModalities.modalityOf(7))
        assertEquals(Modality.DISPENSA_ELETRONICA, ComprasGovModalities.modalityOfPncp(8))
        assertEquals(5, ComprasGovModalities.legacyCodeOf(Modality.PREGAO_ELETRONICO))
        assertEquals(3, ComprasGovModalities.legacyCodeOf(Modality.CONCORRENCIA))
        assertNull(ComprasGovModalities.legacyCodeOf(Modality.DISPENSA_ELETRONICA))
        assertNull("pregão presencial legado não é representado", ComprasGovModalities.legacyModalityOf(5, "presencial"))
        assertEquals(Modality.PREGAO_ELETRONICO, ComprasGovModalities.legacyModalityOf(5, "eletronico"))
    }

    @Test
    fun `pagina real de contratacoes 14133 e mapeada`() {
        val page = json.decodeFromString(ComprasGovPage.serializer(ComprasGovContratacao.serializer()), fixture("contratacoes_14133_pregao_mg_p40.json"))
        assertEquals(399, page.totalRegistros)
        assertEquals(40, page.totalPaginas)
        assertEquals(0, page.paginasRestantes)
        assertEquals(9, page.resultado.size)

        val mapped = page.resultado.mapNotNull(ComprasGovMapper::toOpportunity)
        assertEquals(9, mapped.size)
        assertTrue(mapped.all { it.portal == Portal.COMPRAS_GOV && it.uf == "MG" && it.modality == Modality.PREGAO_ELETRONICO })
        assertEquals(mapped.size, mapped.map { it.id }.distinct().size)

        val first = mapped.first()
        assertEquals("COMPRAS_GOV:18677591000100-1-000499/2026", first.id)
        assertEquals("7/2026", first.number)
        assertEquals("MUNICIPIO DE EXTREMA — CAMARA MUNICIPAL DE EXTREMA - MG", first.agency)
        assertTrue(first.objectDescription.startsWith("CONTRATAÇÃO EXCLUSIVA DE ME, EPP OU EQUIPARADAS PARA FORNECIMENTO DE CADEIRAS"))
        assertEquals("EXTREMA", first.city)
        assertEquals(34197.82, first.estimatedValue, 0.0001)
        assertEquals(saoPaulo(2026, 9, 29, 15, 10, 20), first.publishedAt)
        assertEquals(saoPaulo(2026, 10, 20, 9, 0, 0), first.proposalDeadline)
        assertEquals(first.proposalDeadline, first.sessionAt)
        assertEquals("https://pncp.gov.br/app/editais/18677591000100/2026/499", first.editalUrl)
        assertTrue(first.keywords.contains("Pregão - Eletrônico"))
        assertTrue(first.keywords.contains("Lei 14.133/2021, Art. 28, I"))
        assertTrue(first.keywords.contains("UASG 929730"))
        assertTrue(first.keywords.contains("18677591000100-1-000499/2026"))
        assertEquals(Segment.PERSONALIZADO, first.segment)

        val backup = mapped.first { it.id == "COMPRAS_GOV:23664303000104-1-000045/2026" }
        assertEquals(Segment.TI, backup.segment) // "appliance para armazenamento de backup" → vocabulário de TI ("backup")
        assertEquals(0.0, backup.estimatedValue, 0.0) // valor não informado → 0, nunca inventado
    }

    @Test
    fun `segmento - almoxarifado virtual nao e telecom e link de internet em fibra e`() {
        assertTrue(
            Segment.TELECOM_ISP != ComprasGovMapper.inferSegment(
                "Contratação de serviços contínuos, terceirizados, de almoxarifado virtual, sob demanda, visando o suprimento de materiais",
            ),
        )
        assertEquals(
            Segment.TELECOM_ISP,
            ComprasGovMapper.inferSegment("Serviço de link para conexões dedicadas de acesso à Internet, por meio de fibra óptica"),
        )
    }

    @Test
    fun `sem data de encerramento o prazo fica nao informado e nunca vira publicacao ou abertura`() {
        val page = json.decodeFromString(ComprasGovPage.serializer(ComprasGovContratacao.serializer()), fixture("contratacoes_14133_pregao_mg_p40.json"))
        val dto = page.resultado.first().copy(
            dataEncerramentoPropostaPncp = null,
            dataAberturaPropostaPncp = "2026-09-16T08:00:00",
            dataPublicacaoPncp = "2026-09-16T07:00:00",
        )
        val opp = ComprasGovMapper.toOpportunity(dto)!!
        assertEquals(com.licitaia.domain.model.Opportunity.DEADLINE_UNKNOWN, opp.proposalDeadline)
        assertTrue(!opp.hasProposalDeadline)
        assertEquals(saoPaulo(2026, 9, 16, 7, 0, 0), opp.publishedAt)
    }

    @Test
    fun `UASG de unidadeOrgaoCodigoUnidade com 6 digitos e situacao oficial nas palavras-chave`() {
        val page = json.decodeFromString(ComprasGovPage.serializer(ComprasGovContratacao.serializer()), fixture("contratacoes_14133_pregao_mg_p40.json"))
        val dto = page.resultado.first().copy(unidadeOrgaoCodigoUnidade = "92731", situacaoCompraNomePncp = "Divulgada no PNCP")
        val opp = ComprasGovMapper.toOpportunity(dto)!!
        assertEquals("092731", opp.uasg)
        assertEquals("UASG 092731", com.licitaia.domain.model.UasgCode.label(opp))
        assertNull(opp.officialSituation)
        val suspensa = ComprasGovMapper.toOpportunity(dto.copy(situacaoCompraNomePncp = "Suspensa"))!!
        assertEquals(com.licitaia.domain.model.OfficialSituation.SUSPENSA, suspensa.officialSituation)
        // O cache de linhas não tem coluna própria: UASG e situação são relidas das palavras-chave.
        assertEquals("092731", com.licitaia.domain.model.UasgCode.fromKeywords(suspensa.keywords))
        assertEquals(com.licitaia.domain.model.OfficialSituation.SUSPENSA, com.licitaia.domain.model.OfficialSituation.fromKeywords(suspensa.keywords))
    }

    @Test
    fun `contratacao excluida ou sem modalidade representada e descartada`() {
        val page = json.decodeFromString(ComprasGovPage.serializer(ComprasGovContratacao.serializer()), fixture("contratacoes_14133_pregao_mg_p40.json"))
        val dto = page.resultado.first()
        assertNull(ComprasGovMapper.toOpportunity(dto.copy(contratacaoExcluida = true)))
        assertNull(ComprasGovMapper.toOpportunity(dto.copy(codigoModalidade = 7, modalidadeIdPncp = 9)))
        // sem código Compras.gov.br mas com código PNCP conhecido → fallback
        assertEquals(Modality.DISPENSA_ELETRONICA, ComprasGovMapper.toOpportunity(dto.copy(codigoModalidade = null, modalidadeIdPncp = 8))!!.modality)
        // sem número de controle mas com idCompra → id legado-like, link para a home oficial
        val semControle = ComprasGovMapper.toOpportunity(dto.copy(numeroControlePNCP = null))
        assertEquals("COMPRAS_GOV:929730-05-00007/2026", semControle!!.id)
        assertEquals(Portal.COMPRAS_GOV.publicUrl, semControle.editalUrl)
        assertNull(ComprasGovMapper.toOpportunity(dto.copy(numeroControlePNCP = null, idCompra = null)))
    }

    @Test
    fun `detalhe real de dispensa 14133 e itens sao mapeados`() {
        val page = json.decodeFromString(ComprasGovPage.serializer(ComprasGovContratacao.serializer()), fixture("contratacao_14133_05055128000176-1-000108_2026.json"))
        val dto = page.resultado.single()
        assertEquals(6, dto.codigoModalidade)
        assertEquals(8, dto.modalidadeIdPncp)
        val opp = ComprasGovMapper.toOpportunity(dto)!!
        assertEquals("COMPRAS_GOV:05055128000176-1-000108/2026", opp.id)
        assertEquals(Modality.DISPENSA_ELETRONICA, opp.modality)
        assertEquals("34/2026", opp.number)
        assertEquals("PB", opp.uf)
        assertEquals("POMBAL", opp.city)
        assertEquals(6351.15, opp.estimatedValue, 0.0001)
        assertEquals(saoPaulo(2026, 9, 4, 8, 0, 0), opp.proposalDeadline)

        val items = json.decodeFromString(ComprasGovPage.serializer(ComprasGovItem.serializer()), fixture("itens_14133_05055128000176-1-000108_2026.json"))
        assertEquals(7, items.totalRegistros)
        val described = items.resultado.map(ComprasGovMapper::describeItem)
        assertEquals(7, described.size)
        assertTrue(described.first(), described.first().startsWith("Item 1 — Tubo Cobre"))
        assertTrue(described.first().contains("90 Metro"))
        assertTrue(described.first().contains("39,40 unit."))
    }

    @Test
    fun `item real traz campos do detalhe - NCM, situacao, categoria, fornecedor e datas`() {
        val items = json.decodeFromString(ComprasGovPage.serializer(ComprasGovItem.serializer()), fixture("itens_14133_05055128000176-1-000108_2026.json"))
        val item = ComprasGovMapper.toOfficialItem(items.resultado.first { it.numeroItemPncp == 3 })!!
        assertEquals("85469000", item.ncmNbsCode)
        assertEquals("Homologado", item.situation)
        assertEquals("Informática (TIC)", item.category)
        assertEquals("Não se aplica", item.benefit)
        assertEquals("Menor preço", item.judgingCriterion)
        assertEquals("TORRAO EQUIPAMENTOS E ACESSORIOS LTDA", item.supplier)
        assertEquals(true, item.hasResult)
        assertEquals("2026-09-01T07:27:03", item.includedAt)
        assertEquals("Não se aplica", item.preferenceMargin)
        assertEquals(false, item.productiveIncentive)
        assertNull(item.catalogCode)
    }

    @Test
    fun `licitacao legada real e mapeada sem UF e com UASG como orgao`() {
        val page = json.decodeFromString(ComprasGovPage.serializer(ComprasGovLicitacaoLegado.serializer()), fixture("legado_licitacao_pregao_2023-11_p1.json"))
        assertEquals(4016, page.totalRegistros)
        assertEquals(10, page.resultado.size)
        assertEquals(4, page.resultado.count { it.pertence14133 == true })

        val mapped = page.resultado.mapNotNull(ComprasGovMapper::toOpportunity)
        assertEquals("registros pertence14133 (duplicatas do módulo 14.133) são descartados", 6, mapped.size)
        assertTrue(mapped.all { it.portal == Portal.COMPRAS_GOV && it.modality == Modality.PREGAO_ELETRONICO && it.uf.isEmpty() && it.city.isEmpty() })

        val ines = mapped.first { it.id == "COMPRAS_GOV:152005-05-00027/2023" }
        assertEquals("27/2023", ines.number)
        assertEquals("UASG 152005", ines.agency)
        assertEquals(1138830.24, ines.estimatedValue, 0.0001)
        assertEquals(saoPaulo(2023, 11, 1, 0, 0, 0), ines.publishedAt)
        assertEquals(saoPaulo(2023, 11, 14, 0, 0, 0), ines.proposalDeadline) // data_abertura_proposta
        assertEquals(ines.proposalDeadline, ines.sessionAt)
        assertEquals(Portal.COMPRAS_GOV.publicUrl, ines.editalUrl)
        assertEquals(Segment.SERVICOS, ines.segment)
        assertTrue(ines.keywords.contains("Lei 8.666 (módulo legado)"))

        val semValor = mapped.first { it.id == "COMPRAS_GOV:120632-05-00076/2023" }
        assertEquals(0.0, semValor.estimatedValue, 0.0)

        val items = json.decodeFromString(ComprasGovPage.serializer(ComprasGovItemLegado.serializer()), fixture("legado_itens_15200505000272023.json"))
        val described = items.resultado.map(ComprasGovMapper::describeItem)
        assertEquals(1, described.size)
        assertTrue(described.first(), described.first().startsWith("Item 1 — Contratação de prestação de serviço"))
        assertTrue(described.first().contains("1 UNIDADE"))
    }

    @Test
    fun `datas sem fuso sao interpretadas como Brasilia e formatos invalidos viram null`() {
        assertEquals(saoPaulo(2026, 9, 1, 7, 26, 1), ComprasGovMapper.parseDate("2026-09-01T07:26:01"))
        assertEquals(saoPaulo(2023, 11, 1, 0, 0, 0), ComprasGovMapper.parseDate("2023-11-01"))
        assertNull(ComprasGovMapper.parseDate(null))
        assertNull(ComprasGovMapper.parseDate(""))
        assertNull(ComprasGovMapper.parseDate("não é data"))
        assertEquals("2026-10-06", ComprasGovMapper.queryDate(saoPaulo(2026, 10, 6, 12, 0, 0)))
    }

    @Test
    fun `segmento e inferido por heuristica e cai em PERSONALIZADO fora do vocabulario`() {
        assertEquals(Segment.TELECOM_ISP, ComprasGovMapper.inferSegment("Contratação de link de internet dedicado via fibra óptica"))
        assertEquals(Segment.SOFTWARE, ComprasGovMapper.inferSegment("Licenciamento de software ERP em nuvem (SaaS)"))
        assertEquals(Segment.SERVICOS, ComprasGovMapper.inferSegment("Contratação de empresa para prestação de serviço de limpeza"))
        assertEquals(Segment.PERSONALIZADO, ComprasGovMapper.inferSegment("Aquisição de ração para cães"))
        assertEquals(Segment.PERSONALIZADO, ComprasGovMapper.inferSegment(""))
    }
}
