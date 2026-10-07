package com.licitaia.domain.portal

import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.Portal
import kotlinx.coroutines.flow.Flow

/**
 * Uma licitação que a PRÓPRIA empresa separou/participa no Comprasnet, lida da sessão logada do usuário
 * (lista "Compras eletrônicas" → "Minhas participações", aberta pelo menu "Licitação e Dispensa (novo)").
 * `situation` guarda a etapa ("Seleção de fornecedores", "Proposta até 13/10/2026 08:59") ou o aviso ("Compra suspensa")
 * e " · Favorita" quando o coração do portal está marcado; `objectDescription` guarda o órgão (o cartão não mostra o objeto).
 *
 * [tenderKey] = chave normalizada `<uasg 6>-<número 5>-<ano>` ([PortalTenderMatching.tenderKey]); única por empresa.
 */
data class PortalMyTender(
    val companyId: Long,
    val tenderKey: String,
    val portal: Portal = Portal.COMPRAS_GOV,
    val uasg: String,
    val number: String,
    val year: Int,
    /** Modalidade como o portal escreve (ex.: "Pregão Eletrônico", "Dispensa"). */
    val modality: String,
    val objectDescription: String,
    /** Data/hora de abertura da sessão pública (null = não informada na página). */
    val openingAt: Long?,
    /** Situação como o portal escreve (ex.: "Em andamento", "Proposta cadastrada"). */
    val situation: String,
    /** A página indicou proposta cadastrada pela empresa. */
    val hasProposal: Boolean,
    /** Fontes onde a licitação apareceu: [SOURCE_PARTICIPACOES] (atual); [SOURCE_SPA]/legadas em leituras antigas. */
    val sources: Set<String>,
    /** Número de controle PNCP (`<cnpj>-1-<seq>/<ano>`) quando a página o mostra. */
    val pncpControl: String? = null,
    /** Oportunidade do app com a mesma UASG+número+ano (ou número de controle PNCP), se houver. */
    val matchedOpportunityId: String? = null,
    /** Licitação ("Tenho interesse") do app correspondente, se houver. */
    val matchedTenderId: Long? = null,
    val firstSeenAt: Long,
    val updatedAt: Long,
) {
    /** "UASG 160123 · 90005/2026". */
    val label: String get() = "UASG $uasg · $number/$year"

    companion object {
        /** Leituras antigas de `cotacao.asp` (Cotação Eletrônica, Lei 8.666) — não são mais feitas; ficam no histórico. */
        const val SOURCE_PARTICIPOU = "legado-participou"
        const val SOURCE_ANDAMENTO = "legado-andamento"
        const val SOURCE_SPA = "compras-eletronicas"
        /** Aba "Minhas participações" da lista "Compras eletrônicas" (filtros Em andamento + Propostas). */
        const val SOURCE_PARTICIPACOES = "minhas-participacoes"
    }
}

/** Item da proposta que o robô cadastra no portal (valores definidos/confirmados pelo usuário). */
data class ProposalItemPlan(
    /** Número do item no portal (1, 2, ...). */
    val itemNumber: Int,
    val description: String = "",
    val quantity: Double,
    val unitPrice: Double,
    val brand: String = "",
    val manufacturer: String = "",
    val modelVersion: String = "",
    val detailedDescription: String = "",
    /** Piso do lance POR UNIDADE deste item (robô de lance nunca vai abaixo). null = sem piso → robô não opera o item. */
    val floorUnitPrice: Double? = null,
) {
    val totalPrice: Double get() = quantity * unitPrice
}

enum class RobotProposalStatus(val label: String) {
    NAO_CONFIGURADA("Não configurada"),
    PRONTA("Pronta para cadastrar"),
    EXECUTANDO("Cadastrando…"),
    AGUARDANDO_USUARIO("Aguardando você no portal"),
    CADASTRADA("Proposta cadastrada"),
    PARCIAL("Cadastrada em parte"),
    FALHOU("Falhou"),
}

enum class BidRobotMode(val label: String, val description: String) {
    DESLIGADO("Desligado", "O robô não participa da disputa."),
    MANUAL("Manual", "O app lê a sala, sugere o lance e você toca em Enviar (ou dá o lance no portal)."),
    AUTOMATICO("Automático", "O robô envia lances sozinho dentro do piso, dos intervalos e do teto que você confirmou."),
}

