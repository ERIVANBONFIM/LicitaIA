package com.licitaia.domain.model

// Convenções: datas em epoch millis (Long); valores monetários em BRL (Double).

// ---------------------------------------------------------------- Empresa / usuários

data class Company(
    val id: Long = 0,
    val name: String,
    val tradeName: String,
    val cnpj: String,
    val segment: Segment,
    val uf: String,
    val city: String,
    /** Provedor de IA preferido da empresa; null = usa o provedor global ativo. */
    val preferredAi: AiProviderType? = null,
    /** Empresa do espaço de demonstração: dados fictícios, visíveis apenas ao usuário demo. */
    val demo: Boolean = false,
    // Dados da proposta comercial em PDF (versão 12 do banco). Vazio = não informado; nunca aparece no PDF.
    /** Logradouro e número (ex.: "Rua das Flores, 123"). */
    val street: String = "",
    val complement: String = "",
    val district: String = "",
    /** CEP, somente dígitos (8). */
    val zipCode: String = "",
    /** Telefone com DDD, somente dígitos (10 ou 11). */
    val phone: String = "",
    val email: String = "",
    val legalRepName: String = "",
    /** CPF do representante legal, somente dígitos (11). */
    val legalRepCpf: String = "",
    val legalRepRole: String = "",
    val bankName: String = "",
    val bankAgency: String = "",
    val bankAccount: String = "",
)

data class UserProfile(
    val id: Long = 0,
    val name: String,
    val email: String,
    val role: UserRole,
    /** Empresas às quais o usuário tem acesso. Vazio = identificado, mas sem acesso (aguardando vínculo). */
    val companyIds: List<Long>,
    val provider: AuthProvider = AuthProvider.LOCAL,
    /** Usuário de demonstração (seed). Nunca se mistura com contas reais. */
    val demo: Boolean = false,
) {
    val hasCompanyAccess: Boolean get() = companyIds.isNotEmpty()
}

data class AuthSession(
    val user: UserProfile,
    val activeCompany: Company,
)

// ---------------------------------------------------------------- Radar / oportunidades

data class Radar(
    val id: Long = 0,
    val companyId: Long,
    val name: String,
    val segment: Segment,
    val keywords: List<String> = emptyList(),
    val forbiddenKeywords: List<String> = emptyList(),
    /** Ignorado quando [allPortals] = true. */
    val portals: List<Portal> = emptyList(),
    val allPortals: Boolean = true,
    val ufs: List<String> = emptyList(),
    val region: String? = null,
    val agency: String? = null,
    val modality: Modality? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val minScore: Int = 0,
    val cnae: String? = null,
    val preferredObject: String? = null,
    val requireLocalSupport: Boolean = false,
    val active: Boolean = true,
    val createdAt: Long = 0,
    /** Inclui dispensas sem disputa (contratação direta, [Opportunity.noDispute]). Padrão: ocultas. */
    val showNoDispute: Boolean = false,
)

data class Opportunity(
    /** Identificador único estável (ex.: "COMPRAS_GOV:90012/2026"). */
    val id: String,
    val portal: Portal,
    val number: String,
    val agency: String,
    val objectDescription: String,
    val modality: Modality,
    val segment: Segment,
    val uf: String,
    val city: String,
    val estimatedValue: Double,
    val publishedAt: Long,
    val proposalDeadline: Long,
    val sessionAt: Long,
    val requiresLocalSupport: Boolean = false,
    val keywords: List<String> = emptyList(),
    val editalUrl: String? = null,
    /**
     * Nome exibível da plataforma que publicou a contratação (campo `usuarioNome` do PNCP, ex.: "Licitar Digital").
     * Null quando a fonte não informa (ex.: conector Compras.gov.br, cache antigo).
     */
    val platformName: String? = null,
    /**
     * Dispensa SEM disputa (contratação direta: "Ato que autoriza a Contratação Direta", modo de disputa
     * "Não se aplica"): não há proposta a enviar nem prazo. Oculta por padrão na Busca e nos Radares.
     */
    val noDispute: Boolean = false,
    /** Início do recebimento de propostas (`dataAberturaProposta`); null = não informado. Futuro = "vai abrir". */
    val proposalOpening: Long? = null,
    /**
     * Código da unidade compradora: UASG (6 dígitos) no Compras.gov.br; nas demais plataformas do PNCP, o código da
     * unidade do órgão. null = a fonte não informou (ver [UasgCode.of], que também lê "UASG 123456" das palavras-chave).
     */
    val uasg: String? = null,
    /** Situação oficial que interrompe/encerra a contratação (suspensa, revogada...); null = normal. Versão 14. */
    val officialSituation: OfficialSituation? = null,
    /**
     * Prazo de propostas anterior quando a fonte mudou a data (licitação ADIADA/remarcada), detectado na atualização
     * comparando com o cache; null = nunca mudou. Versão 14.
     */
    val previousProposalDeadline: Long? = null,
) {
    /** ADIADA: o prazo mudou em relação ao que estava salvo, ou a fonte publicou "adiada"/"remarcada". */
    val postponed: Boolean get() = Postponement.isPostponed(this)

    /**
     * A fonte informou o fim do recebimento de propostas. false = "prazo não informado"
     * ([proposalDeadline] = [DEADLINE_UNKNOWN]); a data de publicação NUNCA é usada como prazo.
     */
    val hasProposalDeadline: Boolean get() = proposalDeadline > DEADLINE_UNKNOWN

    /** Prazo conhecido e já vencido em [now]: não é mais oportunidade. */
    fun isProposalClosed(now: Long): Boolean = hasProposalDeadline && proposalDeadline < now

    companion object {
        /** Sentinela de "prazo não informado pela fonte". */
        const val DEADLINE_UNKNOWN = 0L
    }
}

