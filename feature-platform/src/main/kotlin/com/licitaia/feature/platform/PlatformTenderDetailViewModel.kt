package com.licitaia.feature.platform

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.api.TenderAnalysisRequest
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.AnaliseLocalRequest
import com.licitaia.core.platform.net.MensagemDto
import com.licitaia.core.platform.net.PisoItemRequest
import com.licitaia.core.platform.net.PlatformFile
import com.licitaia.core.platform.net.PlatformItem
import com.licitaia.core.platform.net.ProntidaoDto
import com.licitaia.core.platform.net.PropostaCreateRequest
import com.licitaia.core.platform.net.PropostaDto
import com.licitaia.core.platform.net.ResultadoDto
import com.licitaia.core.platform.net.ResultadoRequest
import com.licitaia.core.platform.net.RoboConfigDto
import com.licitaia.core.platform.net.RoboConfigUpdateRequest
import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.platform.session.PlatformIdentity
import com.licitaia.core.platform.session.PlatformSession
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalDeclarations
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.portal.BidRobotConfig
import com.licitaia.domain.portal.BidRobotMode
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalRobotPlan
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.portal.PortalTenderMatching
import com.licitaia.domain.portal.ProposalAuthorization
import com.licitaia.domain.portal.ProposalItemPlan
import com.licitaia.feature.live.automation.PortalRobotEngine
import com.licitaia.feature.live.automation.RobotKind
import com.licitaia.feature.live.automation.RobotRun
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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
    /** Conteúdo (texto/planilha) da última proposta gerada no aparelho, para a tela "Ver proposta". */
    val proposalContent: String? = null,
    /** Robô de lance (leitura + arme via VPS; execução on-device é próxima etapa). */
    val roboConfig: RoboConfigDto? = null,
    val roboLances: Int = 0,
    val prontidao: ProntidaoDto? = null,
    val roboBusy: Boolean = false,
    /** Resultado registrado (null = ainda não registrado). */
    val resultado: ResultadoDto? = null,
    /** Propostas da licitação (metadados na VPS). */
    val propostas: List<PropostaDto> = emptyList(),
    /** Mensagens desta licitação + envio. */
    val mensagens: List<MensagemDto> = emptyList(),
    val msgInput: String = "",
    val sendingMsg: Boolean = false,
    /** Itens candidatos do robô de PROPOSTA; não-nulo = confirmação "Soltar robô — cadastrar proposta" aberta. */
    val propostaRoboItens: List<ProposalItemPlan>? = null,
    val propostaRoboBusy: Boolean = false,
)

