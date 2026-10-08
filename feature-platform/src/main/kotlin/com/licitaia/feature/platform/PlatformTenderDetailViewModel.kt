package com.licitaia.feature.platform

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.api.TenderAnalysisRequest
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.AnaliseLocalRequest
import com.licitaia.core.platform.net.PlatformFile
import com.licitaia.core.platform.net.PlatformItem
import com.licitaia.core.platform.net.PropostaCreateRequest
import com.licitaia.core.platform.net.ResultadoDto
import com.licitaia.core.platform.net.ResultadoRequest
import com.licitaia.core.platform.net.RoboConfigDto
import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.platform.session.PlatformIdentity
import com.licitaia.core.platform.session.PlatformSession
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.TenderStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

data class PlatformDetailUi(
    val loading: Boolean = true,
    val tender: TenderDto? = null,
    val itens: List<PlatformItem> = emptyList(),
    val arquivos: List<PlatformFile> = emptyList(),
    val error: String? = null,
    /** true quando o detalhe veio do espelho local (sem rede) e não do servidor. */
    val fromCache: Boolean = false,
    /** Ação de escrita (favoritar/arquivar/ocultar) em andamento. */
    val acting: Boolean = false,
    /** Análise por IA ON-DEVICE em andamento. */
    val analyzing: Boolean = false,
    val analysisStatus: String? = null,
    /** Geração de proposta ON-DEVICE em andamento + resumo da última gerada (conteúdo fica no aparelho). */
    val generatingProposal: Boolean = false,
    val proposalSummary: String? = null,
    /** Robô de lance (leitura). */
    val roboConfig: RoboConfigDto? = null,
    val roboLances: Int = 0,
    /** Resultado registrado (null = ainda não registrado). */
    val resultado: ResultadoDto? = null,
)

