package com.licitaia.feature.settings.ai

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ai.ChatGptAccountInfo
import com.licitaia.core.ai.ChatGptAuthorizer
import com.licitaia.core.ai.ChatGptLoginState
import com.licitaia.core.ai.ChatGptOAuthClient
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    /** Sessão de demonstração: só heurística local; nada de IA real pode ser configurado. */
    val demo: Boolean = false,
    val companyId: Long = 0,
    val companyName: String = "",
    /** true = a tela mostra/edita o padrão do aparelho; false = a configuração resolvida para esta empresa. */
    val editDeviceDefault: Boolean = false,
    val configs: List<AiConfig> = emptyList(),
    val active: AiProviderType = AiProviderType.MOCK,
    val tests: Map<AiProviderType, TestState> = emptyMap(),
    val saving: Set<AiProviderType> = emptySet(),
    val oauth: Map<AiProviderType, OAuthFlowState> = emptyMap(),
    /** Fluxo "Entrar com ChatGPT" em andamento (servidor de retorno + navegador). */
    val chatGptLogin: ChatGptLoginState = ChatGptLoginState.Idle,
    /** Conta ChatGPT conectada no escopo exibido (e-mail, plano, uso liberado). Sem tokens. */
    val chatGptAccount: ChatGptAccountInfo? = null,
    /** Modelos da conta ChatGPT (de `/v1/models`), só como sugestão. */
    val chatGptModels: List<String> = emptyList(),
) {
    fun config(provider: AiProviderType): AiConfig =
        configs.firstOrNull { it.provider == provider }
            ?: AiConfig(provider, provider.defaultModel, provider.defaultBaseUrl, hasApiKey = false)

    /** Provedor ativo sem credencial (chave ou conta, conforme o modo): o app cai para a IA de demonstração. */
    val activeWithoutKey: Boolean get() = active != AiProviderType.MOCK && !config(active).isConfigured
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    auth: AuthRepository,
    private val aiConfig: AiConfigRepository,
    private val googleAuth: GoogleAiAuthorizer,
    private val chatGpt: ChatGptAuthorizer,
) : ViewModel() {

    private val local = MutableStateFlow(AiSettingsUiState())
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Telas de consentimento do Google que a UI deve lançar (`StartIntentSenderForResult`). */
    private val _authIntents = Channel<PendingIntent>(Channel.BUFFERED)
    val authIntents = _authIntents.receiveAsFlow()

    /** Provedor e projeto Google Cloud do fluxo OAuth em andamento (até o retorno da Activity). */
    private var pendingOAuth: Pair<AiProviderType, String?>? = null

    private val configs = local.map { it.editDeviceDefault }.distinctUntilChanged().flatMapLatest { aiConfig.observeConfigs(deviceDefault = it) }

    private val sessionConfigs = combine(auth.session, configs, aiConfig.observeActive()) { session, configs, active -> Triple(session, configs, active) }

    val state: StateFlow<AiSettingsUiState> = combine(
        sessionConfigs, local, chatGpt.state,
    ) { (session, configs, active), l, login ->
        if (session == null) {
            AiSettingsUiState(loading = false, noSession = true)
        } else {
            val demo = session.user.demo || session.activeCompany.demo
            l.copy(
                loading = false, error = null,
                canConfigure = !demo && Rbac.can(session.user.role, Permission.CONFIGURAR_IA),
                roleLabel = session.user.role.label,
                demo = demo,
                companyId = session.activeCompany.id,
                companyName = session.activeCompany.tradeName.ifBlank { session.activeCompany.name },
                configs = configs, active = active,
                chatGptLogin = login,
            )
        }
    }
        .catch { emit(AiSettingsUiState(loading = false, error = it.message ?: "Falha ao carregar os provedores de IA.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    init {
        // O resultado do login vive no singleton: mesmo se a tela foi recriada, ele é registrado aqui uma vez.
        viewModelScope.launch {
            chatGpt.state.collect { login ->
                when (login) {
                    is ChatGptLoginState.Connected -> onChatGptConnected(login)
                    is ChatGptLoginState.Failed -> {
                        setOAuth(AiProviderType.OPENAI, if (login.cancelled) OAuthFlowState.Cancelled else OAuthFlowState.Error(login.message))
                        chatGpt.acknowledge()
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            local.map { it.editDeviceDefault }.distinctUntilChanged().collect { refreshChatGptInfo() }
        }
    }

    /** Alterna entre editar a configuração desta empresa e o padrão do aparelho. */
    fun setEditDeviceDefault(deviceDefault: Boolean) = local.update { it.copy(editDeviceDefault = deviceDefault, tests = emptyMap()) }

    private suspend fun refreshChatGptInfo() {
        val ready = state.first { !it.loading }
        val scope = if (ready.editDeviceDefault) 0L else ready.companyId
        val info = runCatching { chatGpt.accountInfo(scope) }.getOrNull()
        local.update { it.copy(chatGptAccount = info, chatGptModels = if (info == null) emptyList() else it.chatGptModels) }
    }

    // ------------------------------------------------------------------ Entrar com ChatGPT

    /** "Entrar com ChatGPT": abre o servidor de retorno; a tela abre a Custom Tab ao ver [ChatGptLoginState.OpenBrowser]. */
    fun connectChatGpt() {
        val s = state.value
        if (!s.canConfigure) return
        val busy = s.chatGptLogin is ChatGptLoginState.Starting || s.chatGptLogin is ChatGptLoginState.OpenBrowser ||
            s.chatGptLogin is ChatGptLoginState.Waiting
        if (busy) return
        setOAuth(AiProviderType.OPENAI, OAuthFlowState.Idle)
        chatGpt.begin(oauthScope())
    }

    /** Chamado pela tela (com o contexto da Activity) quando o servidor de retorno está pronto. */
    fun openChatGptBrowser(activityContext: Context) = chatGpt.launchBrowser(activityContext)

    fun cancelChatGpt() = chatGpt.cancel()

    private suspend fun onChatGptConnected(login: ChatGptLoginState.Connected) {
        val s = state.first { !it.loading }
        val deviceDefault = login.companyId == 0L
        if (!deviceDefault && login.companyId != s.companyId) {
            // A empresa ativa mudou durante a entrada: não associa a conta à empresa errada.
            runCatching { chatGpt.revoke(login.companyId) }
            setOAuth(AiProviderType.OPENAI, OAuthFlowState.Error("A empresa ativa mudou durante a entrada. Entre de novo."))
            chatGpt.acknowledge()
            return
        }
        val account = login.account.email ?: login.account.name ?: "conta ChatGPT"
        runCatching { aiConfig.saveOAuth(AiProviderType.OPENAI, account, null, deviceDefault = deviceDefault) }
            .onSuccess {
                setOAuth(AiProviderType.OPENAI, OAuthFlowState.Idle)
                // Modelo padrão da conta quando o atual não está disponível nela.
                val cfg = s.config(AiProviderType.OPENAI)
                if (login.models.isNotEmpty() && s.editDeviceDefault == deviceDefault) {
                    val chosen = ChatGptOAuthClient.chooseModel(login.models, cfg.model, AiProviderType.OPENAI.defaultModel)
                    if (chosen != cfg.model) {
                        runCatching { aiConfig.saveConfig(AiProviderType.OPENAI, chosen, cfg.baseUrl, null, null, deviceDefault = deviceDefault) }
                    }
                }
                local.update { it.copy(chatGptAccount = login.account, chatGptModels = login.models.take(40)) }
                _events.send("ChatGPT conectado: $account")
            }
            .onFailure {
                runCatching { chatGpt.revoke(login.companyId) }
                setOAuth(AiProviderType.OPENAI, OAuthFlowState.Error(it.message ?: "Não foi possível registrar a conta ChatGPT."))
            }
        chatGpt.acknowledge()
    }

    /** Escopo das credenciais OAuth no cofre: 0 = padrão do aparelho; senão a empresa ativa. */
    private fun oauthScope(): Long = if (state.value.editDeviceDefault) 0L else state.value.companyId

    /** "Usar padrão do aparelho": apaga a configuração própria desta empresa para o provedor. */
    fun useDeviceDefault(provider: AiProviderType) {
        val s = state.value
        if (!s.canConfigure || s.editDeviceDefault) return
        viewModelScope.launch {
            runCatching { aiConfig.useDeviceDefault(provider) }
                .onSuccess { _events.send("${provider.label}: esta empresa passa a usar o padrão do aparelho") }
                .onFailure { _events.send(it.message ?: "Não foi possível remover a configuração da empresa") }
        }
    }

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
                            cfg.authMode == AiAuthMode.OAUTH && provider == AiProviderType.OPENAI -> "${provider.label} ativo — entre com o ChatGPT para analisar editais"
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
            val scopeLabel = if (s.editDeviceDefault) "padrão do aparelho" else s.companyName
            runCatching { aiConfig.saveConfig(provider, trimmedModel, trimmedUrl, key, project, deviceDefault = s.editDeviceDefault) }
                .onSuccess {
                    _events.send("${provider.label}: configuração salva ($scopeLabel)" + if (key != null) " e chave cifrada no Keystore" else "")
                }
                .onFailure { _events.send(it.message ?: "Não foi possível salvar a configuração") }
            local.update { it.copy(saving = it.saving - provider) }
        }
    }

    fun clearKey(provider: AiProviderType) {
        if (!state.value.canConfigure) return
        viewModelScope.launch {
            runCatching { aiConfig.clearApiKey(provider, deviceDefault = state.value.editDeviceDefault) }
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
            runCatching { aiConfig.setAuthMode(provider, mode, deviceDefault = s.editDeviceDefault) }
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
            when (val result = googleAuth.begin(oauthScope())) {
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
            finishOAuth(provider, project, googleAuth.complete(resultCode, data, oauthScope()))
        }
    }

    fun disconnectGoogle(provider: AiProviderType) {
        if (!state.value.canConfigure) return
        setOAuth(provider, OAuthFlowState.Loading)
        viewModelScope.launch {
            runCatching { aiConfig.clearOAuth(provider, deviceDefault = state.value.editDeviceDefault) }
                .onSuccess {
                    if (provider == AiProviderType.OPENAI) local.update { it.copy(chatGptAccount = null, chatGptModels = emptyList()) }
                    _events.send("${if (provider == AiProviderType.OPENAI) "Conta ChatGPT" else "Conta Google"} desconectada de ${provider.label}")
                }
                .onFailure { _events.send(it.message ?: "Não foi possível desconectar a conta") }
            setOAuth(provider, OAuthFlowState.Idle)
        }
    }

    private suspend fun finishOAuth(provider: AiProviderType, project: String?, result: GoogleAiAuthResult) {
        when (result) {
            is GoogleAiAuthResult.Granted -> {
                val account = result.account ?: "conta Google"
                runCatching { aiConfig.saveOAuth(provider, account, project, deviceDefault = state.value.editDeviceDefault) }
                    .onSuccess {
                        setOAuth(provider, OAuthFlowState.Idle)
                        _events.send("${provider.label}: conta $account autorizada")
                    }
                    .onFailure {
                        // Token já está no cofre, mas a configuração não pôde ser gravada: limpa para não ficar meio autorizado.
                        runCatching { googleAuth.revoke(oauthScope()) }
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
