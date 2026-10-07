package com.licitaia.domain.competition

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Resultado PÚBLICO de um item de contratação já homologado (fornecedor vencedor e valor homologado), lido da API
 * pública de consulta do PNCP (`/orgaos/{cnpj}/compras/{ano}/{sequencial}/itens/{numeroItem}/resultados`).
 * Nenhum dado sigiloso: só o que o órgão publicou.
 */
data class PublicAwardResult(
    /** Número de controle PNCP da contratação (`<cnpj>-1-<sequencial>/<ano>`). */
    val controlNumber: String,
    val itemNumber: Int,
    val itemDescription: String = "",
    /** Órgão, objeto e UF da contratação (podem vir vazios quando a fonte só informa o item). */
    val agency: String = "",
    val objectSummary: String = "",
    val uf: String = "",
    val supplierName: String,
    /** CNPJ/CPF do fornecedor só com dígitos (`niFornecedor`); vazio quando não informado. */
    val supplierDocument: String,
    /** Porte do fornecedor (ME, EPP, Demais...) quando informado. */
    val supplierSize: String? = null,
    /** Valor total estimado do item (0 = sigiloso ou não informado). */
    val estimatedValue: Double = 0.0,
    /** Valor total homologado ao fornecedor. */
    val homologatedValue: Double,
    /** Desconto publicado (`percentualDesconto`) quando existir. */
    val publishedDiscountPct: Double? = null,
    /** Data do resultado (epoch millis); 0 = não informada. */
    val resultDate: Long = 0L,
    /** true = contratação da própria empresa (licitação acompanhada); false = mercado/segmento. */
    val ownTender: Boolean = false,
) {
    /** Desconto do homologado sobre o estimado (%), ou o publicado; null quando não dá para calcular. */
    val discountPct: Double?
        get() = when {
            estimatedValue > 0.0 && homologatedValue > 0.0 -> ((estimatedValue - homologatedValue) / estimatedValue * 100.0).coerceIn(-100.0, 100.0)
            publishedDiscountPct != null && !publishedDiscountPct.isNaN() -> publishedDiscountPct.coerceIn(-100.0, 100.0)
            else -> null
        }

    /** Chave de deduplicação (contratação + item + fornecedor). */
    val key: String get() = "$controlNumber#$itemNumber#${supplierDocument.filter { it.isDigit() }.ifEmpty { supplierName.trim().lowercase() }}"
}

/** Lote de resultados; [partial] = a fonte limitou as consultas (HTTP 429) e o restante fica para a próxima vez. */
data class PublicResultsBatch(
    val results: List<PublicAwardResult>,
    val partial: Boolean = false,
    /** Contratações consultadas neste lote. */
    val checkedContracts: Int = 0,
)

/** Consulta de mercado: contratações semelhantes (palavras do segmento/radares) já com resultado. */
data class MarketQuery(
    val keywords: List<String>,
    val ufs: List<String> = emptyList(),
    /** Janela de publicação, em dias para trás. */
    val windowDays: Int = 75,
    /** Máximo de contratações com resultado a detalhar (cada uma custa 1 + itens requisições). */
    val maxContracts: Int = 6,
    val maxItemsPerContract: Int = 3,
)

/** Fonte pública de resultados (implementada pelo conector do PNCP). */
interface PublicResultsSource {
    /** Resultados homologados dos itens da contratação; vazio quando ainda não há resultado publicado. */
    suspend fun awardResults(controlNumber: String, maxItems: Int = 10): PublicResultsBatch

    /** Contratações recentes semelhantes a [query] com resultado publicado. */
    suspend fun recentAwardsLike(query: MarketQuery): PublicResultsBatch
}

// ------------------------------------------------------------------ licitações acompanhadas (ponto de extensão)

/** Licitação da empresa cujo resultado público deve ser buscado. */
data class TrackedTender(
    val tenderId: Long?,
    val controlNumber: String,
    val portal: Portal,
    val number: String,
    val agency: String,
    val segment: Segment,
    val objectDescription: String,
    val estimatedValue: Double,
    /** Data da sessão/encerramento (epoch millis); 0 = desconhecida. */
    val sessionAt: Long,
    /** A empresa participou da disputa (proposta enviada, em disputa, vencida ou perdida). */
    val participated: Boolean,
    /** Resultado já conhecido pela empresa: true = vencida, false = perdida, null = não informado. */
    val knownOutcome: Boolean? = null,
    /** Nosso lance final, quando conhecido. */
    val ourFinalBid: Double? = null,
)

/**
 * PONTO DE EXTENSÃO: fornece licitações da empresa para a busca automática de resultados. A implementação padrão lê
 * as licitações salvas (interesse/participação/vencidas/perdidas). Outros módulos (ex.: "Minhas licitações" capturadas
 * nos portais) contribuem com `@IntoSet` no Hilt; duplicatas por número de controle são unificadas.
 */
interface TrackedTenderProvider {
    suspend fun trackedTenders(companyId: Long): List<TrackedTender>
}

// ------------------------------------------------------------------ sincronização

data class CompetitionSyncReport(
    val finishedAt: Long,
    /** Licitações da empresa consultadas no PNCP. */
    val checked: Int,
    /** Resultados da empresa gravados no histórico de concorrência. */
    val imported: Int,
    /** Licitações ainda sem resultado publicado. */
    val pending: Int,
    /** Resultados de mercado (segmento) obtidos. */
    val marketResults: Int,
    /** O PNCP limitou as consultas: parte ficou para a próxima atualização. */
    val partial: Boolean = false,
    /** Falha (sem rede etc.); null = sucesso. */
    val error: String? = null,
) {
    val summary: String
        get() = buildString {
            if (error != null) { append(error); return@buildString }
            append(if (imported == 1) "1 resultado novo" else "$imported resultados novos")
            append(" · ").append(checked).append(if (checked == 1) " licitação consultada" else " licitações consultadas")
            if (pending > 0) append(" · ").append(pending).append(" ainda sem resultado publicado")
            if (marketResults > 0) append(" · ").append(marketResults).append(" resultados do segmento")
            if (partial) append(" · o PNCP limitou as consultas; o restante entra na próxima atualização")
        }
}

/** Resultados de mercado guardados para a empresa (ranking de concorrentes e preço médio). */
data class MarketSnapshot(
    val results: List<PublicAwardResult> = emptyList(),
    val updatedAt: Long? = null,
    val keywords: List<String> = emptyList(),
)

/**
 * Busca automática de resultados públicos: para as licitações da empresa ([TrackedTenderProvider]) lê os resultados no
 * PNCP e grava no histórico de concorrência; também monta a base "Concorrentes do meu segmento".
 */
interface CompetitionResultsSync {
    /** Atualização em andamento (qualquer empresa). */
    val running: StateFlow<Boolean>

    fun observeMarket(companyId: Long): Flow<MarketSnapshot>
    fun observeLastReport(companyId: Long): Flow<CompetitionSyncReport?>

    /** Atualiza agora (botão "Atualizar resultados"). */
    suspend fun refresh(companyId: Long, includeMarket: Boolean = true): Result<CompetitionSyncReport>

    /** Execução diária (Worker/abertura da tela): só roda se a última foi há mais de [minIntervalMs]. null = não era hora. */
    suspend fun refreshIfDue(companyId: Long, minIntervalMs: Long = DAILY_MS): Result<CompetitionSyncReport>?

    companion object {
        const val DAILY_MS = 24L * 60 * 60 * 1000

        /** Marca, no resumo do registro de concorrência, os resultados importados automaticamente do PNCP. */
        const val PUBLIC_RESULT_TAG = "Resultado público PNCP"
    }
}
