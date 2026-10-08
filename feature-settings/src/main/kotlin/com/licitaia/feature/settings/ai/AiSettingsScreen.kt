package com.licitaia.feature.settings.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.core.ai.ChatGptAccountInfo
import com.licitaia.core.ai.ChatGptLoginState
import kotlinx.coroutines.delay
import com.licitaia.domain.model.AiAuthMode
import com.licitaia.domain.model.AiConfig
import com.licitaia.domain.model.AiProviderType

private fun AiProviderType.icon(): ImageVector = when (this) {
    AiProviderType.MOCK -> Icons.Outlined.Science
    AiProviderType.OPENAI -> Icons.Outlined.AutoAwesome
    AiProviderType.ANTHROPIC -> Icons.Outlined.Psychology
    AiProviderType.GEMINI -> Icons.Outlined.Cloud
    AiProviderType.CUSTOM -> Icons.Outlined.NetworkCheck
}

private fun AiProviderType.color(): Color = when (this) {
    AiProviderType.MOCK -> LicitaColors.Blue
    AiProviderType.OPENAI -> LicitaColors.Green
    AiProviderType.ANTHROPIC -> LicitaColors.Yellow
    AiProviderType.GEMINI -> LicitaColors.Cyan
    AiProviderType.CUSTOM -> LicitaColors.Purple
}

private fun AiProviderType.description(): String = when (this) {
    AiProviderType.MOCK -> "Respostas simuladas, offline e sem custo. Ideal para demonstração e testes."
    AiProviderType.OPENAI -> "Modelos GPT da OpenAI: com chave de API própria ou entrando com a sua conta do ChatGPT."
    AiProviderType.ANTHROPIC -> "Modelos Claude via API da Anthropic. Requer chave de API própria."
    AiProviderType.GEMINI -> "Modelos Gemini via Google AI. Use uma chave de API ou entre com sua conta Google (cota no seu projeto Google Cloud)."
    AiProviderType.CUSTOM -> "Endpoint compatível com o formato OpenAI (servidor próprio, proxy corporativo ou modelo local)."
}

/** Linha informativa exibida nos provedores sem OAuth público (Anthropic, Custom). */
private const val OAUTH_UNAVAILABLE = "Login com conta: não oferecido por este provedor para apps de terceiros"

/** Aviso obrigatório do "Entrar com ChatGPT" (disponibilidade definida pela OpenAI). */
private const val CHATGPT_NOTICE = "Usa a sua assinatura do ChatGPT. Disponível para uso pessoal; para distribuir a clientes a OpenAI exige aprovação."

/** Página oficial para o usuário acompanhar/gerenciar o uso do plano pelo app. */
private const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"

/** Abre a página oficial no navegador; sem navegador, informa em vez de travar. */
private fun openUrl(context: Context, url: String, onError: (String) -> Unit) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        onError("Nenhum navegador disponível. Acesse $url em outro aparelho.")
    } catch (e: Exception) {
        onError("Não foi possível abrir o navegador.")
    }
}