data class ScoredOpportunity(
    val opportunity: Opportunity,
    /** Score de aderência 0..100. */
    val score: Int,
    val interested: Boolean,
    /** De onde veio a nota: heurística local (padrão) ou avaliação do provedor de IA. */
    val scoreSource: ScoreSource = ScoreSource.HEURISTIC,
    /** Motivo curto da nota por IA (null na heurística). */
    val scoreReason: String? = null,
)

/** Origem do score de aderência exibido. */
enum class ScoreSource { HEURISTIC, AI }

/**
 * Pedido de notas por IA para os candidatos de uma busca/radar (heurística já aplicada e cache já consultado).
 * Não contém dados pessoais: só o contexto do radar (segmento, palavras, CNAE, objeto preferencial).
 */
data class AiScoringRequest(
    val companyId: Long,
    /** null = busca livre (contexto = empresa + radares ativos). */
    val radarId: Long?,
    val minScore: Int,
    /** Hash do contexto (segmento + palavras + objeto preferencial) — chave do cache de notas. */
    val radarSignature: String,
    /** Contexto curto enviado ao modelo. */
    val radarHint: String,
    /** Candidatos ainda sem nota por IA (heurística), em ordem de prioridade (prazos mais próximos primeiro). */
    val candidates: List<ScoredOpportunity>,
    /** Itens desta lista que já tinham nota por IA no cache. */
    val alreadyRated: Int = 0,
) {
    /** Total de itens com nota por IA esperada nesta execução (cache + pendentes). */
    val total: Int get() = alreadyRated + candidates.size
}

/** Lote de notas por IA recebido; [failure] != null = a IA parou (sem rede/erro) e o restante fica heurístico. */
data class AiScoreUpdate(
    val rated: List<ScoredOpportunity>,
    val failure: String? = null,
)

/**
 * Resultado de uma busca com a contagem do que cada FONTE (conector) trouxe para os portais pedidos, antes da
 * deduplicação e dos filtros de texto/score. [fromCache] = sem consulta às fontes (offline/falha de todas).
 */
