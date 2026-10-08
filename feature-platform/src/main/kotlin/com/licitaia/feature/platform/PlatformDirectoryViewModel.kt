package com.licitaia.feature.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.EmpresaDetailDto
import com.licitaia.core.platform.net.UsuarioDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformDirectoryUi(
    val loading: Boolean = true,
    val empresas: List<EmpresaDetailDto> = emptyList(),
    val usuarios: List<UsuarioDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PlatformDirectoryViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlatformDirectoryUi())
    val state: StateFlow<PlatformDirectoryUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val empresas = repository.empresas()
            val usuarios = repository.usuarios()
            val error = empresas.exceptionOrNull() ?: usuarios.exceptionOrNull()
            _state.update {
                PlatformDirectoryUi(
                    loading = false,
                    empresas = empresas.getOrDefault(emptyList()),
                    usuarios = usuarios.getOrDefault(emptyList()),
                    error = if (empresas.isFailure && usuarios.isFailure) error?.message ?: "Não foi possível carregar." else null,
                )
            }
        }
    }
}
