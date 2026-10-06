package com.licitaia.ai.mock

import com.licitaia.ai.api.AIProvider
import com.licitaia.ai.api.DocumentComparison
import com.licitaia.ai.api.MessageDraft
import com.licitaia.ai.api.ProposalDraft
import com.licitaia.ai.api.TenderAnalysisRequest
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.model.ExtractedEdital
import com.licitaia.domain.model.FitScore
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PriceRange
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.scoring.TextMatch
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Provedor de demonstração: offline, determinístico e coerente com os dados reais da
 * licitação e da empresa (ver [TenderHeuristics]). [latencyMs] simula o tempo de resposta
 * de um modelo; use `0L..0L` em testes.
 */
class MockAIProvider(
    private val latencyMs: LongRange = 600L..1200L,
    private val clock: () -> Long = System::currentTimeMillis,
) : AIProvider {

    override val type: AiProviderType = AiProviderType.MOCK
    /** Rótulo honesto: nada aqui é um modelo de IA — são heurísticas locais determinísticas. */
    override val displayName: String = HEURISTIC_LABEL

    private suspend fun think(seed: Any?) {
        val span = latencyMs.last - latencyMs.first
        if (latencyMs.last <= 0) return
        val extra = if (span <= 0) 0 else abs((seed?.hashCode() ?: 0).toLong()) % (span + 1)
        delay(latencyMs.first + extra)
    }

    override suspend fun analyzeTender(request: TenderAnalysisRequest): TenderAnalysis {
        think(request.tender.number)
        return TenderHeuristics.analyze(request, displayName)
    }

    override suspend fun summarize(text: String, maxSentences: Int): String {
        think(text.length)
        return TenderHeuristics.summarize(text, maxSentences)
    }

    override suspend fun extractRequirements(tender: Tender, editalText: String?): ExtractedEdital {
        think(tender.number)
        return TenderHeuristics.extract(tender, editalText)
    }

    override suspend fun scoreFit(
        extracted: ExtractedEdital,
        company: Company,
        documents: List<CompanyDocument>,
    ): FitScore {
        think(extracted.number)
        return TenderHeuristics.fit(tenderFrom(extracted, company), extracted, company, documents, clock())
    }

    override suspend fun suggestProposalRange(tender: Tender, extracted: ExtractedEdital, company: Company): PriceRange {
        think(tender.number)
        val fit = TenderHeuristics.fit(tender, extracted, company, emptyList(), clock())
        return TenderHeuristics.priceRange(tender.estimatedValue, fit.historicalCompetitors, fit.estimatedMarginPct)
    }

    override suspend fun draftProposal(tender: Tender, analysis: TenderAnalysis?, company: Company): ProposalDraft {
        think(tender.number)
        return TenderHeuristics.proposal(tender, analysis, company)
    }

    override suspend fun draftMessage(message: AuctioneerMessage, company: Company): MessageDraft {
        think(message.body.length)
        return TenderHeuristics.message(message, company)
    }

    override suspend fun draftAppeal(tender: Tender, grounds: String, company: Company): String {
        think(tender.number)
        return TenderHeuristics.appeal(tender, grounds, company)
    }

    override suspend fun compareDocuments(
        required: List<DocumentType>,
        available: List<CompanyDocument>,
        now: Long,
    ): DocumentComparison {
        think(required.size)
        return TenderHeuristics.compare(required, available, now)
    }

    companion object {
        /** Nome exibido/persistido em análises geradas sem modelo de IA. */
        const val HEURISTIC_LABEL = "Heurística local (sem IA)"

        /**
         * Reconstrói uma licitação mínima a partir do edital extraído (o contrato de `scoreFit`
         * não recebe a licitação). Sem UF conhecida, assume a sede da empresa.
         */
        fun tenderFrom(extracted: ExtractedEdital, company: Company): Tender {
            val text = TextMatch.normalize(extracted.objectDescription)
            val segment = when {
                listOf("internet", "link", "fibra", "banda larga", "telecom").any { text.contains(it) } -> Segment.TELECOM_ISP
                listOf("software", "sistema", "licenc", "saas", "aplicativo").any { text.contains(it) } -> Segment.SOFTWARE
                listOf("notebook", "computador", "equipamento", "aquisicao", "switch").any { text.contains(it) } -> Segment.EQUIPAMENTOS
                listOf("tecnologia da informacao", "infraestrutura", "datacenter", "suporte tecnico").any { text.contains(it) } -> Segment.TI
                else -> company.segment
            }
            return Tender(
                companyId = company.id,
                opportunityId = "",
                portal = Portal.entries.firstOrNull { it.displayName == extracted.portal } ?: Portal.COMPRAS_GOV,
                number = extracted.number,
                agency = extracted.agency,
                objectDescription = extracted.objectDescription,
                modality = Modality.entries.firstOrNull { it.label == extracted.modality } ?: Modality.PREGAO_ELETRONICO,
                segment = segment,
                uf = company.uf,
                city = "",
                estimatedValue = extracted.estimatedValue,
                proposalDeadline = extracted.proposalDeadline,
                sessionAt = extracted.sessionAt,
            )
        }
    }
}
