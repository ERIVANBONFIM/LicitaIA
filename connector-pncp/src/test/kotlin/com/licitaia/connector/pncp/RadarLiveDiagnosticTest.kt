package com.licitaia.connector.pncp

import com.licitaia.connector.comprasgov.ComprasGovConnector
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.Segment
import com.licitaia.domain.scoring.OpportunityFilterMatcher
import com.licitaia.domain.scoring.OpportunityScorer
import com.licitaia.domain.scoring.RadarMatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Diagnóstico contra as APIs REAIS (rede): reproduz o pipeline de OpportunityRepositoryImpl
 * (conectores → classificação por plataforma → dedup → filtro/score ou RadarMatcher/score → prazo)
 * para o chip/radar "Compras.gov" e imprime quantos itens sobrevivem a cada etapa.
 *
 * Não roda no build normal. Execute com:
 * `gradlew :connector-pncp:testDebugUnitTest --tests "*RadarLiveDiagnosticTest*" -Plicitaia.live=true`
 */
class RadarLiveDiagnosticTest {

    @Before
    fun onlyWhenLive() {
        assumeTrue("diagnóstico ao vivo desligado (-Plicitaia.live=true)", System.getProperty("licitaia.live") == "true")
    }

    private val client = OkHttpClient()
    private val pncp = PncpConnector(client)
    private val compras = ComprasGovConnector(client)
    private val company = Company(id = 1, name = "ISP", tradeName = "ISP", cnpj = "00000000000000", segment = Segment.TELECOM_ISP, uf = "BA", city = "Salvador")
    private val telecomWords = listOf("internet", "link", "fibra", "telecomunicações")

    /** Mesma chave do OpportunityDeduplicator (número de controle PNCP no sufixo do id). */
    private fun dedupe(list: List<Opportunity>): List<Opportunity> {
        val byKey = LinkedHashMap<String, Opportunity>()
        for (o in list) {
            val k = o.id.substringAfter(':')
            val cur = byKey[k]
            if (cur == null || (o.id.startsWith("COMPRAS_GOV:") && !cur.id.startsWith("COMPRAS_GOV:"))) byKey[k] = o
        }
        return byKey.values.toList()
    }

    private fun scenario(ufs: Set<String>) = runBlocking {
        val label = if (ufs.isEmpty()) "todas as UFs" else ufs.joinToString()
        val now = System.currentTimeMillis()
        val apiFilter = OpportunityFilter(portals = setOf(Portal.COMPRAS_GOV), ufs = ufs)
        val t0 = System.currentTimeMillis()
        val fromPncp = runCatching { pncp.listOpportunities(apiFilter) }.onFailure { println("PNCP falhou: ${it.message}") }.getOrDefault(emptyList())
        val t1 = System.currentTimeMillis()
        val fromCompras = runCatching { compras.listOpportunities(apiFilter) }.onFailure { println("Compras falhou: ${it.message}") }.getOrDefault(emptyList())
        val t2 = System.currentTimeMillis()
        val pncpAll = runCatching { pncp.listOpportunities(OpportunityFilter(ufs = ufs)) }.getOrDefault(emptyList())
        println("=== Compras.gov — $label ===")
        println("PNCP (todas as plataformas, sem filtro de portal): ${pncpAll.size} → por portal ${pncpAll.groupingBy { it.portal }.eachCount()}")
        println("PNCP classificados COMPRAS_GOV: ${fromPncp.size} (${t1 - t0} ms)")
        println("Conector Compras.gov.br: ${fromCompras.size} (${t2 - t1} ms)")
        val merged = dedupe((fromPncp + fromCompras).filter { it.portal == Portal.COMPRAS_GOV })
        println("Após dedup: ${merged.size}")
        val open = merged.filter { it.proposalDeadline >= now }
        println("Prazo ainda aberto: ${open.size}")
        // Busca: sem palavras e com cada palavra de Telecom.
        val noQuery = merged.filter { OpportunityFilterMatcher.matches(apiFilter, it) }
        println("Busca sem palavra-chave: ${noQuery.size}")
        for (w in telecomWords) {
            val f = apiFilter.copy(query = w)
            println("Busca \"$w\": ${merged.count { OpportunityFilterMatcher.matches(f, it) }}")
        }
        val segment = merged.count { OpportunityFilterMatcher.matches(apiFilter.copy(segment = Segment.TELECOM_ISP), it) }
        println("Busca segmento Telecom/ISP: $segment")
        // Radar (padrão da tela: score mínimo 60).
        val radar = Radar(
            companyId = 1, name = "Telecom", segment = Segment.TELECOM_ISP, keywords = telecomWords,
            portals = listOf(Portal.COMPRAS_GOV), allPortals = false, ufs = ufs.toList(), minScore = 60,
        )
        val radarMatched = merged.filter { RadarMatcher.matches(radar, it, company.uf) }
        val radarScored = radarMatched.filter { OpportunityScorer.score(it, company, listOf(radar)) >= radar.minScore }
        println("Radar Telecom: casam ${radarMatched.size}, score ≥ 60: ${radarScored.size}")
        radarScored.take(5).forEach { println("  • ${it.uf} ${it.objectDescription.take(90)}") }
    }

    @Test fun comprasGov_todasAsUfs() = scenario(emptySet())
    @Test fun comprasGov_BA() = scenario(setOf("BA"))
    @Test fun comprasGov_MG() = scenario(setOf("MG"))
    @Test fun comprasGov_SP() = scenario(setOf("SP"))
}
