package com.licitaia.feature.live.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalNotLoggedInException
import com.licitaia.domain.portal.PortalRobotPlan
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.portal.RobotProposalStatus
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.PortalRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import com.licitaia.feature.live.automation.MyTendersSync
import com.licitaia.feature.live.automation.PortalAutomationStorage
import com.licitaia.feature.live.automation.PortalLoginBadge
import com.licitaia.feature.live.automation.PortalLoginCheck
import com.licitaia.feature.live.automation.PortalSessionGate
import com.licitaia.feature.live.automation.PortalRobotEngine
import com.licitaia.feature.live.automation.RobotRun
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Rotas do robô (registradas pelo grafo de feature-bidding; strings simples para evitar dependência circular). */
object RobotRoutes {
    const val PLAN = "robotplan/{key}"
    fun plan(key: String) = "robotplan/$key"

    /** Entrada pela proposta do app (licitação "Tenho interesse"): abre o robô da compra casada em Minhas licitações. */
    const val PROPOSAL = "robotproposal/{tenderId}"
    fun proposal(tenderId: Long) = "robotproposal/$tenderId"
}

data class MyTendersUiState(
    val companyId: Long? = null,
    val canOperate: Boolean = false,
    val portalStatus: PortalConnectionStatus? = null,
    val tenders: List<PortalMyTender> = emptyList(),
    val plans: Map<String, PortalRobotPlan> = emptyMap(),
    val runs: List<RobotRun> = emptyList(),
    val syncing: Boolean = false,
    val lastResult: String? = null,
    val notLogged: Boolean = false,
    val mapMode: Boolean = false,
    val snapshots: Int = 0,
    val portalLastLoginAt: Long? = null,
    /** Última verificação REAL da sessão (robô/busca chegaram à área logada ou caíram no login/acessoNegado). */
    val loginCheck: PortalLoginCheck? = null,
) {
    /** Selo "Logado": a verificação real mais recente vence o status salvo ([PortalLoginBadge]). */
    val portalConnected: Boolean get() = PortalLoginBadge.logged(portalStatus, portalLastLoginAt, loginCheck)

    /** A última leitura caiu no login do portal (status salvo ainda dizia conectado). */
    val loggedOutByCheck: Boolean get() = loginCheck?.logged == false && !portalConnected
}

