package com.licitaia.feature.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditEvent
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.IntegrityReport
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuditFilters(
    val allCompanies: Boolean = false,
    val action: AuditAction? = null,
    val origin: AuditOrigin? = null,
    val result: AuditResult? = null,
    val query: String = "",
    val retry: Int = 0,
    val clears: Int = 0,
) {
    val activeCount: Int get() = listOfNotNull(action, origin, result).size + if (query.isBlank()) 0 else 1
}

sealed interface IntegrityUi {
    data object Idle : IntegrityUi
    data object Checking : IntegrityUi
    data class Done(val report: IntegrityReport) : IntegrityUi
    data class Failed(val message: String) : IntegrityUi
}

data class AuditUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val noSession: Boolean = false,
    val allowed: Boolean = true,
    val roleLabel: String = "",
    val companyName: String = "",
    val total: Int = 0,
    val events: List<AuditEvent> = emptyList(),
    val filters: AuditFilters = AuditFilters(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AuditViewModel @Inject constructor(
    auth: AuthRepository,
    private val audit: AuditRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(AuditFilters())

    val state: StateFlow<AuditUiState> = combine(auth.session, filters) { s, f -> s to f }
        .flatMapLatest { (session, f) ->
            when {
                session == null -> flowOf(AuditUiState(loading = false, noSession = true, filters = f))
                !Rbac.can(session.user.role, Permission.VER_AUDITORIA) ->
                    flowOf(AuditUiState(loading = false, allowed = false, roleLabel = session.user.role.label, filters = f))
                else -> audit.observeEvents(if (f.allCompanies) null else session.activeCompany.id)
                    .map { all ->
                        val q = f.query.trim().lowercase()
                        val filtered = all.filter { e ->
                            (f.action == null || e.action == f.action) &&
                                (f.origin == null || e.origin == f.origin) &&
                                (f.result == null || e.result == f.result) &&
                                (q.isEmpty() || e.searchable().contains(q))
                        }
                        AuditUiState(
                            loading = false, roleLabel = session.user.role.label,
                            companyName = session.activeCompany.tradeName.ifBlank { session.activeCompany.name },
                            total = all.size, events = filtered, filters = f,
                        )
                    }
                    .catch { emit(AuditUiState(loading = false, error = it.message ?: "Falha ao carregar a auditoria.", filters = f)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuditUiState())

    private val _integrity = MutableStateFlow<IntegrityUi>(IntegrityUi.Idle)
    /** Resultado da última verificação da cadeia de hashes (não persiste entre aberturas da tela). */
    val integrity: StateFlow<IntegrityUi> = _integrity.asStateFlow()

    /** Percorre toda a trilha e confere o hash encadeado de cada evento. Só para quem vê a auditoria. */
    fun verifyIntegrity() {
        if (_integrity.value is IntegrityUi.Checking || !state.value.allowed) return
        _integrity.value = IntegrityUi.Checking
        viewModelScope.launch {
            _integrity.value = runCatching { audit.verifyIntegrity() }
                .fold({ IntegrityUi.Done(it) }, { IntegrityUi.Failed(it.message ?: "Não foi possível verificar a trilha.") })
        }
    }

    fun setAllCompanies(all: Boolean) = filters.update { it.copy(allCompanies = all) }
    fun setAction(action: AuditAction?) = filters.update { it.copy(action = action) }
    fun setOrigin(origin: AuditOrigin?) = filters.update { it.copy(origin = origin) }
    fun setResult(result: AuditResult?) = filters.update { it.copy(result = result) }
    fun setQuery(query: String) = filters.update { it.copy(query = query.take(80)) }
    fun clearFilters() = filters.update { AuditFilters(allCompanies = it.allCompanies, clears = it.clears + 1) }
    fun retry() = filters.update { it.copy(retry = it.retry + 1) }

    private fun AuditEvent.searchable(): String = listOfNotNull(
        user, companyName, portal, tenderNumber, item, action.label, previousValue, newValue, reason, details,
    ).joinToString(" ").lowercase()
}

/** Texto plano para exportação/compartilhamento da trilha. */
internal fun buildExportText(events: List<AuditEvent>, scope: String, limit: Int = 500): String = buildString {
    appendLine("LicitaIA — Trilha de auditoria")
    appendLine("Escopo: $scope")
    appendLine("Gerado em: ${Formatters.dateTime(System.currentTimeMillis())}")
    appendLine("Eventos: ${events.size}" + if (events.size > limit) " (exibindo os $limit mais recentes)" else "")
    appendLine("Integridade: cada linha traz o hash SHA-256 do evento (prevHash + campos) e o hash do evento anterior;")
    appendLine("eventos anteriores ao encadeamento aparecem com hash \"—\".")
    appendLine("----------------------------------------")
    events.take(limit).forEach { e -> appendLine(e.toExportLine()) }
}

internal fun AuditEvent.toExportLine(): String = buildString {
    append("[${Formatters.dateTime(timestamp)}] #$id ${action.label} — ${result.label} (${origin.label})")
    append(" | Usuário: $user | Empresa: $companyName")
    portal?.let { append(" | Portal: $it") }
    tenderNumber?.let { append(" | Pregão: $it") }
    item?.let { append(" | Item: $it") }
    if (previousValue != null || newValue != null) append(" | ${previousValue ?: "—"} → ${newValue ?: "—"}")
    reason?.let { append(" | Motivo: $it") }
    if (details.isNotBlank()) append(" | $details")
    append(" | hash: ${hash.ifEmpty { "—" }}")
    if (hash.isNotEmpty()) append(" | prev: ${prevHash.ifEmpty { "(início da cadeia)" }}")
}

/** Texto curto para a UI: ok / quebra a partir do evento N. */
internal fun IntegrityReport.summary(): String = when {
    total == 0 -> "Nenhum evento para verificar."
    firstBroken != null -> "Integridade comprometida a partir do evento #$firstBroken: $verified evento(s) conferido(s) antes da quebra, de $total."
    verified == 0 -> "Nenhum evento encadeado ainda ($unhashed anterior(es) à versão com hash). Novos eventos serão encadeados."
    unhashed > 0 -> "Cadeia íntegra: $verified evento(s) conferido(s); $unhashed anterior(es) ao encadeamento (sem hash)."
    else -> "Cadeia íntegra: $verified de $total evento(s) conferido(s)."
}
