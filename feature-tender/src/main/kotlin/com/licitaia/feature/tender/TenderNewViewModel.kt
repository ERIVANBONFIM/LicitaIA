package com.licitaia.feature.tender

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.ManualTenderDraft
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/** Formulário de cadastro manual. Datas em epoch millis locais; horas como "HH:mm". */
data class ManualTenderForm(
    val portal: Portal = Portal.COMPRAS_GOV,
    val number: String = "",
    val agency: String = "",
    val objectDescription: String = "",
    val modality: Modality = Modality.PREGAO_ELETRONICO,
    val segment: Segment? = null,
    val uf: String = "",
    val city: String = "",
    val estimatedValue: String = "",
    val proposalDeadlineDate: Long? = null,
    val proposalDeadlineTime: String = "09:00",
    val sessionDate: Long? = null,
    val sessionTime: String = "10:00",
    val editalUrl: String = "",
) {
    /** Converte para o rascunho de domínio; [fallbackSegment] = segmento da empresa quando o usuário não escolheu. */
    fun toDraft(fallbackSegment: Segment): ManualTenderDraft = ManualTenderDraft(
        portal = portal,
        number = number.trim(),
        agency = agency.trim(),
        objectDescription = objectDescription.trim(),
        modality = modality,
        segment = segment ?: fallbackSegment,
        uf = uf.trim().uppercase(),
        city = city.trim(),
        estimatedValue = parseNumber(estimatedValue) ?: 0.0,
        proposalDeadline = combine(proposalDeadlineDate, proposalDeadlineTime) ?: 0L,
        sessionAt = combine(sessionDate, sessionTime) ?: 0L,
        editalUrl = editalUrl.trim().takeIf { it.isNotEmpty() },
    )

    /** Erros de formulário que o domínio não enxerga (texto de hora/valor inválidos). */
    fun localErrors(): List<String> = buildList {
        if (estimatedValue.isNotBlank() && parseNumber(estimatedValue)?.isNaN() != false) add("Valor estimado inválido. Use, por exemplo, 250000,00.")
        if (proposalDeadlineDate != null && parseTime(proposalDeadlineTime) == null) add("Hora do prazo de propostas inválida (use HH:mm).")
        if (sessionDate != null && parseTime(sessionTime) == null) add("Hora da sessão inválida (use HH:mm).")
    }

    companion object {
        fun parseTime(text: String): Pair<Int, Int>? {
            val match = Regex("^\\s*(\\d{1,2})[:h](\\d{2})\\s*$").find(text) ?: return null
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            return if (hour in 0..23 && minute in 0..59) hour to minute else null
        }

        fun combine(date: Long?, time: String): Long? {
            if (date == null) return null
            val (hour, minute) = parseTime(time) ?: return null
            return Calendar.getInstance().apply {
                timeInMillis = date
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }
}

data class TenderNewState(
    val form: ManualTenderForm = ManualTenderForm(),
    val companySegment: Segment? = null,
    val role: UserRole? = null,
    val saving: Boolean = false,
    val errors: List<String> = emptyList(),
    /** Id da licitação criada: a tela navega para o detalhe. */
    val createdId: Long? = null,
) {
    val canCreate: Boolean get() = role?.let { Rbac.can(it, Permission.ANALISAR) } ?: false
}

@HiltViewModel
class TenderNewViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val tenders: TenderRepository,
) : ViewModel() {

    private val form = MutableStateFlow(ManualTenderForm())
    private val flags = MutableStateFlow(TenderNewState())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val state: StateFlow<TenderNewState> = combine(auth.session, form, flags.asStateFlow()) { session, f, s ->
        s.copy(form = f, companySegment = session?.activeCompany?.segment, role = session?.user?.role)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TenderNewState())

    fun edit(transform: (ManualTenderForm) -> ManualTenderForm) {
        form.update(transform)
        if (flags.value.errors.isNotEmpty()) flags.update { it.copy(errors = emptyList()) }
    }

    fun save() {
        val current = state.value
        if (current.saving) return
        val session = auth.session.value ?: run { _messages.tryEmit("Sessão encerrada. Entre novamente."); return }
        val draft = current.form.toDraft(session.activeCompany.segment)
        val errors = current.form.localErrors() + draft.validate()
        if (errors.isNotEmpty()) {
            flags.update { it.copy(errors = errors.distinct()) }
            return
        }
        flags.update { it.copy(saving = true, errors = emptyList()) }
        viewModelScope.launch {
            val result = try {
                tenders.createManual(session.activeCompany.id, draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { id ->
                    _messages.tryEmit("Licitação ${draft.number} cadastrada. Importe o edital para analisar.")
                    flags.update { it.copy(saving = false, createdId = id) }
                },
                onFailure = { e ->
                    flags.update { it.copy(saving = false, errors = listOf(e.message?.takeIf(String::isNotBlank) ?: "Não foi possível cadastrar a licitação.")) }
                },
            )
        }
    }
}