/** Estado compartilhado de "Minhas licitações (Comprasnet)" + robôs (Robô e Pregões ao Vivo). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MyTendersViewModel @Inject constructor(
    private val auth: AuthRepository,
    portals: PortalRepository,
    private val repo: PortalRobotRepository,
    private val sync: MyTendersSync,
    val engine: PortalRobotEngine,
    val storage: PortalAutomationStorage,
    gate: PortalSessionGate,
) : ViewModel() {
    private val notLogged = MutableStateFlow(false)
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val company = auth.session
    private val base = company.flatMapLatest { s ->
        val id = s?.activeCompany?.id ?: return@flatMapLatest flowOf(Triple<Long?, List<PortalMyTender>, List<PortalRobotPlan>>(null, emptyList(), emptyList()))
        combine(repo.observeMyTenders(id), repo.observePlans(id)) { t, p -> Triple<Long?, List<PortalMyTender>, List<PortalRobotPlan>>(id, t, p) }
    }
    private val portalSession = company.flatMapLatest { s ->
        val id = s?.activeCompany?.id ?: return@flatMapLatest flowOf(null)
        portals.observeSessions(id).map { list -> list.firstOrNull { it.portal == Portal.COMPRAS_GOV } }
    }

    val state: StateFlow<MyTendersUiState> = combine(
        combine(base, portalSession, company, gate.loginCheck) { b, ps, s, check -> Triple(b, ps to check, s) },
        engine.runs, sync.running, sync.lastResult,
        combine(notLogged, storage.mapMode, storage.snapshotCount) { a, b, c -> Triple(a, b, c) },
    ) { (b, psCheck, s), runs, syncing, last, (nl, map, snaps) ->
        val (ps, check) = psCheck
        MyTendersUiState(
            companyId = b.first, canOperate = s?.user?.role?.let { Rbac.can(it, Permission.OPERAR_SESSOES) } == true && s.user.demo.not(),
            portalStatus = ps?.status, tenders = b.second, plans = b.third.associateBy { it.tenderKey },
            runs = runs.values.filter { it.companyId == b.first }.sortedByDescending { it.startedAt },
            syncing = syncing, lastResult = last, notLogged = nl, mapMode = map, snapshots = snaps,
            portalLastLoginAt = ps?.lastLoginAt, loginCheck = check?.takeIf { it.companyId == b.first },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyTendersUiState())

    init { storage.refreshCount() }

    fun refresh() {
        val id = auth.session.value?.activeCompany?.id ?: return
        viewModelScope.launch {
            val r = sync.refreshMyTenders(id, includeElectronic = true)
            notLogged.value = r.exceptionOrNull() is PortalNotLoggedInException
            r.onSuccess { s -> _events.send("${s.found} licitação(ões) lida(s) do Comprasnet" + if (s.warnings.isNotEmpty()) " · ${s.warnings.first()}" else "") }
                .onFailure { e -> if (e !is PortalNotLoggedInException) _events.send(e.message ?: "Falha ao buscar suas licitações.") }
        }
    }

    fun setMapMode(on: Boolean) = storage.setMapMode(on)
    fun clearSnapshots() { viewModelScope.launch { storage.clearSnapshots() } }
    fun export(onIntent: (Intent?) -> Unit) { viewModelScope.launch { onIntent(storage.exportIntent()) } }
    fun stopAll() = engine.stopAll()
}

fun proposalTone(s: RobotProposalStatus?): Tone = when (s) {
    RobotProposalStatus.CADASTRADA -> Tone.SUCCESS
    RobotProposalStatus.PARCIAL, RobotProposalStatus.AGUARDANDO_USUARIO -> Tone.WARNING
    RobotProposalStatus.FALHOU -> Tone.DANGER
    RobotProposalStatus.EXECUTANDO, RobotProposalStatus.PRONTA -> Tone.INFO
    else -> Tone.NEUTRAL
}

/** Seção "Minhas licitações (Comprasnet)". */
@Composable
fun MyTendersPanel(
    state: MyTendersUiState,
    onRefresh: () -> Unit,
    onOpenTender: (PortalMyTender) -> Unit,
    onOpenPortal: () -> Unit,
    modifier: Modifier = Modifier,
    maxItems: Int = Int.MAX_VALUE,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Minhas licitações (Comprasnet)", actionLabel = if (state.syncing) null else "Buscar", onAction = onRefresh)
        if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.notLogged || (!state.portalConnected && state.tenders.isEmpty())) {
            AlertBanner(
                "Comprasnet desconectado",
                "Entre no Comprasnet em Portais para buscar suas licitações.",
                Tone.WARNING, actionLabel = "Entrar", onAction = onOpenPortal,
            )
        }
        state.lastResult?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
        if (state.tenders.isEmpty()) {
            Text(
                "Participe das compras no Comprasnet (PC ou celular) e toque em Buscar: o app abre “Licitação e Dispensa (novo)” pelo menu e lê “Minhas participações” (Em andamento e Propostas) com a sua sessão. Deixe o app aberto durante a busca.",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
            )
            SecondaryButton("Buscar minhas licitações", onRefresh, Modifier.fillMaxWidth(), enabled = !state.syncing, icon = Icons.Outlined.Refresh)
        }
        state.tenders.take(maxItems).forEach { t -> MyTenderCard(t, state.plans[t.tenderKey], state.runs.filter { it.tenderKey == t.tenderKey }, onClick = { onOpenTender(t) }) }
        if (state.tenders.size > maxItems) Text("+${state.tenders.size - maxItems} no Robô", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        if (!state.portalConnected && state.tenders.isNotEmpty()) {
            SecondaryButton("Abrir Comprasnet", onOpenPortal, Modifier.fillMaxWidth(), icon = Icons.Outlined.Language, tone = Tone.NEUTRAL)
        }
    }
}

@Composable
fun MyTenderCard(t: PortalMyTender, plan: PortalRobotPlan?, runs: List<RobotRun>, onClick: () -> Unit) {
    val active = runs.firstOrNull { it.active }
    LicitaCard(Modifier.fillMaxWidth(), onClick = onClick, accent = when {
        active?.needsUser == true -> LicitaColors.Yellow
        active != null -> LicitaColors.Blue
        plan?.bidArmed == true -> LicitaColors.Green
        else -> null
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.label, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Text(
                    listOf(t.modality, t.situation).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Compras.gov.br" },
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                )
            }
            val situation = com.licitaia.domain.model.OfficialSituation.fromText(t.situation)
            when {
                // Compra suspensa/cancelada/revogada/anulada/deserta/fracassada: selo colorido em destaque.
                situation != null && active == null -> com.licitaia.core.ui.components.OfficialSituationBadge(situation)
                active != null -> StatusBadge(active.status.label, if (active.needsUser) Tone.WARNING else Tone.INFO, pulsing = !active.needsUser)
                plan?.bidArmed == true -> StatusBadge("Lance armado", Tone.SUCCESS)
                t.hasProposal -> StatusBadge("Proposta enviada", Tone.SUCCESS)
                else -> StatusBadge("Sem proposta", Tone.NEUTRAL)
            }
        }
        if (t.objectDescription.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(t.objectDescription, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Sessão: " + (t.openingAt?.let(Formatters::dateTime) ?: "não informada"),
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f),
            )
            plan?.proposalStatus?.takeIf { it != RobotProposalStatus.NAO_CONFIGURADA }?.let {
                Spacer(Modifier.width(6.dp))
                StatusBadge(it.label, proposalTone(it))
            }
        }
        if (t.matchedTenderId != null || t.matchedOpportunityId != null) {
            Text("Vinculada às suas licitações no app", style = MaterialTheme.typography.labelSmall, color = LicitaColors.GreenBright)
        }
    }
}