data class SearchOutcome(
    val items: List<ScoredOpportunity>,
    /** Fonte (portal do conector: PNCP, COMPRAS_GOV) → itens obtidos; vazio = não informado. */
    val sourceCounts: Map<Portal, Int> = emptyMap(),
    val fromCache: Boolean = false,
    /** Fontes consultadas que falharam/estavam em espera (limite de consultas, timeout) nesta busca. */
    val failedSources: Set<Portal> = emptySet(),
    /** Candidatos aguardando nota por IA (null = sem provedor real configurado ou nada a avaliar). */
    val aiRequest: AiScoringRequest? = null,
    /** Funil das fontes que leem tudo e triam no aparelho (Compras.gov.br): lidas → candidatas → abertas. */
    val sourceDiagnostics: Map<Portal, SourceDiagnostics> = emptyMap(),
    /** Dispensas sem disputa que casariam com a busca/radar, mas ficaram ocultas (opção desligada). */
    val hiddenNoDispute: Int = 0,
    /** Itens sem data de encerramento confiável (mesmo após o PNCP), ocultos por padrão. */
    val hiddenNoDeadline: Int = 0,
    /**
     * Leitura SÓ do que já está salvo no aparelho (abertura da tela): as fontes não foram consultadas de propósito — a
     * atualização diária já baixou as novas. Diferente de [fromCache] por falha/sem internet.
     */
    val cacheSnapshot: Boolean = false,
    /**
     * Itens com data de proposta em dias anteriores do mês atual (já com nota), fora de [items]: a tela os mostra na
     * seção "Dias anteriores" com "Mostrar dias anteriores" ou quando há texto digitado na busca.
     */
    val previousDays: List<ScoredOpportunity> = emptyList(),
) {
    /**
     * Ex.: "PNCP 120 · Compras.gov.br 8000 lidas · 34 candidatas · 12 abertas · 5 dispensas sem disputa ocultas" ou
     * "Compras.gov.br 167 · PNCP indisponível agora"; null sem contagem.
     */
    val sourceSummary: String?
        get() {
            val parts = if (cacheSnapshot) listOf("Licitações salvas no aparelho")
            else if (fromCache) listOf("Cache local (fontes não consultadas)")
            else sourceCounts.entries.sortedBy { it.key.ordinal }.map { (portal, count) ->
                sourceDiagnostics[portal]?.let { "${portal.displayName} ${it.summary}" } ?: "${portal.displayName} $count"
            } +
                failedSources.filter { it !in sourceCounts }.sortedBy { it.ordinal }.map { "${it.displayName} indisponível agora" }
            val hidden = if (hiddenNoDispute <= 0) emptyList()
            else listOf(if (hiddenNoDispute == 1) "1 dispensa sem disputa oculta" else "$hiddenNoDispute dispensas sem disputa ocultas")
            val noDeadline = if (hiddenNoDeadline <= 0) emptyList() else listOf("$hiddenNoDeadline sem prazo ocultas")
            return (parts + hidden + noDeadline).joinToString(" · ").ifEmpty { null }
        }

    /** Alguma fonte devolveu resultado parcial porque a varredura completa ainda está em andamento. */
    val syncing: Boolean get() = sourceDiagnostics.values.any { it.syncing }

    /**
     * Por que a lista ficou vazia, pela causa REAL (não um genérico "nenhum passou nos filtros"):
     * sincronização em andamento/incompleta, nada casou com as palavras, tudo oculto (sem disputa/sem prazo) ou
     * abaixo do score mínimo. null quando há itens.
     */
    val emptyReason: String?
        get() {
            if (items.isNotEmpty()) return null
            val failure = sourceDiagnostics.values.firstNotNullOfOrNull { it.syncFailure }
            val candidates = sourceDiagnostics.values.sumOf { it.candidates } +
                sourceCounts.filterKeys { it !in sourceDiagnostics }.values.sum()
            val hiddenParts = listOfNotNull(
                hiddenNoDispute.takeIf { it > 0 }?.let { if (it == 1) "1 dispensa sem disputa" else "$it dispensas sem disputa" },
                hiddenNoDeadline.takeIf { it > 0 }?.let { "$it sem prazo de propostas" },
            )
            return when {
                syncing -> "sincronização do Compras.gov.br em andamento — resultado parcial; a lista será atualizada ao terminar"
                failure != null -> "a leitura completa do Compras.gov.br não terminou ($failure) — resultado parcial; nova tentativa na próxima atualização"
                previousDays.isNotEmpty() ->
                    "nenhuma de hoje em diante; ${previousDays.size} de dias anteriores do mês (marque \"Mostrar dias anteriores\")"
                cacheSnapshot -> "nada salvo no aparelho casou com a busca; puxe para atualizar"
                fromCache -> "fontes não consultadas; só o cache local"
                candidates == 0 -> "nenhuma licitação aberta das fontes casou com as palavras/filtros"
                hiddenParts.isNotEmpty() -> "as que casaram estão ocultas: ${hiddenParts.joinToString(" e ")}"
                else -> "nenhuma atingiu o score mínimo ou os demais filtros"
            }
        }
}

/**
 * Regra de "dispensa sem disputa" (contratação direta), confirmada em respostas reais de 06/10/2026 do Compras.gov.br
 * (`modoDisputaIdPncp`/`modoDisputaNomePncp`, `tipoInstrumentoConvocatorioCodigoPncp`) e do PNCP (`modoDisputaId`,
 * `tipoInstrumentoConvocatorioCodigo`): na modalidade Dispensa, modo de disputa 5 "Não se aplica" + instrumento 3
 * "Ato que autoriza a Contratação Direta" + datas de proposta nulas; as dispensas COM disputa vêm com modo 4
 * "Dispensa Com Disputa", instrumento 2 "Aviso de Contratação Direta" e prazo.
 */
object NoDisputeRule {
    const val MODO_DISPUTA_NAO_SE_APLICA = 5
    const val INSTRUMENTO_ATO_CONTRATACAO_DIRETA = 3
    const val INSTRUMENTO_AVISO_CONTRATACAO_DIRETA = 2

