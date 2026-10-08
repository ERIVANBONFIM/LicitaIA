package com.licitaia.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.session.PlatformSession
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlatformAccessViewModel @Inject constructor(
    repository: PlatformRepository,
) : ViewModel() {
    val session: StateFlow<PlatformSession> = repository.session

    init {
        viewModelScope.launch { repository.ensureSessionLoaded() }
    }
}

/**
 * Linha "Entrar na plataforma LicitaPRO" (modo online opcional). Não altera o app local atual: apenas
 * abre o login da plataforma ou, se já autenticado, a lista de licitações sincronizadas.
 */
@Composable
internal fun PlatformAccessRow(viewModel: PlatformAccessViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val signedIn = session as? PlatformSession.SignedIn

    NavRow(
        icon = Icons.Outlined.CloudSync,
        title = "Entrar na plataforma LicitaPRO",
        description = signedIn?.let { "Conectado — ${it.companyName}" }
            ?: "Modo online opcional: sincronize suas licitações da VPS",
        color = LicitaColors.Blue,
        badge = { StatusBadge(if (signedIn != null) "Conectado" else "Novo", if (signedIn != null) Tone.SUCCESS else Tone.INFO) },
    ) {
        navigator.navigate(if (signedIn != null) Routes.PLATFORM_TENDERS else Routes.PLATFORM_LOGIN)
    }
}
