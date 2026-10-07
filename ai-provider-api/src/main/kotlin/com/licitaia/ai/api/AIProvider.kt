package com.licitaia.ai.api

import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.model.ExtractedEdital
import com.licitaia.domain.model.FitScore
import com.licitaia.domain.model.PriceRange
import com.licitaia.domain.model.ProposalItem
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis

/**
 * Contrato único dos provedores de IA (SDD §23). Implementações: Mock, OpenAI, Anthropic,
 * Gemini e API personalizada. Erros de rede/credencial devem ser lançados como [AiProviderException].
 */
interface AIProvider {
    val type: AiProviderType
    val displayName: String

    suspend fun analyzeTender(request: TenderAnalysisRequest): TenderAnalysis
    suspend fun summarize(text: String, maxSentences: Int = 3): String
    suspend fun extractRequirements(tender: Tender, editalText: String?): ExtractedEdital
    suspend fun scoreFit(extracted: ExtractedEdital, company: Company, documents: List<CompanyDocument>): FitScore
    suspend fun suggestProposalRange(tender: Tender, extracted: ExtractedEdital, company: Company): PriceRange
    suspend fun draftProposal(tender: Tender, analysis: TenderAnalysis?, company: Company): ProposalDraft
    suspend fun draftMessage(message: AuctioneerMessage, company: Company): MessageDraft
    suspend fun draftAppeal(tender: Tender, grounds: String, company: Company): String
    suspend fun compareDocuments(required: List<DocumentType>, available: List<CompanyDocument>, now: Long): DocumentComparison

    /**
     * Nota de relevância 0..100 para um lote (até [MAX_RELEVANCE_BATCH]) de objetos de licitação: "o objeto é algo que
     * esta empresa fornece?". [radarHint] descreve segmento/palavras/CNAE/objeto preferencial (sem dados pessoais).
     * Itens sem nota na resposta ficam fora da lista devolvida. Padrão (heurística/mock): lista vazia = sem nota por
     * IA, e quem chama mantém a nota heurística marcada como tal.
     */
    suspend fun rateRelevance(company: Company, radarHint: String, items: List<RelevanceItem>): List<RelevanceScore> = emptyList()

    /**
     * "Pergunte ao edital": responde em texto livre a [EditalQuestionRequest.prompt] (já com o edital/recorte e as
     * instruções de [EditalQuestionRequest.system]). Erros sobem como [AiProviderException]. Padrão: não suportado.
     */
    suspend fun askEdital(request: EditalQuestionRequest): EditalAnswer =
        throw AiProviderException("$displayName não responde perguntas sobre o edital.")

    companion object {
        const val MAX_RELEVANCE_BATCH = 25
    }
}

/** Objeto a avaliar (sem dados pessoais): id, objeto, órgão e modalidade. */
data class RelevanceItem(
    val id: String,
    val objectDescription: String,
    val agency: String,
    val modality: String,
)

/** Nota 0..100 com motivo curto, devolvida pelo provedor de IA. */
data class RelevanceScore(
    val id: String,
    val score: Int,
    val reason: String,
)

/** Pergunta ao edital já montada (ver `EditalQuestionPrompt`); [editalExcerpt] é o texto/recorte enviado. */
data class EditalQuestionRequest(
    val system: String,
    val prompt: String,
    val question: String,
    val editalExcerpt: String,
)

/** Resposta em texto (com as linhas "Fonte: ...") e o modelo que respondeu, quando conhecido. */
data class EditalAnswer(
    val text: String,
    val model: String? = null,
)

data class TenderAnalysisRequest(
    val tender: Tender,
    val company: Company,
    val documents: List<CompanyDocument>,
    /** Texto do edital quando disponível; null = analisar a partir dos metadados. */
    val editalText: String? = null,
    val now: Long,
)

data class ProposalDraft(
    val items: List<ProposalItem>,
    val deliveryDays: Int,
    val validityDays: Int,
    val notes: String,
)

data class MessageDraft(
    val summary: String,
    val suggestedReply: String,
    val urgent: Boolean,
)

data class DocumentComparison(
    val satisfied: List<DocumentType>,
    val expiring: List<DocumentType>,
    val expired: List<DocumentType>,
    val missing: List<DocumentType>,
) {
    val coveragePct: Int
        get() {
            val total = satisfied.size + expiring.size + expired.size + missing.size
            return if (total == 0) 100 else (satisfied.size + expiring.size) * 100 / total
        }
}

class AiProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Resolve o provedor ativo de todo o app (empresa explícita → global → qualquer conta/chave configurada).
 * Sem nenhuma credencial, cai no mock (heurística local), de modo que o app funcione sem IA real.
 */
interface AiGateway {
    suspend fun current(): AIProvider
    fun provider(type: AiProviderType): AIProvider
    /** Tipo do provedor que [current] usaria agora (MOCK = nenhum provedor real disponível). */
    suspend fun activeType(): AiProviderType = current().type
}