@Composable
fun AiSettingsScreen(viewModel: AiSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.events.collect { navigator.showMessage(it) } }

    // "Entrar com ChatGPT": servidor de retorno pronto → abre a autorização numa Custom Tab desta Activity.
    val chatGptLogin = state.chatGptLogin
    LaunchedEffect(chatGptLogin) {
        if (chatGptLogin is ChatGptLoginState.OpenBrowser) viewModel.openChatGptBrowser(context)
    }

    // Tela de consentimento/seleção de conta do Google (PendingIntent do AuthorizationClient).
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onGoogleAuthResult(result.resultCode, result.data)
    }
    LaunchedEffect(Unit) {
        viewModel.authIntents.collect { pendingIntent ->
            try {
                consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
            } catch (e: Exception) {
                viewModel.onGoogleAuthResult(android.app.Activity.RESULT_CANCELED, null)
                navigator.showMessage("Não foi possível abrir a tela de autorização do Google.")
            }
        }
    }

    LicitaScaffold(title = "Provedor de IA", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> SkeletonList(items = 4)
                state.noSession -> ErrorState("Sessão encerrada. Entre novamente para configurar a IA.")
                state.error != null -> ErrorState(state.error ?: "")
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.demo) {
                        AlertBanner(
                            "Demonstração: análise heurística local",
                            "No espaço de demonstração a IA real não pode ser configurada nem usada. As análises usam o motor heurístico local. Saia da demonstração e crie sua conta para usar sua chave.",
                            Tone.WARNING,
                        )
                    } else if (!state.canConfigure) {
                        AlertBanner(
                            "Somente leitura",
                            "O perfil ${state.roleLabel} não tem a permissão \"Configurar provedores de IA\". Apenas administradores alteram estas opções.",
                            Tone.WARNING,
                        )
                    }
                    AlertBanner(
                        "Credenciais protegidas pelo Android Keystore",
                        "Chaves de API e tokens de conta são cifrados no aparelho. A análise exige provedor real configurado. Não há fallback fictício; revise os resultados antes de decidir.",
                        Tone.INFO,
                    )
                    if (!state.demo) {
                        val effective = state.effective
                        if (effective != AiProviderType.MOCK) {
                            AlertBanner(
                                "Em uso em todo o app: ${effective.label}",
                                "Este provedor é usado em todo o app: análise do edital, Pergunte ao edital, relevância do radar, " +
                                    "proposta e mensagens do pregoeiro. A conta logada ou a chave de API configurada aqui vale para tudo" +
                                    " (uma empresa só usa outro provedor se isso estiver definido no cadastro dela).",
                                Tone.SUCCESS,
                            )
                        } else {
                            AlertBanner(
                                "Nenhum provedor de IA conectado",
                                "Entre com o ChatGPT ou cadastre uma chave de API abaixo. Este provedor é usado em todo o app; " +
                                    "enquanto nenhum estiver conectado, as análises são heurísticas (regras locais, sem IA).",
                                Tone.WARNING,
                            )
                        }
                    }
                    if (state.activeWithoutKey) {
                        val oauth = state.config(state.active).authMode == AiAuthMode.OAUTH
                        val fallback = state.effective.takeIf { it != AiProviderType.MOCK && it != state.active }
                        AlertBanner(
                            "${state.active.label} está ativo sem ${if (oauth) "conta autorizada" else "chave"}",
                            (if (oauth && state.active == AiProviderType.OPENAI) "Entre com o ChatGPT para habilitar este provedor."
                            else if (oauth) "Entre com sua conta Google para habilitar este provedor."
                            else "Configure sua chave para habilitar este provedor.") +
                                (fallback?.let { " Enquanto isso, o app usa ${it.label}." } ?: ""),
                            Tone.WARNING,
                        )
                    }
                    if (!state.demo) {
                        ScopeSelector(
                            companyName = state.companyName,
                            deviceDefault = state.editDeviceDefault,
                            enabled = true,
                            onSelect = viewModel::setEditDeviceDefault,
                        )
                    }
                    val autoAnalyze by viewModel.autoAnalyzeOnInterest.collectAsStateWithLifecycle()
                    SectionHeader("Quando analisar")
                    com.licitaia.core.ui.components.LicitaCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Analisar automaticamente ao marcar interesse", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                Text(
                                    if (autoAnalyze) "Ligado: o \"Tenho interesse\" baixa o edital oficial e já pede a análise à IA (usa cota do provedor)."
                                    else "Desligado (padrão): a licitação abre com os dados da fonte; a IA só analisa quando você tocar em \"Analisar com IA\".",
                                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.Switch(checked = autoAnalyze, onCheckedChange = viewModel::setAutoAnalyzeOnInterest)
                        }
                    }
                    SectionHeader("Provedores")
                    AiProviderType.entries.filter { it != AiProviderType.MOCK }.forEach { provider ->
                        ProviderCard(
                            provider = provider,
                            config = state.config(provider),
                            isActive = state.active == provider,
                            canConfigure = state.canConfigure,
                            editingDeviceDefault = state.editDeviceDefault,
                            companyName = state.companyName,
                            onUseDeviceDefault = { viewModel.useDeviceDefault(provider) },
                            saving = provider in state.saving,
                            test = state.tests[provider] ?: TestState.Idle,
                            oauth = state.oauth[provider] ?: OAuthFlowState.Idle,
                            onActivate = { viewModel.setActive(provider) },
                            onSave = { model, url, key, project -> viewModel.save(provider, model, url, key, project) },
                            onClearKey = { viewModel.clearKey(provider) },
                            onTest = { viewModel.test(provider) },
                            onAuthMode = { viewModel.setAuthMode(provider, it) },
                            onConnectGoogle = { viewModel.connectGoogle(provider, it) },
                            onDisconnectGoogle = { viewModel.disconnectGoogle(provider) },
                            chatGptLogin = state.chatGptLogin,
                            chatGptAccount = state.chatGptAccount,
                            chatGptModels = state.chatGptModels,
                            onConnectChatGpt = viewModel::connectChatGpt,
                            onCancelChatGpt = viewModel::cancelChatGpt,
                            onOpenUrl = { url -> openUrl(context, url) { navigator.showMessage(it) } },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: AiProviderType,
    config: AiConfig,
    isActive: Boolean,
    canConfigure: Boolean,
    editingDeviceDefault: Boolean,
    companyName: String,
    onUseDeviceDefault: () -> Unit,
    saving: Boolean,
    test: TestState,
    oauth: OAuthFlowState,
    onActivate: () -> Unit,
    onSave: (model: String, baseUrl: String, apiKey: String?, cloudProject: String?) -> Unit,
    onClearKey: () -> Unit,
    onTest: () -> Unit,
    onAuthMode: (AiAuthMode) -> Unit,
    onConnectGoogle: (cloudProject: String) -> Unit,
    onDisconnectGoogle: () -> Unit,
    chatGptLogin: ChatGptLoginState,
    chatGptAccount: ChatGptAccountInfo?,
    chatGptModels: List<String>,
    onConnectChatGpt: () -> Unit,
    onCancelChatGpt: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var expanded by rememberSaveable(provider) { mutableStateOf(isActive && provider != AiProviderType.MOCK) }
    var model by rememberSaveable(provider, config.model) { mutableStateOf(config.model) }
    var baseUrl by rememberSaveable(provider, config.baseUrl) { mutableStateOf(config.baseUrl) }
    var cloudProject by rememberSaveable(provider, config.cloudProject) { mutableStateOf(config.cloudProject.orEmpty()) }
    // A chave fica apenas em memória (não vai para o saved state) e é apagada após salvar.
    var apiKey by remember(provider) { mutableStateOf("") }
    var showKey by remember(provider) { mutableStateOf(false) }
    var confirmClear by remember(provider) { mutableStateOf(false) }
    var confirmDisconnect by remember(provider) { mutableStateOf(false) }
    var confirmUseDefault by remember(provider) { mutableStateOf(false) }
    val color = provider.color()
    val isMock = provider == AiProviderType.MOCK
    val oauthMode = config.authMode == AiAuthMode.OAUTH && provider.supportsOAuth
    val dirty = model != config.model || baseUrl != config.baseUrl || apiKey.isNotBlank() ||
        (oauthMode && cloudProject.trim() != config.cloudProject.orEmpty())
    val isChatGpt = provider == AiProviderType.OPENAI
    val chatGptBusy = isChatGpt && (chatGptLogin is ChatGptLoginState.Starting || chatGptLogin is ChatGptLoginState.OpenBrowser || chatGptLogin is ChatGptLoginState.Waiting)
    val authBusy = oauth == OAuthFlowState.Loading || chatGptBusy

    LicitaCard(Modifier.fillMaxWidth().animateContentSize(), accent = if (isActive) color else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isActive, onClick = onActivate, enabled = canConfigure && !isActive)
            IconBubble(provider.icon(), color, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(provider.label, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isActive) StatusBadge("ATIVO", Tone.SUCCESS)
                    when {
                        isMock -> StatusBadge("MOCK", Tone.INFO)
                        oauthMode && config.hasOAuth -> StatusBadge(if (isChatGpt) "ChatGPT ✓" else "conta Google ✓", Tone.SUCCESS)
                        oauthMode -> StatusBadge("sem conta", Tone.NEUTRAL)
                        config.hasApiKey -> StatusBadge("chave configurada ✓", Tone.SUCCESS)
                        else -> StatusBadge("sem chave", Tone.NEUTRAL)
                    }
                }
                if (!isMock) {
                    Text(
                        if (editingDeviceDefault) "Padrão do aparelho"
                        else if (config.companyScoped) "Configuração desta empresa"
                        else "Padrão do aparelho (esta empresa não tem configuração própria)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (config.companyScoped && !editingDeviceDefault) LicitaColors.BlueBright else LicitaColors.TextMuted,
                    )
                }
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (expanded) "Recolher" else "Expandir", tint = LicitaColors.TextSecondary)
            }
        }
        Text(provider.description(), style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))

        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isMock) {
                    Text(
                        "A IA de demonstração gera análises, propostas e respostas plausíveis a partir de modelos locais, sem acesso à internet. Não há nada a configurar.",
                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                    )
                } else {
                    AuthModeSelector(
                        provider = provider,
                        mode = if (oauthMode) AiAuthMode.OAUTH else AiAuthMode.API_KEY,
                        enabled = canConfigure && !authBusy,
                        onSelect = onAuthMode,
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it.take(80) },
                        label = { Text("Modelo") },
                        placeholder = { Text(provider.defaultModel.ifBlank { "nome-do-modelo" }) },
                        enabled = canConfigure, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // No modo conta ChatGPT o token só vai para https://api.openai.com/v1: a URL base não se aplica.
                    if (!(oauthMode && isChatGpt)) OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it.take(300) },
                        label = { Text(if (provider == AiProviderType.CUSTOM) "URL base (obrigatória)" else "URL base") },
                        placeholder = { Text(provider.defaultBaseUrl.ifBlank { "https://servidor.exemplo.com/v1/" }) },
                        supportingText = { Text(if (provider == AiProviderType.CUSTOM) "Somente HTTPS. Ex.: https://ia.suaempresa.com.br/v1/" else "Deixe o padrão, salvo se usar proxy corporativo.") },
                        isError = provider == AiProviderType.CUSTOM && baseUrl.isNotBlank() && !baseUrl.startsWith("https://"),
                        enabled = canConfigure, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (oauthMode && isChatGpt) {
                        ChatGptAccountSection(
                            config = config,
                            account = chatGptAccount,
                            models = chatGptModels,
                            login = chatGptLogin,
                            flow = oauth,
                            canConfigure = canConfigure,
                            onConnect = onConnectChatGpt,
                            onCancel = onCancelChatGpt,
                            onDisconnect = { confirmDisconnect = true },
                            onOpenUrl = onOpenUrl,
                        )
                    } else if (oauthMode) {
                        GoogleAccountSection(
                            config = config,
                            cloudProject = cloudProject,
                            onCloudProject = { cloudProject = it.take(30).lowercase() },
                            canConfigure = canConfigure,
                            flow = oauth,
                            onConnect = { onConnectGoogle(cloudProject) },
                            onDisconnect = { confirmDisconnect = true },
                        )
                    } else {
                        OutlinedTextField(
                            value = apiKey, onValueChange = { apiKey = it.take(400) },
                            label = { Text(if (config.hasApiKey) "Nova chave de API (opcional)" else "Chave de API") },
                            placeholder = { Text(if (config.hasApiKey) "••••••••  chave configurada" else "Cole a chave aqui") },
                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (showKey) "Ocultar" else "Mostrar")
                                }
                            },
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            enabled = canConfigure, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                            supportingText = {
                                Text(
                                    if (config.hasApiKey) "A chave salva nunca é exibida. Preencha apenas para substituí-la."
                                    else "Cifrada no Android Keystore. Nunca é registrada em logs ou auditoria.",
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, tint = LicitaColors.TextMuted, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Chave guardada fora do banco de dados e fora dos backups do app.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.weight(1f))
                            if (config.hasApiKey && canConfigure) {
                                TextButton(onClick = { confirmClear = true }) { Text("Remover chave", color = LicitaColors.Red) }
                            }
                        }
                        provider.apiKeyUrl?.let { url ->
                            SecondaryButton(
                                "Obter chave de API", { onOpenUrl(url) }, Modifier.fillMaxWidth(),
                                icon = Icons.Outlined.OpenInNew,
                            )
                        }
                    }
                    ButtonRow {
                        PrimaryButton(
                            "Salvar",
                            onClick = {
                                onSave(model, baseUrl, apiKey.takeIf { it.isNotBlank() }, if (oauthMode) cloudProject else null)
                                apiKey = ""
                            },
                            modifier = Modifier.weight(1f), enabled = canConfigure && dirty, loading = saving,
                        )
                        SecondaryButton(
                            if (test == TestState.Running) "Testando…" else "Testar conexão", onTest,
                            Modifier.weight(1f),
                            enabled = test != TestState.Running && !dirty && !authBusy && (!oauthMode || config.hasOAuth),
                            icon = Icons.Outlined.NetworkCheck,
                        )
                    }
                    if (dirty && canConfigure) {
                        Text("Salve as alterações antes de testar a conexão.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
                    }
                    if (!editingDeviceDefault && config.companyScoped && canConfigure) {
                        TextButton(onClick = { confirmUseDefault = true }) {
                            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Usar padrão do aparelho")
                        }
                    } else if (!editingDeviceDefault && !config.companyScoped && canConfigure) {
                        Text(
                            "Ao salvar, a configuração passa a valer só para $companyName. Para alterar o padrão do aparelho, mude o escopo acima.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        )
                    }
                }
                if (isMock) {
                    SecondaryButton(if (test == TestState.Running) "Testando…" else "Testar IA de demonstração", onTest, Modifier.fillMaxWidth(), enabled = test != TestState.Running, icon = Icons.Outlined.NetworkCheck)
                }
                AnimatedVisibility(test is TestState.Done) {
                    val done = test as? TestState.Done
                    if (done != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (done.ok) Icons.Outlined.CheckCircle else Icons.Outlined.Lock, contentDescription = null,
                                tint = if (done.ok) LicitaColors.Green else LicitaColors.Red, modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(done.message, style = MaterialTheme.typography.bodySmall, color = if (done.ok) LicitaColors.GreenBright else LicitaColors.RedBright)
                        }
                    }
                }
                if (!canConfigure) {
                    Text("Edição desabilitada: requer a permissão \"Configurar provedores de IA\".", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Remover chave de ${provider.label}?",
            message = "A chave será apagada do Keystore. Se este provedor estiver ativo, o app voltará a usar a IA de demonstração até uma nova chave ser configurada.",
            confirmLabel = "Remover", tone = Tone.DANGER,
            onConfirm = { confirmClear = false; onClearKey() },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmUseDefault) {
        ConfirmDialog(
            title = "Usar o padrão do aparelho em ${provider.label}?",
            message = "A configuração própria desta empresa (modelo, URL, chave e conta) será apagada do aparelho. A empresa passa a usar o padrão do aparelho, se houver.",
            confirmLabel = "Usar padrão", tone = Tone.WARNING,
            onConfirm = { confirmUseDefault = false; onUseDeviceDefault() },
            onDismiss = { confirmUseDefault = false },
        )
    }
    if (confirmDisconnect) {
        ConfirmDialog(
            title = if (isChatGpt) "Desconectar a conta ChatGPT?" else "Desconectar conta Google de ${provider.label}?",
            message = if (isChatGpt) {
                "O acesso será revogado na OpenAI e os tokens apagados do Keystore deste aparelho. O provedor volta ao modo \"Chave de API\"; se estiver ativo, o app usará a IA de demonstração até você configurar uma credencial."
            } else {
                "O token de acesso será apagado do Keystore e a autorização revogada. O provedor volta ao modo \"Chave de API\"; se estiver ativo, o app usará a IA de demonstração até você configurar uma credencial."
            },
            confirmLabel = "Desconectar", tone = Tone.DANGER,
            onConfirm = { confirmDisconnect = false; onDisconnectGoogle() },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

/** Seletor de escopo: configuração desta empresa (resolvida com fallback) ou padrão do aparelho. */
@Composable
private fun ScopeSelector(companyName: String, deviceDefault: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Text("Escopo da configuração", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !deviceDefault,
                onClick = { onSelect(false) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = { SegmentedButtonDefaults.Icon(active = !deviceDefault) { Icon(Icons.Outlined.Business, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize)) } },
            ) { Text("Esta empresa", maxLines = 1) }
            SegmentedButton(
                selected = deviceDefault,
                onClick = { onSelect(true) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { SegmentedButtonDefaults.Icon(active = deviceDefault) { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize)) } },
            ) { Text("Padrão do aparelho", maxLines = 1) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (deviceDefault) "Vale para toda empresa deste aparelho que não tenha configuração própria. As chaves ficam no Keystore, separadas das chaves por empresa."
            else "Configuração efetiva de $companyName: a própria, se existir; senão o padrão do aparelho. Chaves e contas são guardadas por empresa.",
            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
        )
    }
}

/**
 * Seletor "Autenticação". Com OAuth público (Gemini): [Chave de API] [Entrar com conta].
 * Sem OAuth público (OpenAI, Anthropic, Custom): só "Chave de API" (selecionado) + linha informativa —
 * nenhum botão desabilitado, para não parecer quebrado.
 */
@Composable
private fun AuthModeSelector(
    provider: AiProviderType,
    mode: AiAuthMode,
    enabled: Boolean,
    onSelect: (AiAuthMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Autenticação", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
        if (provider.supportsOAuth) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == AiAuthMode.API_KEY,
                    onClick = { onSelect(AiAuthMode.API_KEY) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(active = mode == AiAuthMode.API_KEY) { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize)) } },
                ) { Text("Chave de API") }
                SegmentedButton(
                    selected = mode == AiAuthMode.OAUTH,
                    onClick = { onSelect(AiAuthMode.OAUTH) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(active = mode == AiAuthMode.OAUTH) { Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize)) } },
                ) { Text(if (provider == AiProviderType.OPENAI) "Entrar com ChatGPT" else "Entrar com conta", maxLines = 1) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Key, contentDescription = null, tint = LicitaColors.TextSecondary, modifier = Modifier.size(18.dp))
                Text("Chave de API", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                StatusBadge("Selecionado", Tone.SUCCESS)
            }
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = LicitaColors.TextMuted, modifier = Modifier.size(14.dp).padding(top = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text(OAUTH_UNAVAILABLE, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
        }
    }
}

