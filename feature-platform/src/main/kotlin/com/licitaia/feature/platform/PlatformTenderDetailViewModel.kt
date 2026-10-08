package com.licitaia.feature.platform

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.TenderDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformDetailUi(
    val loading: Boolean = true,
    val tender: TenderDto? = null,
    val error: String? = null,
    /** true quando o detalhe veio do espelho local (sem rede) e não do servidor. */
    val fromCache: Boolean = false,
)

@HiltViewModel
class PlatformTenderDetailViewModel @Inject constructor(
    private val repository: PlatformRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val id: String = savedStateHandle.get<String>("platformId").orEmpty()

    private val _state = MutableStateFlow(PlatformDetailUi())
    val state: StateFlow<PlatformDetailUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = repository.tenderDetail(id)
            result.fold(
                onSuccess = { dto -> _state.update { PlatformDetailUi(loading = false, tender = dto) } },
                onFailure = { e ->
                    // Falha de rede: tenta o espelho local antes de mostrar erro.
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
}

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