@HiltViewModel
class PlatformTenderDetailViewModel @Inject constructor(
    private val repository: PlatformRepository,
    private val gateway: AiGateway,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val id: String = savedStateHandle.get<String>("platformId").orEmpty()

    private val _state = MutableStateFlow(PlatformDetailUi())
    val state: StateFlow<PlatformDetailUi> = _state.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = repository.tenderDetail(id)
            result.fold(
                onSuccess = { dto ->
                    _state.update { PlatformDetailUi(loading = false, tender = dto) }
                    val itens = repository.tenderItens(id).getOrDefault(emptyList())
                    val arquivos = repository.tenderArquivos(id).getOrDefault(emptyList())
                    val robo = repository.roboConfig(id).getOrNull()
                    val lances = repository.roboHistoricoCount(id).getOrDefault(0)
                    val resultado = repository.resultado(id).getOrNull()
                    _state.update {
                        it.copy(itens = itens, arquivos = arquivos, roboConfig = robo, roboLances = lances, resultado = resultado)
                    }
                },
                onFailure = { e ->
                    val cached = repository.tenderFromMirror(id)
                    if (cached != null) {
                        _state.update { PlatformDetailUi(loading = false, tender = cached.toDto(), fromCache = true) }
                    } else {
                        _state.update { PlatformDetailUi(loading = false, error = e.message ?: "Não foi possível carregar a licitação.") }
                    }
                },
            )
        }
    }

    /**
     * Análise do edital ON-DEVICE: usa o motor de IA local (chave do aparelho; heurística se não houver chave),
     * sobre o texto do edital vindo da VPS. Depois grava o resultado textual na VPS (sem subir chave).
     */
    fun analyze() {
        val dto = _state.value.tender ?: return
        if (_state.value.analyzing) return
        val company = currentCompany()
        if (company == null) { viewModelScope.launch { _events.send("Entre na plataforma para analisar.") }; return }
        _state.update { it.copy(analyzing = true, analysisStatus = "Analisando no aparelho…") }
        viewModelScope.launch {
            val analysis = runCatching {
                withContext(Dispatchers.Default) {
                    val tender = dto.toDomainTender(company.id)
                    val request = TenderAnalysisRequest(
                        tender = tender, company = company, documents = emptyList(),
                        editalText = dto.editalTexto?.takeIf { it.isNotBlank() }, now = System.currentTimeMillis(),
                    )
                    gateway.current().analyzeTender(request)
                }
            }.getOrElse { e ->
                _state.update { it.copy(analyzing = false, analysisStatus = null) }
                _events.send(e.message ?: "Não foi possível analisar no aparelho.")
                return@launch
            }
            // Reflete no detalhe exibido.
            val req = analysis.toAnaliseLocal()
            _state.update {
                it.copy(
                    analyzing = false, analysisStatus = null,
                    tender = it.tender?.copy(
                        veredito = req.veredito, scoreRelevancia = req.scoreRelevancia, scoreRisco = req.scoreRisco,
                        editalResumoIA = req.editalResumoIA, precoSugeridoIA = req.precoSugeridoIA, margemEstimadaIA = req.margemEstimadaIA,
                    ),
                )
            }
            val heur = analysis.heuristicOnly
            _events.send(if (heur) "Análise local (heurística). Configure uma chave de IA para uma análise mais rica." else "Análise concluída no aparelho.")
            // Grava na VPS para a equipe ver (best-effort; não quebra se 404/offline).
            repository.saveAnaliseLocal(id, req)
        }
    }

    /** Gera a proposta ON-DEVICE (chave local) e grava os metadados na VPS; o conteúdo fica no aparelho. */
    fun gerarProposta() {
        val dto = _state.value.tender ?: return
        if (_state.value.generatingProposal) return
        val company = currentCompany()
        if (company == null) { viewModelScope.launch { _events.send("Entre na plataforma para gerar a proposta.") }; return }
        _state.update { it.copy(generatingProposal = true) }
        viewModelScope.launch {
            val draft = runCatching {
                withContext(Dispatchers.Default) {
                    gateway.current().draftProposal(dto.toDomainTender(company.id), null, company)
                }
            }.getOrElse { e ->
                _state.update { it.copy(generatingProposal = false) }
                _events.send(e.message ?: "Não foi possível gerar a proposta no aparelho.")
                return@launch
            }
            val total = draft.items.sumOf { it.total }
            val resumo = "Proposta gerada no aparelho: ${draft.items.size} item(ns) · total ${PlatformFmt.money(total)} · " +
                "entrega ${draft.deliveryDays}d · validade ${draft.validityDays}d."
            _state.update { it.copy(generatingProposal = false, proposalSummary = resumo) }
            _events.send("Proposta gerada no aparelho.")
            // Grava metadados na VPS (conteúdo completo fica no aparelho por ora).
            repository.criarProposta(PropostaCreateRequest(licitacaoId = id, valorTotal = total))
        }
    }

    fun toggleFavorita() = act {
        repository.toggleFavorita(id).map { fav ->
            _state.update { s -> s.copy(tender = s.tender?.copy(favorita = fav)) }
            if (fav) "Marcada como interesse" else "Removida dos interesses"
        }
    }

    fun toggleArquivar() = act {
        repository.toggleArquivar(id).map { status ->
            _state.update { s -> s.copy(tender = s.tender?.copy(status = status)) }
            if (status.equals("arquivada", true)) "Licitação arquivada" else "Licitação desarquivada"
        }
    }

    fun toggleOcultar() = act {
        repository.toggleOcultar(id).map { status ->
            _state.update { s -> s.copy(tender = s.tender?.copy(status = status)) }
            if (status.equals("oculta", true)) "Licitação ocultada" else "Licitação reexibida"
        }
    }

    /** Registra o resultado do pregão e recarrega. */
    fun registrarResultado(resultado: String) {
        if (_state.value.acting) return
        _state.update { it.copy(acting = true) }
        viewModelScope.launch {
            val r = repository.registrarResultado(id, ResultadoRequest(resultado = resultado))
            r.fold(
                onSuccess = {
                    _events.send("Resultado registrado: $resultado")
                    val novo = repository.resultado(id).getOrNull()
                    _state.update { it.copy(acting = false, resultado = novo) }
                },
                onFailure = { _events.send(it.message ?: "Não foi possível registrar o resultado."); _state.update { s -> s.copy(acting = false) } },
            )
        }
    }

    private fun currentCompany(): Company? =
        (repository.session.value as? PlatformSession.SignedIn)?.user?.let { PlatformIdentity.session(it).activeCompany }

    private fun act(block: suspend () -> Result<String>) {
        if (_state.value.acting) return
        _state.update { it.copy(acting = true) }
        viewModelScope.launch {
            block().fold(
                onSuccess = { _events.send(it) },
                onFailure = { _events.send(it.message ?: "Não foi possível concluir a ação.") },
            )
            _state.update { it.copy(acting = false) }
        }
    }
}