@HiltViewModel
class PlatformTenderDetailViewModel @Inject constructor(
    private val repository: PlatformRepository,
    private val gateway: AiGateway,
    private val roboEngine: PortalRobotEngine,
    private val roboRepo: PortalRobotRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val id: String = savedStateHandle.get<String>("platformId").orEmpty()

    private val _state = MutableStateFlow(PlatformDetailUi())
    val state: StateFlow<PlatformDetailUi> = _state.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Chave do robô (uasg-num-ano) desta licitação, quando reconhecível; usada para observar a execução on-device. */
    private val _roboKey = MutableStateFlow<String?>(null)

    /** Execução on-device do robô de lance para esta licitação (observada do motor local — SEM alterá-lo). */
    val roboRun: StateFlow<RobotRun?> = combine(roboEngine.runs, _roboKey) { runs, key ->
        if (key == null) null else runs.values.firstOrNull { it.tenderKey == key && it.kind == RobotKind.LANCE && it.active }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Execução do robô de PROPOSTA desta licitação (a mais recente — continua visível depois de terminar). */
    val propostaRun: StateFlow<RobotRun?> = combine(roboEngine.runs, _roboKey) { runs, key ->
        if (key == null) null else runs.values.filter { it.tenderKey == key && it.kind == RobotKind.PROPOSTA }.maxByOrNull { it.startedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _abrirPortal = Channel<Unit>(Channel.BUFFERED)
    /** Pede à tela que abra o portal (Compras.gov.br) para o usuário ASSISTIR o robô trabalhando. */
    val abrirPortal = _abrirPortal.receiveAsFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = repository.tenderDetail(id)
            result.fold(
                onSuccess = { dto ->
                    _state.update { PlatformDetailUi(loading = false, tender = dto) }
                    _roboKey.value = roboKeyOf(dto)
                    val itens = repository.tenderItens(id).getOrDefault(emptyList())
                    val arquivos = repository.tenderArquivos(id).getOrDefault(emptyList())
                    val robo = repository.roboConfig(id).getOrNull()
                    val lances = repository.roboHistoricoCount(id).getOrDefault(0)
                    val prontidao = repository.roboProntidao(id).getOrNull()
                    val resultado = repository.resultado(id).getOrNull()
                    val propostas = repository.propostas(id).getOrDefault(emptyList())
                    val mensagens = repository.mensagens(id).getOrDefault(emptyList())
                    val conteudo = repository.propostaConteudo(id)
                    _state.update {
                        it.copy(
                            itens = itens, arquivos = arquivos, roboConfig = robo, roboLances = lances, prontidao = prontidao,
                            resultado = resultado, propostas = propostas, mensagens = mensagens, proposalContent = conteudo,
                        )
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
            // Total robusto: soma os itens; se a IA não precificou (edital sigiloso), tenta o valor de referência
            // do órgão (estimatedUnitPrice) e, por fim, o valor estimado da licitação. Nunca fica 0 se há valores.
            val totalItens = draft.items.sumOf { it.total }
            val totalRef = draft.items.sumOf { (it.estimatedUnitPrice ?: 0.0) * it.quantity }
            val total = when {
                totalItens > 0.0 -> totalItens
                totalRef > 0.0 -> totalRef
                else -> dto.valorEstimado?.toDoubleOrNull() ?: 0.0
            }
            val conteudo = buildProposalContent(draft, total)
            repository.cacheProposta(id, conteudo)
            val valorTxt = if (total > 0) PlatformFmt.money(total) else "a definir (edital sem valor)"
            val resumo = "Proposta gerada no aparelho: ${draft.items.size} item(ns) · total $valorTxt · " +
                "entrega ${draft.deliveryDays}d · validade ${draft.validityDays}d."
            _state.update { it.copy(generatingProposal = false, proposalSummary = resumo, proposalContent = conteudo) }
            _events.send("Proposta gerada no aparelho.")
            // Grava metadados na VPS (conteúdo completo fica no aparelho por ora) e recarrega a lista.
            repository.criarProposta(PropostaCreateRequest(licitacaoId = id, valorTotal = total))
            val novas = repository.propostas(id).getOrDefault(_state.value.propostas)
            _state.update { it.copy(propostas = novas) }
        }
    }

    fun aprovarProposta(pid: String) = propostaOp("Proposta aprovada (revisada).") { repository.updateProposta(pid, null, "revisada") }
    fun editarProposta(pid: String, valorTotal: Double?, status: String?) = propostaOp("Proposta atualizada.") { repository.updateProposta(pid, valorTotal, status) }
    fun excluirProposta(pid: String) = propostaOp("Proposta excluída.") { repository.deleteProposta(pid) }

    private fun propostaOp(okMsg: String, block: suspend () -> Result<Unit>) {
        if (_state.value.acting) return
        _state.update { it.copy(acting = true) }
        viewModelScope.launch {
            val r = block()
            if (r.isSuccess) {
                _events.send(okMsg)
                val novas = repository.propostas(id).getOrDefault(_state.value.propostas)
                _state.update { it.copy(acting = false, propostas = novas) }
            } else {
                _events.send(r.exceptionOrNull()?.message ?: "Não foi possível concluir.")
                _state.update { it.copy(acting = false) }
            }
        }
    }

    /**
     * Arma/edita o robô na VPS. [auto]=true (lance REAL) exige confirmação explícita na UI e envia
     * confirmarAuto=true (trava do backend). Sem [auto], arma em dry_run (decide e loga, NÃO envia).
     * O PISO (valorMinimo) é sempre enviado. A disputa on-device (motor local) é a próxima etapa.
     */
    fun armarRobo(piso: Double, decremento: Double, estrategia: String?, intervalo: Int?, pisosItens: List<PisoItemRequest>, auto: Boolean) = roboOp(
        if (auto) "Robô armado em modo AUTO (lance real)." else "Robô armado em modo de teste (dry_run).",
    ) {
        repository.armarRobo(
            id,
            RoboConfigUpdateRequest(
                estrategia = estrategia, valorMinimo = piso, decremento = decremento, intervaloSegundos = intervalo,
                itemAlvo = null, modoExecucao = if (auto) "auto" else "dry_run", confirmarAuto = if (auto) true else null,
                pisosItens = pisosItens.ifEmpty { null },
            ),
        )
    }

    fun participarRobo() = roboOp("Participação registrada (robô em dry_run).") { repository.roboParticipar(id) }
    fun prepararRobo() = roboOp("Preparação disparada.") { repository.roboPreparar(id) }

    /**
     * BRIDGE F4-B: roda o robô de lance NO APARELHO reusando o PortalRobotEngine (SEM alterá-lo).
     * - Importa a licitação para os repos LOCAIS sob a IDENTIDADE SINTÉTICA da plataforma (isolada do modo local).
     * - Arma SEMPRE em dry_run (BidRobotMode.MANUAL): o motor lê a sala e SUGERE, nunca envia sozinho.
     * - Respeita a trava "um robô por licitação" (VPS /ativas + runs do motor) e a prontidão.
     * - Execução AUTO (lance real) NÃO é liberada por esta ponte nesta etapa (sempre dry_run).
     */
    fun iniciarRoboLocal() {
        if (_state.value.roboBusy) return
        val dto = _state.value.tender ?: return
        val company = currentCompany()
        if (company == null) { viewModelScope.launch { _events.send("Entre na plataforma.") }; return }
        val key = roboKeyOf(dto)
        if (key == null) { viewModelScope.launch { _events.send("Robô on-device só para Comprasnet com UASG/número/ano reconhecíveis nesta licitação.") }; return }
        val cfg = _state.value.roboConfig ?: repository.roboConfigCacheada(id)
        val piso = cfg?.valorMinimo?.takeIf { it > 0 }
        if (piso == null) { viewModelScope.launch { _events.send("Defina o piso (valor mínimo) armando o robô antes de iniciar no aparelho.") }; return }
        _state.update { it.copy(roboBusy = true) }
        viewModelScope.launch {
            // Trava "um robô por licitação": motor local + ativas na VPS.
            if (roboEngine.runsFor(company.id, key).any { it.active }) {
                _events.send("O robô já está rodando no aparelho para esta licitação."); _state.update { it.copy(roboBusy = false) }; return@launch
            }
            val ativas = repository.roboAtivas().getOrDefault(emptyList())
            if (ativas.any { it.licitacaoId == id && it.ativo }) {
                _events.send("Já há um robô ativo para esta licitação (um robô por licitação)."); _state.update { it.copy(roboBusy = false) }; return@launch
            }
            // Prontidão (VPS): não inicia se o portal/compra não está pronto.
            val pront = repository.roboProntidao(id).getOrNull()
            if (pront != null && !pront.ok) {
                _events.send("Robô não está pronto: " + pront.motivos.joinToString("; ").ifBlank { pront.estado ?: "bloqueado" })
                _state.update { it.copy(roboBusy = false) }; return@launch
            }
            val ref = resolveCompraRef(dto)
            val now = System.currentTimeMillis()
            val resultado = runCatching {
                // 1) Importa a "minha licitação" nos repos locais (escopo sintético).
                roboRepo.upsertMyTenders(
                    company.id,
                    listOf(
                        PortalMyTender(
                            companyId = company.id, tenderKey = key, portal = Portal.COMPRAS_GOV,
                            uasg = ref.uasg.orEmpty(), number = ref.number ?: "", year = ref.year ?: 0,
                            modality = dto.modalidade ?: "", objectDescription = dto.orgao,
                            openingAt = parseIso(dto.dataAbertura).takeIf { it > 0 },
                            situation = dto.fase ?: "", hasProposal = !dto.urlProposta.isNullOrBlank(),
                            sources = setOf(PortalMyTender.SOURCE_PARTICIPACOES), firstSeenAt = now, updatedAt = now,
                        ),
                    ),
                )
                // 2) Plano ARMADO em dry_run (MANUAL): piso POR ITEM (valorLanceMinimo de cada item da licitação).
                //    Cada item do plano leva o seu próprio floor; o motor só SUGERE (nunca envia). Fallback:
                //    se nenhum item tem piso salvo, usa 1 item-alvo com o valorMinimo geral (compatível com o anterior).
                val dec = (cfg.decremento ?: 0.01).coerceAtLeast(0.01)
                val itensComPiso = _state.value.itens.mapNotNull { pi ->
                    val floor = pi.valorLanceMinimo?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                    val num = pi.numero ?: return@mapNotNull null
                    val ref = pi.valor?.toDoubleOrNull()?.takeIf { it >= floor } ?: floor
                    val qtd = pi.quantidade?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                    ProposalItemPlan(
                        itemNumber = num, description = pi.descricao.orEmpty(), quantity = qtd,
                        unitPrice = ref, floorUnitPrice = floor, selected = true,
                    )
                }
                val planItems = itensComPiso.ifEmpty {
                    listOf(ProposalItemPlan(itemNumber = cfg.itemAlvo?.toIntOrNull() ?: 1, quantity = 1.0, unitPrice = piso, floorUnitPrice = piso, selected = true))
                }
                roboRepo.savePlan(
                    PortalRobotPlan(
                        companyId = company.id, tenderKey = key, items = planItems,
                        bid = BidRobotConfig(
                            mode = BidRobotMode.MANUAL, // dry_run SEMPRE nesta ponte
                            strategy = mapEstrategia(cfg.estrategia), minDecrement = dec, reductionValue = dec,
                            ownIntervalSeconds = (cfg.intervaloSegundos ?: BidRobotConfig.MIN_OWN_INTERVAL_SECONDS).coerceAtLeast(BidRobotConfig.MIN_OWN_INTERVAL_SECONDS),
                            maxBids = 30,
                        ),
                        bidArmedAt = now, sessionAt = parseIso(dto.dataAbertura).takeIf { it > 0 }, updatedAt = now,
                    ),
                )
                // 3) Inicia o motor EXISTENTE (sem alterá-lo). Em MANUAL ele lê a sala e sugere; nunca envia sozinho.
                roboEngine.startBid(company.id, key).getOrThrow()
            }
            resultado.fold(
                onSuccess = { _events.send("Robô iniciado no aparelho em dry_run (sugere e registra; NÃO envia lance).") },
                onFailure = { _events.send(it.message ?: "Não foi possível iniciar o robô no aparelho.") },
            )
            _state.update { it.copy(roboBusy = false) }
        }
    }

    /**
     * ROBÔ DE PROPOSTA no aparelho (mesmo motor do modo local, SEM alterá-lo): abre a confirmação com os itens da
     * licitação vindos da plataforma, já com o preço ofertado salvo na VPS (valorProposto). Itens sem preço vêm
     * desmarcados; o usuário digita o valor na confirmação.
     */
    fun abrirCadastroProposta() {
        val dto = _state.value.tender ?: return
        if (currentCompany() == null) { viewModelScope.launch { _events.send("Entre na plataforma.") }; return }
        if (roboKeyOf(dto) == null) {
            viewModelScope.launch { _events.send("Robô de proposta só para compras do Comprasnet com UASG/número/ano reconhecíveis.") }; return
        }
        val itens = _state.value.itens.filter { it.numero != null }
        if (itens.isEmpty()) { viewModelScope.launch { _events.send("Sem itens carregados para esta licitação: não há o que cadastrar.") }; return }
        val candidatos = itens.sortedBy { it.numero }.map { pi ->
            val preco = pi.valorProposto?.toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0
            ProposalItemPlan(
                itemNumber = pi.numero!!, description = pi.descricao.orEmpty(),
                quantity = pi.quantidade?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
                unitPrice = preco, brand = pi.marca.orEmpty(), modelVersion = pi.modelo.orEmpty(),
                floorUnitPrice = pi.valorLanceMinimo?.toDoubleOrNull()?.takeIf { it > 0 },
                selected = preco > 0,
            )
        }
        _state.update { it.copy(propostaRoboItens = candidatos) }
    }

    fun fecharCadastroProposta() = _state.update { it.copy(propostaRoboItens = null) }

    /**
     * "Soltar o robô" (o usuário confirmou na tela: itens, declarações e a autorização do termo). Importa a compra
     * nos repositórios locais (escopo sintético da empresa da plataforma), grava os itens no plano e chama o
     * startProposal EXISTENTE do motor local — o robô abre o Compras.gov.br, acha a compra pela UASG + número,
     * confere, preenche e salva os itens selecionados. Depois abre a tela do portal para o usuário assistir.
     */
    fun soltarRoboProposta(items: List<ProposalItemPlan>, declarations: PortalDeclarations, updateDifferent: Boolean) {
        if (_state.value.propostaRoboBusy) return
        val dto = _state.value.tender ?: return
        val company = currentCompany() ?: return
        val key = roboKeyOf(dto) ?: return
        val usuario = (repository.session.value as? PlatformSession.SignedIn)?.user?.nome?.takeIf { it.isNotBlank() } ?: "Operador"
        _state.update { it.copy(propostaRoboBusy = true) }
        viewModelScope.launch {
            if (roboEngine.runsFor(company.id, key).any { it.active }) {
                _events.send("Já há um robô rodando no aparelho para esta licitação. Pare-o antes de soltar outro.")
                _state.update { it.copy(propostaRoboBusy = false) }; return@launch
            }
            val now = System.currentTimeMillis()
            val resultado = runCatching {
                importarMinhaLicitacao(dto, company.id, key, now)
                val atual = roboRepo.getPlan(company.id, key) ?: PortalRobotPlan(companyId = company.id, tenderKey = key)
                roboRepo.savePlan(atual.copy(items = items, sessionAt = parseIso(dto.dataAbertura).takeIf { it > 0 } ?: atual.sessionAt, updatedAt = now))
                val auth = ProposalAuthorization(acceptTerms = true, declarations = declarations, authorizedBy = usuario, authorizedAt = now)
                roboEngine.startProposal(company.id, key, auth, updateDifferent).getOrThrow()
            }
            resultado.fold(
                onSuccess = {
                    _state.update { it.copy(propostaRoboItens = null) }
                    _events.send("Robô de proposta solto. Acompanhe na tela do portal.")
                    _abrirPortal.send(Unit)
                },
                onFailure = { _events.send(it.message ?: "Não foi possível soltar o robô de proposta.") },
            )
            _state.update { it.copy(propostaRoboBusy = false) }
        }
    }

    fun pararRoboProposta() {
        val run = propostaRun.value?.takeIf { it.active } ?: return
        roboEngine.stop(run.id, "Parado pelo usuário (modo plataforma).")
        viewModelScope.launch { _events.send("Robô de proposta parado.") }
    }

    /** "Ver no portal": abre a tela do Compras.gov.br onde o robô trabalha. */
    fun verNoPortal() { viewModelScope.launch { _abrirPortal.send(Unit) } }

    /** Registra a compra da plataforma como "minha licitação" nos repositórios locais (escopo sintético). */
    private suspend fun importarMinhaLicitacao(dto: TenderDto, companyId: Long, key: String, now: Long) {
        val ref = resolveCompraRef(dto)
        roboRepo.upsertMyTenders(
            companyId,
            listOf(
                PortalMyTender(
                    companyId = companyId, tenderKey = key, portal = Portal.COMPRAS_GOV,
                    uasg = ref.uasg.orEmpty(), number = ref.number ?: "", year = ref.year ?: 0,
                    modality = dto.modalidade ?: "", objectDescription = dto.objeto.ifBlank { dto.orgao },
                    openingAt = parseIso(dto.dataAbertura).takeIf { it > 0 },
                    situation = dto.fase ?: "", hasProposal = !dto.urlProposta.isNullOrBlank(),
                    sources = setOf(PortalMyTender.SOURCE_PARTICIPACOES), firstSeenAt = now, updatedAt = now,
                ),
            ),
        )
    }

    /**
     * Modo MANUAL: o usuário toca "Enviar" na sugestão atual do robô de lance. O motor só envia se a sugestão ainda
     * for a mesma e passa pelas travas (piso, intervalos, teto) — mesmo botão do modo local.
     */
    fun enviarSugestao() {
        val run = roboRun.value?.takeIf { it.active && it.suggestion != null } ?: return
        roboEngine.sendSuggestion(run.id)
        viewModelScope.launch { _events.send("Enviando o lance sugerido (passa pelas travas de piso e intervalo).") }
    }

    fun pararRoboLocal() {
        val run = roboRun.value ?: return
        roboEngine.stop(run.id, "Parado pelo usuário (modo plataforma).")
        viewModelScope.launch { _events.send("Robô parado.") }
    }

    /** UASG/número/ano resolvidos da compra (usados pela chave do robô E pela navegação on-device). */
    private data class CompraRef(val uasg: String?, val number: String?, val year: Int?)

    /**
     * Extração ROBUSTA de UASG/número/ano: além dos campos crus dto.uasg/dto.numero, aproveita o órgão
     * ("UASG 926810"), o objeto e o id (padrões legado/PNCP) via refOf. Evita a trava "UASG/número/ano
     * reconhecíveis" quando o dado veio no texto e não nos campos; se nada identificar a compra (ex.: PNCP
     * sem UASG), os componentes ficam nulos → a trava fica (correta).
     */
    private fun resolveCompraRef(dto: TenderDto): CompraRef {
        // FONTE MAIS FORTE: o link do Comprasnet em urlProposta traz a "compra" com UASG+modalidade+número+ano
        // (ex.: ?compra=92548305000072026). É o dado real, mesmo quando o campo uasg vem nulo e portalUrl é a
        // publicação no PNCP.
        parseCompraComprasnet(dto.urlProposta ?: dto.portalUrl)?.let { return it }
        val ref = PortalTenderMatching.refOf(dto.id, dto.numero, dto.orgao)
        val ny = PortalTenderMatching.parseNumberYear(dto.numero)
            ?: PortalTenderMatching.parseNumberYear(dto.objeto)
            ?: (ref.number?.let { n -> ref.year?.let { y -> n to y } })
        val uasg = dto.uasg?.takeIf { it.any(Char::isDigit) } ?: ref.uasg
        return CompraRef(uasg, ny?.first ?: ref.number, ny?.second ?: ref.year)
    }

    /**
     * Extrai UASG/número/ano do número da "compra" do Comprasnet (17 dígitos: UASG[6]+modalidade[2]+
     * sequencial[5]+ano[4]), presente no link ?compra=... do Comprasnet. Ex.: 92548305000072026 →
     * UASG 925483, número 7, ano 2026. null se a URL não tiver esse formato.
     */
    private fun parseCompraComprasnet(url: String?): CompraRef? {
        val c = Regex("""compra=(\d{17})""").find(url.orEmpty())?.groupValues?.get(1)
            ?: Regex("""(?<!\d)(\d{17})(?!\d)""").find(url.orEmpty())?.groupValues?.get(1)
            ?: return null
        val uasg = c.substring(0, 6)
        val numero = c.substring(8, 13).trimStart('0').ifEmpty { "0" }
        val ano = c.substring(13, 17).toIntOrNull()?.takeIf { it in 1990..2100 } ?: return null
        return CompraRef(uasg, numero, ano)
    }

    private fun roboKeyOf(dto: TenderDto): String? =
        resolveCompraRef(dto).let { PortalTenderMatching.tenderKey(it.uasg, it.number, it.year) }

    private fun mapEstrategia(e: String?): BidStrategy = when {
        e == null -> BidStrategy.CONSERVADORA
        e.contains("agress", true) -> BidStrategy.AGRESSIVA
        e.contains("acompan", true) -> BidStrategy.ACOMPANHAR_CONCORRENTE
        e.contains("personaliz", true) -> BidStrategy.PERSONALIZADA
        else -> BidStrategy.CONSERVADORA
    }

    private fun roboOp(okMsg: String, block: suspend () -> Result<Unit>) {
        if (_state.value.roboBusy) return
        _state.update { it.copy(roboBusy = true) }
        viewModelScope.launch {
            val r = block()
            if (r.isSuccess) _events.send(okMsg) else _events.send(r.exceptionOrNull()?.message ?: "Não foi possível concluir.")
            val robo = repository.roboConfig(id).getOrNull()
            val prontidao = repository.roboProntidao(id).getOrNull()
            val lances = repository.roboHistoricoCount(id).getOrDefault(_state.value.roboLances)
            // Recarrega itens para refletir os pisos por item recém-salvos (usados pela ponte on-device).
            val itens = repository.tenderItens(id).getOrDefault(_state.value.itens)
            _state.update { it.copy(roboBusy = false, roboConfig = robo ?: it.roboConfig, prontidao = prontidao ?: it.prontidao, roboLances = lances, itens = itens) }
        }
    }

    fun onMsgInput(v: String) = _state.update { it.copy(msgInput = v) }

    fun sendMessage() {
        val msg = _state.value.msgInput.trim()
        if (msg.isBlank() || _state.value.sendingMsg) return
        _state.update { it.copy(sendingMsg = true, msgInput = "") }
        viewModelScope.launch {
            val r = repository.enviarMensagem(msg, id)
            val novas = repository.mensagens(id).getOrDefault(_state.value.mensagens)
            _state.update { it.copy(sendingMsg = false, mensagens = novas) }
            if (r.isFailure) _events.send(r.exceptionOrNull()?.message ?: "Mensagem enviada.")
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

/** Monta o texto/planilha legível da proposta gerada no aparelho (exibido em "Ver proposta"). */
private fun buildProposalContent(draft: com.licitaia.ai.api.ProposalDraft, total: Double): String = buildString {
    appendLine("PROPOSTA COMERCIAL (gerada no aparelho)")
    appendLine()
    draft.items.forEachIndexed { i, item ->
        val num = item.itemNumber ?: (i + 1)
        val unit = if (item.unitPrice > 0) PlatformFmt.money(item.unitPrice)
            else item.estimatedUnitPrice?.takeIf { it > 0 }?.let { PlatformFmt.money(it) + " (ref.)" } ?: "a definir"
        val sub = if (item.unitPrice > 0) PlatformFmt.money(item.total) else "—"
        appendLine("Item $num — ${item.description.ifBlank { "(sem descrição)" }}")
        val qtd = if (item.quantity == item.quantity.toLong().toDouble()) item.quantity.toLong().toString() else item.quantity.toString()
        appendLine("  Qtd $qtd ${item.unit.ifBlank { "un" }} × $unit = $sub")
        if (item.confidentialBudget) appendLine("  (orçamento sigiloso — defina o preço)")
    }
    appendLine()
    appendLine("TOTAL: " + if (total > 0) PlatformFmt.money(total) else "a definir (edital sem valor)")
    appendLine("Entrega: ${draft.deliveryDays} dia(s) · Validade: ${draft.validityDays} dia(s)")
    if (draft.notes.isNotBlank()) {
        appendLine()
        appendLine("Observações:")
        appendLine(draft.notes)
    }
}