/** Parâmetros do robô de lance de UMA licitação (por sessão). */
data class BidRobotConfig(
    val mode: BidRobotMode = BidRobotMode.DESLIGADO,
    val strategy: BidStrategy = BidStrategy.CONSERVADORA,
    /** Decremento mínimo por lance (R$) exigido pelo edital/portal. */
    val minDecrement: Double = 0.01,
    /** Redução por lance da estratégia (R$). */
    val reductionValue: Double = 1.0,
    /** Intervalo mínimo entre lances PRÓPRIOS (regra do Compras.gov.br: 20 s). */
    val ownIntervalSeconds: Int = MIN_OWN_INTERVAL_SECONDS,
    /** Espera mínima após o registro do melhor lance (regra do Compras.gov.br: 3 s). */
    val afterBestSeconds: Int = MIN_AFTER_BEST_SECONDS,
    /** Teto de lances enviados pelo robô na sessão. */
    val maxBids: Int = 30,
    /** O edital disputa pelo VALOR TOTAL do item (lance = unitário × quantidade); false = valor unitário. */
    val bidOnTotal: Boolean = false,
) {
    companion object {
        const val MIN_OWN_INTERVAL_SECONDS = 20
        const val MIN_AFTER_BEST_SECONDS = 3
    }
}

/** Plano do robô (proposta + lance) de uma licitação da empresa. */
data class PortalRobotPlan(
    val companyId: Long,
    val tenderKey: String,
    val items: List<ProposalItemPlan> = emptyList(),
    val proposalStatus: RobotProposalStatus = RobotProposalStatus.NAO_CONFIGURADA,
    /** Linhas do último log do robô de proposta (mais antigo primeiro, sem dados sensíveis). */
    val proposalLog: List<String> = emptyList(),
    val bid: BidRobotConfig = BidRobotConfig(),
    /** Momento em que o usuário armou o robô de lance (confirmação explícita); null = desarmado. */
    val bidArmedAt: Long? = null,
    /** Horário da sessão pública (agenda do robô). */
    val sessionAt: Long? = null,
    /** Sessão de acompanhamento (Pregões ao Vivo) criada para o histórico de lances. */
    val liveSessionId: String? = null,
    val updatedAt: Long = 0,
) {
    val bidArmed: Boolean get() = bidArmedAt != null && bid.mode != BidRobotMode.DESLIGADO
}

/** Persistência das "minhas licitações" do Comprasnet e dos planos do robô (Room, por empresa). */
interface PortalRobotRepository {
    fun observeMyTenders(companyId: Long): Flow<List<PortalMyTender>>
    suspend fun getMyTenders(companyId: Long): List<PortalMyTender>

    /**
     * Grava o resultado de uma leitura: insere/atualiza [items] (mesclando fontes e mantendo [PortalMyTender.firstSeenAt]).
     * Nada é apagado: uma licitação que sumiu da página continua listada (o usuário pode tê-la só retirado do filtro).
     */
    suspend fun upsertMyTenders(companyId: Long, items: List<PortalMyTender>)

    suspend fun deleteMyTender(companyId: Long, tenderKey: String)

    fun observePlans(companyId: Long): Flow<List<PortalRobotPlan>>
    fun observePlan(companyId: Long, tenderKey: String): Flow<PortalRobotPlan?>
    suspend fun getPlan(companyId: Long, tenderKey: String): PortalRobotPlan?
    suspend fun savePlan(plan: PortalRobotPlan)
}

/** Resultado de "Buscar minhas licitações". */
data class MyTendersRefreshSummary(
    val found: Int,
    val matched: Int,
    /** Fontes lidas com sucesso. */
    val sources: List<String>,
    /** Avisos por fonte (ex.: "Compras eletrônicas: tela ainda não mapeada"). */
    val warnings: List<String> = emptyList(),
)

/** Sessão do Comprasnet não está logada: "Entre no Comprasnet em Portais para buscar suas licitações". */
class PortalNotLoggedInException(message: String = "Entre no Comprasnet em Portais para buscar suas licitações.") : IllegalStateException(message)

/**
 * "Buscar minhas licitações" do Comprasnet com a sessão logada do usuário. Implementado em feature-live (WebView);
 * pode ser chamado pela sincronização diária (worker) e pelas telas.
 */
interface MyTendersRefresher {
    /**
     * @param includeElectronic lê a lista "Compras eletrônicas" pelo menu (usa a aba retida do portal; só com o app
     *        aberto e o portal fora da tela). false = nada a ler (a lista do SPA é a única fonte).
     * Falha com [PortalNotLoggedInException] quando a sessão não está logada.
     */
    suspend fun refreshMyTenders(companyId: Long, includeElectronic: Boolean = true): Result<MyTendersRefreshSummary>
}
