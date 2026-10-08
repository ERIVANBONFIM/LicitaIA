package com.licitaia.feature.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.db.PlatformTenderEntity
import com.licitaia.core.platform.session.PlatformSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformSyncUi(
    val syncing: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class PlatformTendersViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {

    val session: StateFlow<PlatformSession> = repository.session

    val tenders: StateFlow<List<PlatformTenderEntity>> =
        repository.observeTenders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingMutations: StateFlow<Int> =
        repository.observePendingMutations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _sync = MutableStateFlow(PlatformSyncUi())
    val sync: StateFlow<PlatformSyncUi> = _sync.asStateFlow()

    init {
        viewModelScope.launch { repository.ensureSessionLoaded() }
        refresh()
    }

    fun refresh() {
        if (_sync.value.syncing) return
        _sync.update { it.copy(syncing = true, message = null, isError = false) }
        viewModelScope.launch {
            val result = repository.syncTenders()
            _sync.update {
                result.fold(
                    onSuccess = { r -> PlatformSyncUi(syncing = false, message = "Sincronizado: ${r.totalLocal} licitações.") },
                    onFailure = { e -> PlatformSyncUi(syncing = false, message = e.message ?: "Falha ao sincronizar.", isError = true) },
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }
}