/**
 * Bloco do modo "Entrar com ChatGPT": aviso de disponibilidade, botão de entrada, estados do fluxo (abrindo o
 * navegador / aguardando com contagem de 5 min e Cancelar / conectado / erro) e "Desconectar".
 */
@Composable
private fun ChatGptAccountSection(
    config: AiConfig,
    account: ChatGptAccountInfo?,
    models: List<String>,
    login: ChatGptLoginState,
    flow: OAuthFlowState,
    canConfigure: Boolean,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = LicitaColors.TextMuted, modifier = Modifier.size(14.dp).padding(top = 1.dp))
        Spacer(Modifier.width(6.dp))
        Text(CHATGPT_NOTICE, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
    }
    when (login) {
        is ChatGptLoginState.Starting, is ChatGptLoginState.OpenBrowser -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Abrindo o navegador…", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancelar") }
        }
        is ChatGptLoginState.Waiting -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(login.deadlineMs) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            val remaining = ((login.deadlineMs - now) / 1000).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Aguardando autorização no navegador…", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextPrimary)
                    Text(
                        "Tempo restante: %d:%02d. Conclua a entrada na página da OpenAI e volte ao app.".format(remaining / 60, remaining % 60),
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancelar", color = LicitaColors.Red) }
            }
        }
        else -> if (config.hasOAuth) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountCircle, contentDescription = null, tint = LicitaColors.Green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(account?.email ?: config.oauthAccount ?: "conta ChatGPT", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextPrimary)
                    val plan = account?.planType?.takeIf { it.isNotBlank() }?.let { " (plano $it)" }.orEmpty()
                    Text(
                        if (account == null || account.planShared) "Usando o plano do ChatGPT$plan — uso liberado para o LicitaPRO. O acesso é renovado automaticamente."
                        else "O uso do plano não está liberado para o LicitaPRO.",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (account == null || account.planShared) LicitaColors.TextMuted else LicitaColors.Yellow,
                    )
                }
                if (canConfigure) {
                    TextButton(onClick = onDisconnect) { Text("Desconectar", color = LicitaColors.Red) }
                }
            }
            if (models.isNotEmpty()) {
                Text(
                    "Modelos da sua conta: " + models.take(8).joinToString(", ") + if (models.size > 8) "…" else "",
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                )
            }
            TextButton(onClick = { onOpenUrl(CHATGPT_USAGE_URL) }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Gerenciar uso no ChatGPT")
            }
        } else {
            SecondaryButton(
                "Entrar com ChatGPT", onConnect, Modifier.fillMaxWidth(),
                enabled = canConfigure, icon = Icons.Outlined.Login,
            )
            Text(
                "Abre a página oficial da OpenAI no navegador. O LicitaPRO nunca vê sua senha; os tokens ficam cifrados no Keystore deste aparelho.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
        }
    }
    when (flow) {
        OAuthFlowState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Desconectando…", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        }
        OAuthFlowState.Cancelled -> Text("Entrada cancelada.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.Yellow)
        is OAuthFlowState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = LicitaColors.Red, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(flow.message, style = MaterialTheme.typography.bodySmall, color = LicitaColors.RedBright)
        }
        OAuthFlowState.Idle -> Unit
    }
}

