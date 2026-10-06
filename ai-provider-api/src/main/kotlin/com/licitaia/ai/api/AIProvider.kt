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
}

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
 * Resolve o provedor ativo (empresa → global). Sem chave configurada, SEMPRE cai no mock,
 * de modo que o app funcione sem nenhuma credencial.
 */
interface AiGateway {
    suspend fun current(): AIProvider
    fun provider(type: AiProviderType): AIProvider
}
