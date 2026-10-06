package com.licitaia.feature.settings.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PinFlow { SET, CHANGE, REMOVE }

/** Passos do diálogo de PIN. */
enum class PinStep { CURRENT, NEW, CONFIRM }

data class PinDialogState(
    val flow: PinFlow,
    val step: PinStep,
    val input: String = "",
    val newPin: String = "",
    val error: String? = null,
    val busy: Boolean = false,
)

data class SecurityUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val settings: AppSettings = AppSettings(),
    val hasPin: Boolean = false,
    val pinDialog: PinDialogState? = null,
)

val SESSION_TIMEOUT_OPTIONS = listOf(1, 5, 15, 30)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val audit: AuditRepository,
) : ViewModel() {

    private val local = MutableStateFlow(SecurityUiState())
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<SecurityUiState> = combine(settingsRepository.settings, local) { settings, l ->
        l.copy(loading = false, settings = settings)
    }
        .catch { emit(SecurityUiState(loading = false, error = it.message ?: "Falha ao carregar as opções de segurança.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityUiState())

    init { refreshPin() }

    private fun refreshPin() {
        viewModelScope.launch {
            val has = runCatching { auth.hasPin() }.getOrDefault(false)
            local.update { it.copy(hasPin = has) }
        }
    }

    fun update(label: String, previous: String, new: String, transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            runCatching { settingsRepository.update(transform) }
                .onSuccess { recordChange(label, previous, new) }
                .onFailure { _events.send(it.message ?: "Não foi possível salvar") }
        }
    }

    // ------------------------------------------------------------------ PIN

    fun startPinFlow(flow: PinFlow) {
        val step = if (flow == PinFlow.SET) PinStep.NEW else PinStep.CURRENT
        local.update { it.copy(pinDialog = PinDialogState(flow, step)) }
    }

    fun dismissPinDialog() = local.update { it.copy(pinDialog = null) }

    fun onPinInput(value: String) {
        val digits = value.filter { it.isDigit() }.take(6)
        local.update { s -> s.copy(pinDialog = s.pinDialog?.copy(input = digits, error = null)) }
    }

    fun submitPinStep() {
        val dialog = local.value.pinDialog ?: return
        if (dialog.busy) return
        val pin = dialog.input
        if (pin.length < 4) {
            local.update { s -> s.copy(pinDialog = dialog.copy(error = "O PIN deve ter de 4 a 6 dígitos")) }
            return
        }
        when (dialog.step) {
            PinStep.CURRENT -> verifyCurrent(dialog, pin)
            PinStep.NEW -> {
                if (isWeakPin(pin)) {
                    local.update { s -> s.copy(pinDialog = dialog.copy(error = "Evite sequências ou dígitos repetidos")) }
                } else {
                    local.update { s -> s.copy(pinDialog = dialog.copy(step = PinStep.CONFIRM, newPin = pin, input = "")) }
                }
            }
            PinStep.CONFIRM -> {
                if (pin != dialog.newPin) {
                    local.update { s -> s.copy(pinDialog = dialog.copy(step = PinStep.NEW, newPin = "", input = "", error = "Os PINs não coincidem. Tente novamente.")) }
                } else {
                    savePin(dialog, pin)
                }
            }
        }
    }

    private fun verifyCurrent(dialog: PinDialogState, pin: String) {
        local.update { s -> s.copy(pinDialog = dialog.copy(busy = true)) }
        viewModelScope.launch {
            val ok = runCatching { auth.verifyPin(pin) }.getOrDefault(false)
            if (!ok) {
                local.update { s -> s.copy(pinDialog = dialog.copy(busy = false, input = "", error = "PIN atual incorreto")) }
                return@launch
            }
            if (dialog.flow == PinFlow.REMOVE) {
                runCatching { auth.setPin(null) }
                    .onSuccess {
                        // Auditoria do PIN é registrada pelo AuthRepository.
                        local.update { it.copy(pinDialog = null, hasPin = false) }
                        _events.send("PIN removido")
                    }
                    .onFailure { e ->
                        local.update { s -> s.copy(pinDialog = dialog.copy(busy = false, error = e.message ?: "Falha ao remover o PIN")) }
                    }
            } else {
                local.update { s -> s.copy(pinDialog = dialog.copy(busy = false, step = PinStep.NEW, input = "")) }
            }
        }
    }

    private fun savePin(dialog: PinDialogState, pin: String) {
        local.update { s -> s.copy(pinDialog = dialog.copy(busy = true)) }
        viewModelScope.launch {
            runCatching { auth.setPin(pin) }
                .onSuccess {
                    val changed = dialog.flow == PinFlow.CHANGE
                    local.update { it.copy(pinDialog = null, hasPin = true) }
                    _events.send(if (changed) "PIN alterado" else "PIN definido")
                }
                .onFailure { e ->
                    local.update { s -> s.copy(pinDialog = dialog.copy(busy = false, error = e.message ?: "Falha ao salvar o PIN")) }
                }
        }
    }

    private fun isWeakPin(pin: String): Boolean {
        if (pin.all { it == pin[0] }) return true
        val asc = pin.zipWithNext().all { (a, b) -> b - a == 1 }
        val desc = pin.zipWithNext().all { (a, b) -> a - b == 1 }
        return asc || desc
    }

    /** Auditoria da configuração — nunca registra o valor do PIN. */
    private suspend fun recordChange(item: String, previous: String, new: String) {
        runCatching {
            audit.record(action = AuditAction.CONFIGURACAO, item = "Segurança: $item", previousValue = previous, newValue = new)
        }
    }
}