/** Bloco do modo "Conta Google": projeto opcional, botão de entrada, estado do fluxo e conta autorizada. */
@Composable
private fun GoogleAccountSection(
    config: AiConfig,
    cloudProject: String,
    onCloudProject: (String) -> Unit,
    canConfigure: Boolean,
    flow: OAuthFlowState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val busy = flow == OAuthFlowState.Loading
    OutlinedTextField(
        value = cloudProject, onValueChange = onCloudProject,
        label = { Text("Projeto Google Cloud (ID) — opcional") },
        placeholder = { Text("meu-projeto-123456") },
        supportingText = { Text("Enviado como x-goog-user-project. Preencha se a API exigir projeto de cota; a cobrança vai para esse projeto.") },
        enabled = canConfigure && !busy, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (config.hasOAuth) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccountCircle, contentDescription = null, tint = LicitaColors.Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(config.oauthAccount ?: "conta Google", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextPrimary)
                Text("Autorizada para a API Gemini (escopo cloud-platform). O token é renovado automaticamente.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
            if (canConfigure) {
                TextButton(onClick = onDisconnect, enabled = !busy) { Text("Desconectar", color = LicitaColors.Red) }
            }
        }
    } else {
        SecondaryButton(
            if (busy) "Aguardando o Google…" else "Entrar com conta Google",
            onConnect,
            Modifier.fillMaxWidth(),
            enabled = canConfigure && !busy,
            icon = Icons.Outlined.Login,
        )
        Text(
            "Abre o seletor de contas do aparelho e pede permissão para usar a API do Google Cloud (Gemini) em seu nome. " +
                "Só o e-mail é exibido; o token fica cifrado no Keystore.",
            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
        )
    }
    when (flow) {
        OAuthFlowState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Autorizando com o Google…", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        }
        OAuthFlowState.Cancelled -> Text("Autorização cancelada.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.Yellow)
        is OAuthFlowState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = LicitaColors.Red, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(flow.message, style = MaterialTheme.typography.bodySmall, color = LicitaColors.RedBright)
        }
        OAuthFlowState.Idle -> Unit
    }
}
