package com.licitaia.feature.platform

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.PlatformFile
import com.licitaia.core.platform.net.PlatformItem
import com.licitaia.core.platform.net.TenderDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
)

@HiltViewModel
class PlatformTenderDetailViewModel @Inject constructor(
    private val repository: PlatformRepository,
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
                    // Itens e arquivos em paralelo (best-effort; não bloqueiam o detalhe).
                    val itens = repository.tenderItens(id).getOrDefault(emptyList())
                    val arquivos = repository.tenderArquivos(id).getOrDefault(emptyList())
                    _state.update { it.copy(itens = itens, arquivos = arquivos) }
                },
                onFailure = { e ->
                    // Falha de rede/VPS: abre a cópia do espelho local se existir.
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
