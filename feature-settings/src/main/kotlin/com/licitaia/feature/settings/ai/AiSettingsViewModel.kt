package com.licitaia.feature.settings.ai

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ai.GoogleAiAuthResult
import com.licitaia.core.ai.GoogleAiAuthorizer
import com.licitaia.domain.model.AiAuthMode
import com.licitaia.domain.model.AiConfig
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.repository.AiConfigRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
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

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Done(val message: String, val ok: Boolean) : TestState
}

/** Estado do fluxo "Entrar com conta Google" de um provedor. O token nunca aparece aqui. */
sealed interface OAuthFlowState {
    data object Idle : OAuthFlowState
    /** Aguardando o Google (chamada silenciosa ou tela de consentimento aberta). */
    data object Loading : OAuthFlowState
    data object Cancelled : OAuthFlowState
    data class Error(val message: String) : OAuthFlowState
}

data class AiSettingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val noSession: Boolean = false,
    val canConfigure: Boolean = false,
    val roleLabel: String = "",
    val configs: List<AiConfig> = emptyList(),
    val active: AiProviderType = AiProviderType.MOCK,
    val tests: Map<AiProviderType, TestState> = emptyMap(),
    val saving: Set<AiProviderType> = emptySet(),
    val oauth: Map<AiProviderType, OAuthFlowState> = emptyMap(),
) {
    fun config(provider: AiProviderType): AiConfig =
        configs.firstOrNull { it.provider == provider }
            ?: AiConfig(provider, provider.defaultModel, provider.defaultBaseUrl, hasApiKey = false)

    /** Provedor ativo sem credencial (chave ou conta, conforme o modo): o app cai para a IA de demonstração. */
    val activeWithoutKey: Boolean get() = active != AiProviderType.MOCK && !config(active).isConfigured
}

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    auth: AuthRepository,
    private val aiConfig: AiConfigRepository,
    private val googleAuth: GoogleAiAuthorizer,
) : ViewModel() {

    private val local = MutableStateFlow(AiSettingsUiState())
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Telas de consentimento do Google que a UI deve lançar (`StartIntentSenderForResult`). */
    private val _authIntents = Channel<PendingIntent>(Channel.BUFFERED)
    val authIntents = _authIntents.receiveAsFlow()

    /** Provedor e projeto Google Cloud do fluxo OAuth em andamento (até o retorno da Activity). */
    private var pendingOAuth: Pair<AiProviderType, String?>? = null

    val state: StateFlow<AiSettingsUiState> = combine(
        auth.session, aiConfig.observeConfigs(), aiConfig.observeActive(), local,
    ) { session, configs, active, l ->
        if (session == null) {
            AiSettingsUiState(loading = false, noSession = true)
        } else {
            l.copy(
                loading = false, error = null,
                canConfigure = Rbac.can(session.user.role, Permission.CONFIGURAR_IA),
                roleLabel = session.user.role.label,
                configs = configs, active = active,
            )
        }
    }
        .catch { emit(AiSettingsUiState(loading = false, error = it.message ?: "Falha ao carregar os provedores de IA.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    fun setActive(provider: AiProviderType) {
        val s = state.value
        if (!s.canConfigure || provider == s.active) return
        viewModelScope.launch {
            runCatching { aiConfig.setActive(provider) }
                .onSuccess {
                    val cfg = s.config(provider)
                    _events.send(
                        when {
                            provider == AiProviderType.MOCK || cfg.isConfigured -> "${provider.label} definido como provedor ativo"
                            cfg.authMode == AiAuthMode.OAUTH -> "${provider.label} ativo — entre com sua conta Google para analisar editais"
                            else -> "${provider.label} ativo — configure a chave para analisar editais"
                        },
                    )
                }
                .onFailure { _events.send(it.message ?: "Não foi possível trocar o provedor") }
        }
    }

    /**
     * [apiKey] vazio/nulo mantém a chave atual; [cloudProject] null mantém o projeto. Nenhum segredo fica no estado da tela.
     */
    fun save(provider: AiProviderType, model: String, baseUrl: String, apiKey: String?, cloudProject: String? = null) {
        val s = state.value
        if (!s.canConfigure || provider in s.saving) return
        val trimmedModel = model.trim()
        val trimmedUrl = baseUrl.trim()
        if (provider != AiProviderType.MOCK && trimmedModel.isBlank()) {
            _events.trySend("Informe o modelo"); return
        }
        if (provider == AiProviderType.CUSTOM && !trimmedUrl.startsWith("https://")) {
            _events.trySend("A URL base é obrigatória e deve usar HTTPS"); return
        }
        if (trimmedUrl.isNotBlank() && !trimmedUrl.startsWith("https://")) {
            _events.trySend("Apenas URLs HTTPS são aceitas"); return
        }
        val project = cloudProject?.trim()
        if (project != null && project.isNotEmpty() && !CLOUD_PROJECT_ID.matches(project)) {
            _events.trySend("ID de projeto Google Cloud inválido (6–30 caracteres: letras minúsculas, dígitos e hifens)"); return
        }
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        local.update { it.copy(saving = it.saving + provider) }
        viewModelScope.launch {
            // A auditoria (CONFIGURACAO) é registrada pelo AiConfigRepository.
            runCatching { aiConfig.saveConfig(provider, trimmedModel, trimmedUrl, key, project) }
                .onSuccess {
                    _events.send("${provider.label}: configuração salva" + if (key != null) " e chave cifrada no Keystore" else "")
                }
                .onFailure { _events.send(it.message ?: "Não foi possível salvar a configuração") }
            local.update { it.copy(saving = it.saving - provider) }
        }
    }

    fun clearKey(provider: AiProviderType) {
        if (!state.value.canConfigure) return
        viewModelScope.launch {
            runCatching { aiConfig.clearApiKey(provider) }
                .onSuccess {
                    _events.send("Chave de ${provider.label} removida")
                }
                .onFailure { _events.send(it.message ?: "Não foi possível remover a chave") }
        }
    }

    fun setAuthMode(provider: AiProviderType, mode: AiAuthMode) {
        val s = state.value
        if (!s.canConfigure || s.config(provider).authMode == mode) return
        if (mode == AiAuthMode.OAUTH && !provider.supportsOAuth) {
            _events.trySend("${provider.label} não oferece login para apps de terceiros. Use uma chave de API."); return
        }
        viewModelScope.launch {
            runCatching { aiConfig.setAuthMode(provider, mode) }
                .onFailure { _events.send(it.message ?: "Não foi possível trocar o modo de autenticação") }
        }
    }

    /**
     * "Entrar com conta Google": tenta autorizar sem UI; se o Google exigir consentimento, emite o
     * PendingIntent em [authIntents] e o resultado volta por [onGoogleAuthResult].
     */
    fun connectGoogle(provider: AiProviderType, cloudProject: String) {
        val s = state.value
        if (!s.canConfigure || !provider.supportsOAuth || s.oauth[provider] == OAuthFlowState.Loading) return
        val project = cloudProject.trim().takeIf { it.isNotEmpty() }
        if (project != null && !CLOUD_PROJECT_ID.matches(project)) {
            setOAuth(provider, OAuthFlowState.Error("ID de projeto Google Cloud inválido (6–30 caracteres: letras minúsculas, dígitos e hifens)."))
            return
        }
        setOAuth(provider, OAuthFlowState.Loading)
        viewModelScope.launch {
            when (val result = googleAuth.begin()) {
                is GoogleAiAuthResult.NeedsResolution -> {
                    pendingOAuth = provider to project
                    _authIntents.send(result.pendingIntent)
                }
                else -> finishOAuth(provider, project, result)
            }
        }
    }

    /** Retorno da tela de consentimento do Google (Activity result). */
    fun onGoogleAuthResult(resultCode: Int, data: Intent?) {
        val (provider, project) = pendingOAuth ?: return
        pendingOAuth = null
        viewModelScope.launch {
            finishOAuth(provider, project, googleAuth.complete(resultCode, data))
        }
    }

    fun disconnectGoogle(provider: AiProviderType) {
        if (!state.value.canConfigure) return
        setOAuth(provider, OAuthFlowState.Loading)
        viewModelScope.launch {
            runCatching { aiConfig.clearOAuth(provider) }
                .onSuccess { _events.send("Conta Google desconectada de ${provider.label}") }
                .onFailure { _events.send(it.message ?: "Não foi possível desconectar a conta") }
            setOAuth(provider, OAuthFlowState.Idle)
        }
    }

    private suspend fun finishOAuth(provider: AiProviderType, project: String?, result: GoogleAiAuthResult) {
        when (result) {
            is GoogleAiAuthResult.Granted -> {
                val account = result.account ?: "conta Google"
                runCatching { aiConfig.saveOAuth(provider, account, project) }
                    .onSuccess {
                        setOAuth(provider, OAuthFlowState.Idle)
                        _events.send("${provider.label}: conta $account autorizada")
                    }
                    .onFailure {
                        // Token já está no cofre, mas a configuração não pôde ser gravada: limpa para não ficar meio autorizado.
                        runCatching { googleAuth.revoke() }
                        setOAuth(provider, OAuthFlowState.Error(it.message ?: "Não foi possível registrar a autorização."))
                    }
            }
            is GoogleAiAuthResult.Cancelled -> setOAuth(provider, OAuthFlowState.Cancelled)
            is GoogleAiAuthResult.Failure -> setOAuth(provider, OAuthFlowState.Error(result.message))
            is GoogleAiAuthResult.NeedsResolution -> setOAuth(provider, OAuthFlowState.Error("A autorização não foi concluída. Tente novamente."))
        }
    }

    private fun setOAuth(provider: AiProviderType, flow: OAuthFlowState) {
        local.update { it.copy(oauth = it.oauth + (provider to flow)) }
    }

    fun test(provider: AiProviderType) {
        if (state.value.tests[provider] == TestState.Running) return
        local.update { it.copy(tests = it.tests + (provider to TestState.Running)) }
        viewModelScope.launch {
            val result = runCatching { aiConfig.testConnection(provider).getOrThrow() }
            val done = result.fold(
                onSuccess = { TestState.Done(it, ok = true) },
                onFailure = { TestState.Done(it.message ?: "Falha na conexão", ok = false) },
            )
            local.update { it.copy(tests = it.tests + (provider to done)) }
        }
    }

    private companion object {
        /** Formato oficial de ID de projeto Google Cloud. */
        val CLOUD_PROJECT_ID = Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")
    }
}
