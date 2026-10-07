package com.licitaia.feature.bidding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Tender
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.portal.PortalTenderMatching
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.feature.live.ui.MyTendersPanel
import com.licitaia.feature.live.ui.MyTendersViewModel
import com.licitaia.feature.live.ui.RobotRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Identificação da compra no Comprasnet deduzida da licitação do app (UASG pode faltar em dados só do PNCP). */
data class EntryRef(val tender: Tender, val uasg: String?, val number: String?, val year: Int?, val opportunityId: String?)

@HiltViewModel
class RobotProposalEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tenders: TenderRepository,
    private val opportunities: OpportunityRepository,
    private val robots: PortalRobotRepository,
) : ViewModel() {
    val tenderId: Long = savedStateHandle.get<Long>("tenderId") ?: -1L

    private val _ref = MutableStateFlow<EntryRef?>(null)
    val ref = _ref.asStateFlow()
    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 2)
    /** tenderKey criado → abrir o plano do robô. */
    val opened = _opened.asSharedFlow()
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val errors = _errors.asSharedFlow()

    init {
        viewModelScope.launch {
            val t = runCatching { tenders.observeTender(tenderId).firstOrNull() }.getOrNull() ?: return@launch
            val opp = runCatching { opportunities.getOpportunity(t.opportunityId) }.getOrNull()
            val r = PortalTenderMatching.refOf(t.opportunityId, t.number, t.agency)
            // 1º a UASG explícita da licitação (trazida no "Tenho interesse"); depois a da oportunidade (campo ou
            // palavras-chave "UASG 927312"); por fim a deduzida do id legado/órgão.
            val explicit = t.uasg?.filter(Char::isDigit)?.takeIf { it.length in 5..6 }?.padStart(6, '0')
            val fromOpp = opp?.let { com.licitaia.domain.model.UasgCode.of(it) }?.takeIf { it.length in 5..6 }?.padStart(6, '0')
            _ref.value = EntryRef(t, explicit ?: fromOpp ?: r.uasg, r.number, r.year, opp?.id ?: t.opportunityId)
        }
    }

    /**
     * Cria a licitação em "Minhas licitações" a partir da licitação do app (ainda sem proposta no portal) para o robô
     * poder abri-la pela busca de "Todas as compras" (UASG + número). Não toca no portal.
     */
    fun createFromApp(uasgInput: String) {
        val r = _ref.value ?: return
        viewModelScope.launch {
            val uasg = uasgInput.filter(Char::isDigit).ifEmpty { r.uasg.orEmpty() }
            val key = PortalTenderMatching.tenderKey(uasg, r.number, r.year)
            if (key == null) { _errors.tryEmit("Informe a UASG (5 ou 6 dígitos) e confira o número/ano da licitação."); return@launch }
            val now = System.currentTimeMillis()
            val mine = PortalMyTender(
                companyId = r.tender.companyId, tenderKey = key, uasg = uasg.padStart(6, '0'),
                number = r.number!!.trimStart('0').ifEmpty { "0" }, year = r.year!!,
                modality = r.tender.modality.label, objectDescription = r.tender.agency,
                openingAt = r.tender.sessionAt.takeIf { it > 0 }, situation = "Escolhida no app",
                hasProposal = false, sources = setOf(SOURCE_APP),
                matchedOpportunityId = r.opportunityId, matchedTenderId = r.tender.id,
                firstSeenAt = now, updatedAt = now,
            )
            runCatching { robots.upsertMyTenders(r.tender.companyId, listOf(mine)) }
                .onSuccess { _opened.tryEmit(key) }
                .onFailure { _errors.tryEmit(it.message ?: "Não foi possível criar o robô desta licitação.") }
        }
    }

    companion object {
        /** Licitação escolhida no app (Radar/Busca) antes de existir proposta no portal. */
        const val SOURCE_APP = "app"
    }
}

/**
 * Entrada vinda da proposta do app ("Confirmar e soltar o robô"): abre o robô da compra de "Minhas licitações" casada
 * com a licitação do app. Sem vínculo ainda (licitação nova, sem proposta no portal): cria o vínculo pela UASG + número
 * + ano da licitação do app — o robô abre a compra pela busca de "Todas as compras".
 */
@Composable
fun RobotProposalEntryScreen(vm: RobotProposalEntryViewModel = hiltViewModel(), mineVm: MyTendersViewModel = hiltViewModel()) {
    val mine by mineVm.state.collectAsStateWithLifecycle()
    val ref by vm.ref.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val match = mine.tenders.firstOrNull { it.matchedTenderId == vm.tenderId }
    var uasg by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(ref?.uasg) { if (uasg.isBlank()) uasg = ref?.uasg.orEmpty() }
    LaunchedEffect(Unit) { mineVm.events.collect { navigator.showMessage(it) } }
    LaunchedEffect(Unit) { vm.errors.collect { navigator.showMessage(it) } }
    LaunchedEffect(Unit) { vm.opened.collect { navigator.navigate(RobotRoutes.plan(it)) } }
    LaunchedEffect(match?.tenderKey) { match?.let { navigator.navigate(RobotRoutes.plan(it.tenderKey)) } }
    LicitaScaffold(title = "Robô de proposta", showBack = true) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (match == null) {
                val r = ref
                AlertBanner(
                    "Licitação nova para o robô",
                    "Ela ainda não está em “Minhas participações” do Comprasnet. O robô abre a compra pela busca (UASG + número) e cadastra a proposta.",
                    Tone.INFO,
                )
                if (r != null) {
                    Text("${r.tender.modality.label} ${r.number ?: r.tender.number}/${r.year ?: ""} · ${r.tender.agency}", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
                    OutlinedTextField(
                        value = uasg, onValueChange = { v -> uasg = v.filter(Char::isDigit).take(6) },
                        label = { Text("UASG (código da unidade compradora)") },
                        supportingText = { Text(if (r.uasg != null) "Preenchida pela licitação." else "Não veio na fonte: copie do edital/aviso (5 ou 6 dígitos).") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton("Continuar para o robô", { vm.createFromApp(uasg) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.PlayArrow, tone = Tone.WARNING)
                }
                SecondaryButton("Já participo: buscar minhas licitações", { mineVm.refresh() }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Refresh)
                MyTendersPanel(
                    mine, onRefresh = { mineVm.refresh() },
                    onOpenTender = { navigator.navigate(RobotRoutes.plan(it.tenderKey)) },
                    onOpenPortal = { navigator.navigate(Routes.portalWeb(Portal.COMPRAS_GOV)) },
                )
            }
        }
    }
}