/** Mapeia o DTO de fio para um [Tender] de domínio (para o motor de IA on-device). */
private fun TenderDto.toDomainTender(companyId: Long): Tender = Tender(
    id = 0,
    companyId = companyId,
    opportunityId = id,
    portal = mapPortal(portal),
    number = numero,
    agency = orgao,
    objectDescription = objeto,
    modality = mapModality(modalidade),
    segment = com.licitaia.domain.model.Segment.PERSONALIZADO,
    uf = estado.orEmpty(),
    city = cidade.orEmpty(),
    estimatedValue = valorEstimado?.toDoubleOrNull() ?: 0.0,
    proposalDeadline = parseIso(dataEncerramento),
    sessionAt = parseIso(dataAbertura),
    status = TenderStatus.EM_ANALISE,
    editalChars = editalTexto?.length ?: 0,
    editalTextPath = if (!editalTexto.isNullOrBlank()) "vps" else null,
    uasg = uasg,
)

private fun TenderAnalysis.toAnaliseLocal(): AnaliseLocalRequest {
    val risco = maxOf(fit.operationalRisk.ordinal, fit.contractualRisk.ordinal, fit.documentaryRisk.ordinal)
    return AnaliseLocalRequest(
        veredito = recommendation.name,
        scoreRelevancia = fit.overall,
        scoreRisco = (risco * 30 + 10).coerceAtMost(100),
        editalResumoIA = summary,
        precoSugeridoIA = priceRange.suggested.toString(),
        margemEstimadaIA = fit.estimatedMarginPct.toString(),
    )
}

private fun mapPortal(p: String?): Portal {
    val s = p?.lowercase().orEmpty()
    return when {
        s.contains("compras") || s.contains("comprasnet") -> Portal.COMPRAS_GOV
        s.contains("pncp") -> Portal.PNCP
        s.contains("bll") -> Portal.BLL
        s.contains("licitanet") -> Portal.LICITANET
        else -> Portal.PNCP
    }
}

private fun mapModality(m: String?): Modality {
    val s = m?.lowercase().orEmpty()
    return when {
        s.contains("preg") -> Modality.PREGAO_ELETRONICO
        s.contains("dispensa") -> Modality.DISPENSA_ELETRONICA
        s.contains("concorr") -> Modality.CONCORRENCIA
        s.contains("credenc") -> Modality.CREDENCIAMENTO
        else -> Modality.PREGAO_ELETRONICO
    }
}

private fun parseIso(iso: String?): Long =
    if (iso.isNullOrBlank()) 0L else runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

/** Reconstrói um [TenderDto] mínimo a partir do espelho local (campos que a tela de detalhe usa). */
private fun com.licitaia.core.platform.db.PlatformTenderEntity.toDto(): TenderDto = TenderDto(
    id = id,
    numero = numero,
    orgao = orgao,
    uasg = null,
    objeto = objeto,
    modalidade = modalidade,
    valorEstimado = valorEstimado,
    dataAbertura = dataAbertura,
    dataEncerramento = dataEncerramento,
    portal = portal,
    portalUrl = portalUrl,
    urlProposta = urlProposta,
    estado = estado,
    cidade = cidade,
    fase = fase,
    status = status,
    favorita = favorita,
    scoreRelevancia = scoreRelevancia,
    updatedAt = updatedAt,
    empresaId = empresaId,
)

/** Formatação monetária compacta para mensagens (a UI usa PlatformFormat). */
private object PlatformFmt {
    fun money(v: Double): String = "R$ " + String.format(java.util.Locale("pt", "BR"), "%,.2f", v)
}