    fun isNoDispute(
        modality: Modality,
        modoDisputaId: Int?,
        modoDisputaNome: String?,
        tipoInstrumentoCodigo: Int?,
        hasProposalDeadline: Boolean,
    ): Boolean {
        if (modality != Modality.DISPENSA_ELETRONICA) return false
        val nome = java.text.Normalizer.normalize(modoDisputaNome.orEmpty(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().trim()
        if (modoDisputaId == MODO_DISPUTA_NAO_SE_APLICA || nome.contains("nao se aplica") || nome.contains("sem disputa")) return true
        // Modo de disputa informado e diferente (ex.: 4 "Dispensa Com Disputa"): há disputa, mesmo sem prazo publicado.
        if (modoDisputaId != null || nome.isNotEmpty()) return false
        if (tipoInstrumentoCodigo == INSTRUMENTO_ATO_CONTRATACAO_DIRETA) return true
        if (tipoInstrumentoCodigo == INSTRUMENTO_AVISO_CONTRATACAO_DIRETA) return false
        // Sem indicação explícita: dispensa sem data de encerramento de propostas = sem disputa.
        return !hasProposalDeadline
    }
}

/**
 * Funil de uma fonte que lê a janela inteira e tria no aparelho: [read] linhas lidas da API, [candidates] que passaram
 * nos filtros/palavras/relevância, [open] devolvidas (prazo aberto ou não informado; [unknownDeadline] destas sem prazo).
 * [truncated] = o teto de segurança de linhas foi atingido (as mais recentes foram priorizadas).
 */
data class SourceDiagnostics(
    val read: Int,
    val candidates: Int,
    val open: Int,
    val unknownDeadline: Int = 0,
    val truncated: Boolean = false,
    /**
     * A varredura completa da janela ainda está em andamento (ou nunca terminou): [read] reflete só o que já está no
     * cache — resultado PARCIAL; a lista é refeita quando a sincronização terminar.
     */
    val syncing: Boolean = false,
    /** A varredura completa necessária falhou (ex.: limite de consultas/timeout) e o resultado veio só do cache parcial. */
    val syncFailure: String? = null,
) {
    /** "8000 lidas · 34 candidatas · 12 abertas" (+ "(3 sem prazo)" quando houver; + estado da sincronização). */
    val summary: String
        get() = buildString {
            append(read).append(if (truncated) "+ lidas" else " lidas")
            append(" · ").append(candidates).append(if (candidates == 1) " candidata" else " candidatas")
            append(" · ").append(open).append(if (open == 1) " aberta" else " abertas")
            if (unknownDeadline > 0) append(" (").append(unknownDeadline).append(" sem prazo)")
            if (syncing) append(" · sincronização em andamento (parcial)")
            else if (syncFailure != null) append(" · leitura completa não terminou (parcial)")
        }
}

data class OpportunityFilter(
    val query: String = "",
    val portals: Set<Portal> = emptySet(),
    val ufs: Set<String> = emptySet(),
    val segment: Segment? = null,
    val modality: Modality? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val minScore: Int = 0,
    /**
     * Inclui dispensas sem disputa ([Opportunity.noDispute]). Padrão: mostradas (a Busca filtra por modalidade com o chip
     * "Todas · Pregão · Dispensa · Concorrência/Outras").
     */
    val showNoDispute: Boolean = true,
) {
    /**
     * Busca focada (texto digitado ou segmento escolhido): só então a Busca pede notas por IA. A busca geral sem
     * texto usa apenas a heurística (e notas por IA já salvas), sem gastar a cota do provedor.
     */
    val focused: Boolean get() = query.isNotBlank() || segment != null
}

// ---------------------------------------------------------------- Licitação de interesse / análise

data class Tender(
    val id: Long = 0,
    val companyId: Long,
    val opportunityId: String,
    val portal: Portal,
    val number: String,
    val agency: String,
    val objectDescription: String,
    val modality: Modality,
    val segment: Segment,
    val uf: String,
    val city: String,
    val estimatedValue: Double,
    val proposalDeadline: Long,
    val sessionAt: Long,
    val status: TenderStatus = TenderStatus.INTERESSE,
    val editalRegistered: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Cópia privada do PDF do edital (filesDir/editais/{companyId}/{tenderId}.pdf), quando importado. */
    val editalPdfPath: String? = null,
    /** Texto extraído/colado do edital (filesDir/editais/{companyId}/{tenderId}.txt), quando existe. */
    val editalTextPath: String? = null,
    /** Tamanho do texto do edital em caracteres (0 = sem texto). */
    val editalChars: Int = 0,
    /** Páginas do PDF importado; null quando o texto foi colado ou não há PDF. */
    val editalPages: Int? = null,
    /** PDF sem camada de texto (escaneado). Com [hasEditalText] = true, o texto veio do OCR local (conferir trechos). */
    val editalScanned: Boolean = false,
    /** UASG / código da unidade compradora trazido da oportunidade ao marcar interesse (versão 14). */
    val uasg: String? = null,
    /** Situação oficial (suspensa, revogada, anulada...) atualizada pela atualização diária; null = normal. Versão 14. */
    val officialSituation: OfficialSituation? = null,
) {
    /** Há texto real do edital para enviar à IA. */
    val hasEditalText: Boolean get() = editalTextPath != null && editalChars > 0

    /** Licitação cadastrada manualmente (sem oportunidade de portal). */
    val isManual: Boolean get() = opportunityId.startsWith(MANUAL_OPPORTUNITY_PREFIX)

    companion object {
        const val MANUAL_OPPORTUNITY_PREFIX = "MANUAL:"
    }
}

/** Dados informados pelo usuário ao cadastrar uma licitação manualmente (edital real). */
data class ManualTenderDraft(
    val portal: Portal,
    val number: String,
    val agency: String,
    val objectDescription: String,
    val modality: Modality,
    val segment: Segment,
    val uf: String,
    val city: String,
    val estimatedValue: Double,
    val proposalDeadline: Long,
    val sessionAt: Long,
    val editalUrl: String? = null,
) {
    /** Mensagens de validação em pt-BR; vazio = válido. */
    fun validate(): List<String> = buildList {
        if (number.trim().length < 3) add("Informe o número do edital (ex.: 90012/2026).")
        if (agency.trim().length < 3) add("Informe o órgão licitante.")
        if (objectDescription.trim().length < 10) add("Descreva o objeto com pelo menos 10 caracteres.")
        if (uf.trim().length != 2 || !uf.trim().all { it.isLetter() }) add("Informe a UF com 2 letras (ex.: CE).")
        if (city.trim().isEmpty()) add("Informe a cidade.")
        if (estimatedValue.isNaN() || estimatedValue < 0.0) add("Valor estimado inválido.")
        if (proposalDeadline <= 0L) add("Informe o prazo final de envio das propostas.")
        if (sessionAt <= 0L) add("Informe a data da sessão pública.")
        if (proposalDeadline > 0L && sessionAt > 0L && sessionAt < proposalDeadline) add("A sessão pública não pode ocorrer antes do prazo de propostas.")
        val url = editalUrl?.trim().orEmpty()
        if (url.isNotEmpty() && !(url.startsWith("https://") || url.startsWith("http://"))) add("A URL do edital deve começar com http:// ou https://.")
    }

    val isValid: Boolean get() = validate().isEmpty()

    /** Identificador estável por empresa: evita cadastrar o mesmo edital duas vezes. */
    fun opportunityId(): String {
        val normalized = number.trim().uppercase().replace(Regex("\\s+"), "")
        return "${Tender.MANUAL_OPPORTUNITY_PREFIX}${portal.name}:$normalized"
    }
}

/** Origem do texto do edital anexado a uma licitação. */
sealed interface EditalSource {
    /** `content://` do PDF escolhido pelo usuário (a UI já pediu permissão persistente). */
    data class Pdf(val uri: String) : EditalSource

    /** Texto colado/digitado pelo usuário. */
    data class Text(val text: String) : EditalSource

    /** Reconhecimento de texto (OCR no aparelho) sobre o PDF já importado da licitação. */
    data object Ocr : EditalSource

    /**
     * PDF oficial baixado de um portal público (HTTPS; hosts permitidos: PNCP e Compras.gov.br).
     * ZIP é aceito (o primeiro PDF é extraído); HTML é recusado.
     */
    data class Remote(val url: String) : EditalSource
}

/**
 * Número de controle PNCP (`<cnpj 14>-1-<sequencial>/<ano>`) contido no id da oportunidade
 * (`PNCP:<controle>` ou `COMPRAS_GOV:<controle>`). Null para ids legados/manuais/demonstração.
 */
object PncpControlNumbers {
    private val PATTERN = Regex("""^(\d{14})-1-(\d{1,6})/(\d{4})$""")

    fun fromOpportunityId(opportunityId: String): String? {
        val prefix = listOf(Portal.PNCP, Portal.COMPRAS_GOV).firstOrNull { opportunityId.startsWith("${it.name}:") } ?: return null
        val raw = opportunityId.removePrefix("${prefix.name}:").trim()
        return raw.takeIf { PATTERN.matches(it) }
    }
}

/** Número de controle PNCP da licitação (permite baixar o edital oficial); null quando não há. */
val Tender.pncpControlNumber: String? get() = if (isManual) null else PncpControlNumbers.fromOpportunityId(opportunityId)

data class EditalImportResult(
    val chars: Int,
    val pages: Int?,
    /** true = PDF sem camada de texto (escaneado). Com [ocr] = true o texto foi reconhecido no aparelho. */
    val scanned: Boolean,
    val storedPath: String,
    /** true = o texto salvo veio do OCR local (ML Kit); pode conter erros de reconhecimento. */
    val ocr: Boolean = false,
)

/** Progresso da importação do edital (cópia, extração e OCR página a página). */
data class EditalImportProgress(
    val tenderId: Long,
    val stage: Stage,
    /** Páginas já reconhecidas (OCR); 0 nas demais etapas. */
    val page: Int = 0,
    /** Total de páginas a reconhecer (OCR); 0 quando desconhecido. */
    val totalPages: Int = 0,
) {
    enum class Stage(val label: String) {
        BAIXANDO("Baixando o edital oficial"),
        COPIANDO("Copiando o PDF"),
        EXTRAINDO("Extraindo o texto"),
        OCR("Reconhecendo texto"),
    }
}

data class ExtractedEdital(
    val objectDescription: String,
    val agency: String,
    val portal: String,
    val number: String,
    val modality: String,
    val estimatedValue: Double,
    val proposalDeadline: Long,
    val openingAt: Long,
    val sessionAt: Long,
    val installationDeadline: String,
    val sla: String,
    val technicalRequirements: List<String>,
    val requiredDocuments: List<DocumentType>,
    val guarantees: List<String>,
    val penalties: List<String>,
    val attestationRequirements: List<String>,
    val certificationRequirements: List<String>,
)

data class FitScore(
    val overall: Int,
    val technical: Int,
    val documentary: Int,
    val financial: Int,
    val estimatedMarginPct: Double,
    val operationalRisk: RiskLevel,
    val documentaryRisk: RiskLevel,
    val contractualRisk: RiskLevel,
    /** 0..100 — quão confortável é o prazo. */
    val deadlineFit: Int,
    /** Média histórica de concorrentes em objetos similares. */
    val historicalCompetitors: Int,
    /** 0..100. */
    val geographicFit: Int,
    /** Investimento estimado necessário (BRL). */
    val investmentNeeded: Double,
)

data class PriceRange(val min: Double, val suggested: Double, val max: Double)

data class ChecklistItem(
    val title: String,
    val done: Boolean,
    val critical: Boolean = false,
    val relatedDocument: DocumentType? = null,
)

data class TenderAnalysis(
    val tenderId: Long,
    val providerName: String,
    val generatedAt: Long,
    val summary: String,
    val extracted: ExtractedEdital,
    val fit: FitScore,
    val recommendation: Recommendation,
    val justification: String,
    val criticalPoints: List<String>,
    val priceRange: PriceRange,
    val checklist: List<ChecklistItem>,
    /**
     * Nomes dos campos efetivamente preenchidos pelo modelo de IA (ex.: "summary", "requiredDocuments").
     * Vazio = análise 100% heurística local (provedor de demonstração ou campos todos rejeitados).
     */
    val aiFields: Set<String> = emptySet(),
) {
    /** true quando nenhum campo veio de um modelo de IA: a UI deve rotular como heurística. */
    val heuristicOnly: Boolean get() = aiFields.isEmpty()
}

// ---------------------------------------------------------------- Documentos

data class CompanyDocument(
    val id: Long = 0,
    val companyId: Long,
    val type: DocumentType,
    val title: String,
    val issuer: String? = null,
    val issuedAt: Long? = null,
    val expiresAt: Long? = null,
    /** content:// ou caminho local do anexo. */
    val attachmentUri: String? = null,
    val tags: List<String> = emptyList(),
    val notes: String = "",
    val createdAt: Long = 0,
) {
    fun status(now: Long, soonDays: Int = 30): DocumentStatus = when {
        attachmentUri == null && issuedAt == null -> DocumentStatus.AUSENTE
        expiresAt == null -> DocumentStatus.VALIDO
        expiresAt < now -> DocumentStatus.VENCIDO
        expiresAt - now <= soonDays * DAY_MS -> DocumentStatus.VENCE_EM_BREVE
        else -> DocumentStatus.VALIDO
    }

    fun daysToExpire(now: Long): Long? = expiresAt?.let { (it - now) / DAY_MS }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

// ---------------------------------------------------------------- Proposta

data class ProposalItem(
    val description: String,
    val unit: String,
    val quantity: Double,
    val unitPrice: Double,
    /** Número do item no edital (PNCP/Compras.gov.br); null = item livre (sem vínculo com o edital). */
    val itemNumber: Int? = null,
    val brand: String = "",
    val manufacturer: String = "",
    val model: String = "",
    /** Valor unitário estimado pelo órgão (teto de referência); null = sigiloso/desconhecido. */
    val estimatedUnitPrice: Double? = null,
    /** Orçamento sigiloso no edital: o preço precisa ser definido pelo usuário. */
    val confidentialBudget: Boolean = false,
) {
    /** Total do item arredondado a centavos (evita diferenças de ponto flutuante no total geral). */
    val total: Double
        get() {
            val raw = quantity * unitPrice
            return if (raw.isFinite()) java.math.BigDecimal.valueOf(raw).setScale(2, java.math.RoundingMode.HALF_UP).toDouble() else 0.0
        }
}

data class Proposal(
    val id: Long = 0,
    val tenderId: Long,
    val companyId: Long,
    val version: Int = 1,
    val items: List<ProposalItem>,
    val deliveryDays: Int,
    val validityDays: Int = 60,
    val notes: String = "",
    val status: ProposalStatus = ProposalStatus.RASCUNHO,
    val pdfPath: String? = null,
    val createdBy: String,
    val createdAt: Long = 0,
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
    val rejectionReason: String? = null,
) {
    val totalValue: Double
        get() = items.fold(java.math.BigDecimal.ZERO) { acc, i -> acc + java.math.BigDecimal.valueOf(i.total) }.toDouble()

    /** Itens ainda sem preço (ex.: orçamento sigiloso marcado "definir"). */
    val pendingPriceItems: List<ProposalItem> get() = items.filter { !(it.unitPrice > 0.0) }
}

// ---------------------------------------------------------------- Portais

data class ConnectorCapabilities(
    val supportsWebView: Boolean,
    val supportsOfficialApi: Boolean,
    val supportsBrowserAutomation: Boolean,
    val supportsPersistentSession: Boolean,
    val requiresMfa: Boolean,
    val mayShowCaptcha: Boolean,
    val isMock: Boolean,
    val limitations: List<String>,
)

data class PortalSession(
    val id: Long = 0,
    val companyId: Long,
    val portal: Portal,
    /** Vazio no login manual: o app não coleta nem guarda usuário/senha. */
    val username: String = "",
    val status: PortalConnectionStatus,
    val lastLoginAt: Long? = null,
    /** Chave do cookie/session store isolado (empresa + portal). */
    val sessionKey: String,
)

// ---------------------------------------------------------------- Pregão ao vivo / robô

data class BidRule(
    val mode: RobotMode = RobotMode.SUPERVISIONADO,
    val strategy: BidStrategy = BidStrategy.CONSERVADORA,
    val initialPrice: Double,
    /** Piso: o robô NUNCA envia lance abaixo deste valor. */
    val floorPrice: Double,
    /** Custo total estimado — base do cálculo de margem. */
    val costPrice: Double,
    /** Redução absoluta por lance (BRL). */
    val reductionValue: Double,
    val minMarginPct: Double,
    /** Perda máxima aceitável em BRL abaixo do custo (0 = não aceita perda). */
    val lossLimit: Double = 0.0,
    val minIntervalSeconds: Int = 5,
    /** Pede autorização humana quando o próximo lance ficar a menos de X% do piso. */
    val authorizationThresholdPct: Double = 5.0,
    /** Sempre true no MVP: nenhum lance real é enviado. */
    val simulation: Boolean = true,
) {
    fun marginPct(price: Double): Double =
        if (price <= 0.0) 0.0 else (price - costPrice) / price * 100.0
}

data class BidAuthorization(
    val id: String,
    val sessionId: String,
    val proposedValue: Double,
    val reason: String,
    val requestedAt: Long,
)

data class LiveSession(
    val id: String,
    val companyId: Long,
    val tenderId: Long? = null,
    val portal: Portal,
    val tenderNumber: String,
    val agency: String,
    val itemLabel: String,
    val objectDescription: String,
    val status: LiveStatus,
    val robotStatus: RobotStatus,
    /** Nossa posição (1 = vencendo). 0 = sem lance. */
    val position: Int,
    val competitors: Int,
    val ourLastBid: Double?,
    val bestBid: Double?,
    val rule: BidRule,
    val remainingSeconds: Int?,
    val captchaPending: Boolean = false,
    val captchaSince: Long? = null,
    val pendingAuthorization: BidAuthorization? = null,
    val unreadMessages: Int = 0,
    val lastError: String? = null,
    val startedAt: Long,
    val updatedAt: Long,
    /** Cronômetro do modo assistido em contagem (estado de runtime; não persiste). */
    val timerRunning: Boolean = false,
) {
    /** Margem no nosso último lance (ou no preço inicial se ainda não houve lance). */
    val currentMarginPct: Double get() = rule.marginPct(ourLastBid ?: rule.initialPrice)
    val isWinning: Boolean get() = position == 1
    val robotRunning: Boolean
        get() = robotStatus == RobotStatus.ATIVO || robotStatus == RobotStatus.AGUARDANDO_AUTORIZACAO
    val isOpen: Boolean get() = status != LiveStatus.ENCERRADA && status != LiveStatus.ERRO
}

data class LiveSessionSpec(
    val companyId: Long,
    val tenderId: Long? = null,
    val portal: Portal,
    val tenderNumber: String,
    val agency: String,
    val itemLabel: String,
    val objectDescription: String,
    val rule: BidRule,
    val competitors: Int = 4,
)

data class BidEvent(
    val id: Long = 0,
    val sessionId: String,
    val timestamp: Long,
    val type: BidEventType,
    val value: Double? = null,
    /** "Robô", nome do usuário, "Concorrente 2", "Portal", "Sistema"... */
    val actor: String,
    val description: String,
)

sealed interface BidResult {
    data class Accepted(val value: Double, val position: Int) : BidResult
    data class Rejected(val reason: String) : BidResult
}

// ---------------------------------------------------------------- Mensagens

data class AuctioneerMessage(
    val id: Long = 0,
    val companyId: Long,
    val sessionId: String? = null,
    val portal: Portal,
    val tenderNumber: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val read: Boolean = false,
    val urgent: Boolean = false,
    val responseDeadline: Long? = null,
    val aiSummary: String? = null,
    val suggestedReply: String? = null,
    val replyDraft: String? = null,
    val replyStatus: ReplyStatus = ReplyStatus.NENHUMA,
    val repliedAt: Long? = null,
)

// ---------------------------------------------------------------- Notificações

data class AppNotification(
    val id: Long = 0,
    val companyId: Long?,
    val category: NotificationCategory,
    val title: String,
    val body: String,
    val createdAt: Long,
    val read: Boolean = false,
    val critical: Boolean = false,
    /** Rota de navegação interna ao tocar. */
    val route: String? = null,
    val sessionId: String? = null,
)

// ---------------------------------------------------------------- Auditoria

data class AuditEvent(
    val id: Long = 0,
    val timestamp: Long,
    val user: String,
    val companyId: Long?,
    val companyName: String,
    val portal: String? = null,
    val tenderNumber: String? = null,
    val item: String? = null,
    val action: AuditAction,
    val previousValue: String? = null,
    val newValue: String? = null,
    val reason: String? = null,
    val origin: AuditOrigin,
    val result: AuditResult,
    val details: String = "",
    /** Hash do evento anterior na cadeia ("" no primeiro evento encadeado ou em eventos legados). */
    val prevHash: String = "",
    /** SHA-256 (hex) de `prevHash` + campos essenciais; "" em eventos anteriores ao encadeamento. */
    val hash: String = "",
)

/** Resultado da verificação da cadeia de hashes da auditoria. */
data class IntegrityReport(
    /** Total de eventos na trilha. */
    val total: Int,
    /** Eventos encadeados cujo hash e vínculo com o anterior conferiram. */
    val verified: Int,
    /** id do primeiro evento cujo hash/encadeamento não confere; null = cadeia íntegra. */
    val firstBroken: Long?,
    /** Eventos anteriores ao encadeamento (sem hash), não verificáveis. */
    val unhashed: Int = 0,
) {
    val ok: Boolean get() = firstBroken == null
}

// ---------------------------------------------------------------- Concorrência (dados públicos / internos)

data class CompetitionRecord(
    val id: Long = 0,
    val companyId: Long,
    val portal: Portal,
    val tenderNumber: String,
    val agency: String,
    val segment: Segment,
    val objectSummary: String,
    val date: Long,
    val competitors: Int,
    val estimatedValue: Double,
    val closingValue: Double,
    val ourFinalBid: Double,
    val won: Boolean,
    val ourMarginPct: Double,
    val bidsCount: Int,
    /** Resumo do comportamento observado (público). */
    val behavior: String,
)

// ---------------------------------------------------------------- IA / configurações

data class AiConfig(
    val provider: AiProviderType,
    val model: String,
    val baseUrl: String,
    /** A chave nunca trafega no modelo — apenas a indicação de que existe no Keystore. */
    val hasApiKey: Boolean,
    val authMode: AiAuthMode = AiAuthMode.API_KEY,
    /** E-mail da conta autorizada (só exibição). null = não autorizado. O token nunca sai do cofre. */
    val oauthAccount: String? = null,
    /** ID do projeto Google Cloud para `x-goog-user-project` (opcional). */
    val cloudProject: String? = null,
    /**
     * true = esta configuração (modelo, URL, credencial) pertence à empresa ativa;
     * false = é o "padrão do aparelho" (companyId 0), usado por toda empresa sem configuração própria.
     */
    val companyScoped: Boolean = false,
) {
    /** Há autorização OAuth registrada (conta conhecida + token no cofre). */
    val hasOAuth: Boolean get() = oauthAccount != null

    /** Credencial utilizável no modo escolhido. */
    val isConfigured: Boolean
        get() = provider == AiProviderType.MOCK || when (authMode) {
            AiAuthMode.API_KEY -> hasApiKey
            AiAuthMode.OAUTH -> hasOAuth
        }
}

data class AppSettings(
    /** Intervalo de repetição do alerta de CAPTCHA em minutos: 1, 3, 5, 10, 15 ou 0 (desativado). */
    val captchaRepeatMinutes: Int = 3,
    val captchaVibrate: Boolean = true,
    val hapticFeedback: Boolean = true,
    val pushEnabled: Boolean = true,
    val biometricLock: Boolean = false,
    val sessionTimeoutMinutes: Int = 15,
    val screenshotProtection: Boolean = false,
    val rememberSession: Boolean = true,
    val activeAiProvider: AiProviderType = AiProviderType.MOCK,
    val showBottomBar: Boolean = true,
    /** Intervalo (min) do "Manter sessão ativa" dos portais: 5, 8, 12 ou 15. */
    val portalKeepAliveMinutes: Int = 8,
    /**
     * Portais com "Manter sessão ativa" ligado (opt-in), por empresa: chaves de [portalKeepAliveKey]
     * ("<companyId>:<PORTAL>"). Padrão vazio = desligado.
     */
    val portalKeepAlive: Set<String> = emptySet(),
    /**
     * "Entrar automaticamente com certificado digital" (opt-in), por empresa+portal: mesmas chaves de
     * [portalKeepAliveKey]. Padrão vazio = desligado.
     */
    val portalAutoCertLogin: Set<String> = emptySet(),
    /** "Mostrar dias anteriores" na Busca e nos Resultados do Radar (padrão desmarcado). */
    val showPreviousDays: Boolean = false,
    /** Último filtro de período escolhido na Busca/Radar (padrão: qualquer data). */
    val searchPeriod: PeriodFilter = PeriodFilter(),
    /** Última abertura da Busca (selo "Nova": o que entrou no cache depois dela). */
    val lastSearchOpenedAt: Long? = null,
) {
    fun isPortalKeepAliveOn(companyId: Long, portal: Portal): Boolean = portalKeepAliveKey(companyId, portal) in portalKeepAlive

    fun isAutoCertLoginOn(companyId: Long, portal: Portal): Boolean = portalKeepAliveKey(companyId, portal) in portalAutoCertLogin

    companion object {
        val CAPTCHA_REPEAT_OPTIONS = listOf(1, 3, 5, 10, 15, 0)
        val PORTAL_KEEP_ALIVE_OPTIONS = listOf(5, 8, 12, 15)
        fun portalKeepAliveKey(companyId: Long, portal: Portal) = "$companyId:${portal.name}"
    }
}
