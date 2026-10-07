package com.licitaia.domain.scoring

import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.ScoreSource
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpportunityScoringTest {
    private val isp = Company(1, "Conecta Minas Telecom Ltda", "Conecta Minas", "12345678000190", Segment.TELECOM_ISP, "MG", "Uberlândia")

    private fun opp(
        obj: String,
        segment: Segment,
        uf: String,
        value: Double = 300_000.0,
        portal: Portal = Portal.COMPRAS_GOV,
    ) = Opportunity(
        id = "${portal.name}:1/2026", portal = portal, number = "1/2026", agency = "Prefeitura Municipal",
        objectDescription = obj, modality = Modality.PREGAO_ELETRONICO, segment = segment, uf = uf,
        city = "Uberaba", estimatedValue = value, publishedAt = 0, proposalDeadline = 1_000, sessionAt = 2_000,
    )

    @Test
    fun adherentOpportunityScoresHigherThanUnrelated() {
        val good = opp("Contratação de link dedicado de internet em fibra óptica", Segment.TELECOM_ISP, "MG")
        val bad = opp("Aquisição de gêneros alimentícios", Segment.SERVICOS, "AM")
        val sGood = OpportunityScorer.score(good, isp)
        val sBad = OpportunityScorer.score(bad, isp)
        assertTrue("good=$sGood", sGood >= 80)
        assertTrue("bad=$sBad", sBad < 45)
    }

    @Test
    fun radarForbiddenKeywordExcludes() {
        val radar = Radar(
            companyId = 1, name = "Links", segment = Segment.TELECOM_ISP,
            keywords = listOf("internet"), forbiddenKeywords = listOf("satélite"),
        )
        assertTrue(RadarMatcher.matches(radar, opp("Link de internet dedicado", Segment.TELECOM_ISP, "MG")))
        assertFalse(RadarMatcher.matches(radar, opp("Internet via satelite", Segment.TELECOM_ISP, "MG")))
    }

    @Test
    fun radarRespectsUfPortalAndValue() {
        val radar = Radar(
            companyId = 1, name = "MG", segment = Segment.TELECOM_ISP, ufs = listOf("MG"),
            allPortals = false, portals = listOf(Portal.BLL), maxValue = 500_000.0,
        )
        assertTrue(RadarMatcher.matches(radar, opp("Internet", Segment.TELECOM_ISP, "MG", portal = Portal.BLL)))
        assertFalse(RadarMatcher.matches(radar, opp("Internet", Segment.TELECOM_ISP, "SP", portal = Portal.BLL)))
        assertFalse(RadarMatcher.matches(radar, opp("Internet", Segment.TELECOM_ISP, "MG")))
        assertFalse(RadarMatcher.matches(radar, opp("Internet", Segment.TELECOM_ISP, "MG", 900_000.0, Portal.BLL)))
    }

    // Caso real (06/10/2026): radar "comprasnet" (Telecom, internet/link/provedor/fibra, BA, score ≥ 70).
    private val ispBa = Company(1, "ME Telecom Servicos de Internet Ltda", "ME Telecom", "27147548000115", Segment.TELECOM_ISP, "BA", "Salvador")
    private val comprasnetRadar = Radar(
        companyId = 1, name = "comprasnet", segment = Segment.TELECOM_ISP,
        keywords = listOf("internet", "link", "provedor", "fibra"),
        allPortals = false, portals = listOf(Portal.COMPRAS_GOV), ufs = listOf("BA"), minScore = 70,
    )

    @Test
    fun almoxarifadoVirtualNaoCasaRadarTelecom() {
        val o = opp(
            "Contratação de serviços contínuos, terceirizados, de almoxarifado virtual, sob demanda, visando o suprimento de materiais de consumo",
            Segment.SERVICOS, "BA",
        ).copy(
            agency = "TRIBUNAL SUPERIOR DO TRABALHO", number = "170/2026",
            // Texto padrão de campos auxiliares não conta como palavra-chave.
            keywords = listOf("Pregão - Eletrônico", "Edital disponível no link do portal; propostas pela internet"),
        )
        assertFalse(RadarMatcher.matches(comprasnetRadar, o, ispBa.uf))
        val s = OpportunityScorer.score(o, ispBa, listOf(comprasnetRadar))
        assertTrue("score=$s", s < 70)
    }

    @Test
    fun linkDedicadoFibraCasaRadarTelecom() {
        val o = opp(
            "Serviço de link para conexões dedicadas de acesso à Internet, por meio de fibra óptica",
            Segment.TELECOM_ISP, "BA",
        )
        assertTrue(RadarMatcher.matches(comprasnetRadar, o, ispBa.uf))
        val s = OpportunityScorer.score(o, ispBa, listOf(comprasnetRadar))
        assertTrue("score=$s", s >= 70)
    }

    // Falsos positivos reais (06/10/2026): radar "comprasnet" Telecom, todo o Brasil, score mínimo 70 → antes nota 80.
    private val radarBrasil = Radar(
        companyId = 1, name = "comprasnet", segment = Segment.TELECOM_ISP,
        keywords = listOf("internet", "link", "provedor", "fibra"),
        allPortals = false, portals = listOf(Portal.COMPRAS_GOV), minScore = 70,
    )

    @Test
    fun coffeeBreakNaoEhTelecom() {
        // O conector pode ter classificado como Telecom: a heurística não confia nisso sem termos fortes.
        val o = opp(
            "Fornecimento de coffee break para os participantes da solenidade de lançamento do programa de inclusão digital",
            Segment.TELECOM_ISP, "PR",
        ).copy(modality = Modality.DISPENSA_ELETRONICA, agency = "INSTITUTO FEDERAL DO PARANA", number = "398/2026")
        val s = OpportunityScorer.score(o, ispBa, listOf(radarBrasil))
        assertTrue("score=$s", s < 40)
    }

    @Test
    fun gerenciamentoDeFrotaViaInternetNaoEhTelecom() {
        val o = opp(
            "Prestação do serviço de gerenciamento eletrônico, via internet, referente a manutenção preventiva e corretiva " +
                "da frota de veículos, com fornecimento de peças",
            Segment.TELECOM_ISP, "CE",
        )
        val a = OpportunityScorer.assess(o, ispBa, listOf(radarBrasil))
        assertTrue("score=${a.score}", a.score < 40)
        // "via internet" é meio: nem conta como palavra-chave (não vira candidato à nota por IA).
        assertFalse(a.isCandidate)
        assertFalse(RadarMatcher.matches(radarBrasil.copy(segment = Segment.PERSONALIZADO), o, ispBa.uf))
    }

    @Test
    fun linkDedicadoFibraTodoBrasilFicaAlto() {
        val o = opp(
            "Serviço de link para conexões dedicadas de acesso à Internet, por meio de fibra óptica, com velocidade mínima de 500 Mbps",
            Segment.TELECOM_ISP, "AM",
        )
        val a = OpportunityScorer.assess(o, ispBa, listOf(radarBrasil))
        assertTrue("score=${a.score}", a.score >= 80)
        assertTrue(a.isCandidate)
    }

    @Test
    fun palavraIsoladaNaoBastaEGeografiaNaoInfla() {
        val o = opp("Aquisição de cabos e conectores para link de rádio da guarda municipal", Segment.SERVICOS, "BA")
            .copy(city = "Salvador")
        val s = OpportunityScorer.score(o, ispBa, listOf(radarBrasil))
        assertTrue("score=$s", s < 70)
    }

    @Test
    fun aiMergeSubstituiNotaEAplicaMinimo() {
        val a = ScoredOpportunity(opp("Link dedicado", Segment.TELECOM_ISP, "BA"), 75, interested = true)
        val bOpp = opp("Conectividade", Segment.TELECOM_ISP, "BA").copy(id = "PNCP:2")
        val b = ScoredOpportunity(bOpp, 50, interested = false)
        val merged = AiScoreMerge.merge(
            current = listOf(a),
            rated = listOf(a.copy(score = 20, interested = false, scoreSource = ScoreSource.AI), b.copy(score = 92, scoreSource = ScoreSource.AI, scoreReason = "Objeto é conectividade")),
            minScore = 70,
        )
        assertEquals(listOf("PNCP:2"), merged.map { it.opportunity.id })
        assertEquals(ScoreSource.AI, merged.single().scoreSource)
    }

    @Test
    fun assinaturaMudaComOsTermosDoRadar() {
        val c1 = RelevanceContext.of(ispBa, listOf(radarBrasil))
        val c2 = RelevanceContext.of(ispBa, listOf(radarBrasil.copy(keywords = listOf("Fibra", "INTERNET", "link", "provedor"))))
        val c3 = RelevanceContext.of(ispBa, listOf(radarBrasil.copy(preferredObject = "link dedicado")))
        assertEquals(c1.signature, c2.signature)
        assertNotEquals(c1.signature, c3.signature)
        assertFalse(c1.hint.contains(ispBa.cnpj))
        assertFalse(c1.hint.contains(ispBa.name))
    }

    @Test
    fun palavraChaveCasaSoPalavraInteira() {
        assertFalse(TextMatch.containsTerm(TextMatch.normalize("Serviço de linkagem de dados"), "link"))
        assertFalse(TextMatch.containsTerm(TextMatch.normalize("Material radiológico"), "radio"))
        assertTrue(TextMatch.containsTerm(TextMatch.normalize("Links dedicados"), "link"))
        assertTrue(TextMatch.containsTerm(TextMatch.normalize("Equipamentos de rede"), "equipamento"))
        assertTrue(TextMatch.containsTerm(TextMatch.normalize("Contratação de LINK DEDICADO"), "link dedicado"))
        assertTrue(TextMatch.containsTerm(TextMatch.normalize("Instalações elétricas"), "instalacao"))
    }

    @Test
    fun filterQueryIgnoresAccentsAndCase() {
        val o = opp("Contratação de FIBRA ÓPTICA", Segment.TELECOM_ISP, "MG")
        assertTrue(OpportunityFilterMatcher.matches(OpportunityFilter(query = "fibra optica"), o))
        assertFalse(OpportunityFilterMatcher.matches(OpportunityFilter(query = "software"), o))
        assertFalse(OpportunityFilterMatcher.matches(OpportunityFilter(minValue = 1_000_000.0), o))
    }
}
