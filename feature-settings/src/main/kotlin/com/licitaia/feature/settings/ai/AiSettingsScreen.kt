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
import androidx.compose.material.icons.outlined.CheckCircle
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
    AiProviderType.OPENAI -> "Modelos GPT via API da OpenAI. Requer chave de API própria."
    AiProviderType.ANTHROPIC -> "Modelos Claude via API da Anthropic. Requer chave de API própria."
    AiProviderType.GEMINI -> "Modelos Gemini via Google AI. Use uma chave de API ou entre com sua conta Google (cota no seu projeto Google Cloud)."
    AiProviderType.CUSTOM -> "Endpoint compatível com o formato OpenAI (servidor próprio, proxy corporativo ou modelo local)."
}

/** Por que "Entrar com conta" está indisponível para este provedor. */
private const val OAUTH_UNAVAILABLE =
    "Indisponível: este provedor não oferece login para apps de terceiros (OpenAI: 'Sign in with ChatGPT' em beta restrito a parceiros). Use uma chave de API."

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
                    if (!state.canConfigure) {
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
                    if (state.activeWithoutKey) {
                        val oauth = state.config(state.active).authMode == AiAuthMode.OAUTH
                        AlertBanner(
                            "${state.active.label} está ativo sem ${if (oauth) "conta autorizada" else "chave"}",
                            if (oauth) "Entre com sua conta Google para habilitar a análise. Falhas de conexão serão informadas."
                            else "Configure sua chave para habilitar a análise. Falhas de conexão serão informadas.",
                            Tone.WARNING,
                        )
                    }
                    SectionHeader("Provedores")
                    AiProviderType.entries.filter { it != AiProviderType.MOCK }.forEach { provider ->
                        ProviderCard(
                            provider = provider,
                            config = state.config(provider),
                            isActive = state.active == provider,
                            canConfigure = state.canConfigure,
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
    val color = provider.color()
    val isMock = provider == AiProviderType.MOCK
    val oauthMode = config.authMode == AiAuthMode.OAUTH && provider.supportsOAuth
    val dirty = model != config.model || baseUrl != config.baseUrl || apiKey.isNotBlank() ||
        (oauthMode && cloudProject.trim() != config.cloudProject.orEmpty())
    val authBusy = oauth == OAuthFlowState.Loading

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
                        oauthMode && config.hasOAuth -> StatusBadge("conta Google ✓", Tone.SUCCESS)
                        oauthMode -> StatusBadge("sem conta", Tone.NEUTRAL)
                        config.hasApiKey -> StatusBadge("chave configurada ✓", Tone.SUCCESS)
                        else -> StatusBadge("sem chave", Tone.NEUTRAL)
                    }
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
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it.take(300) },
                        label = { Text(if (provider == AiProviderType.CUSTOM) "URL base (obrigatória)" else "URL base") },
                        placeholder = { Text(provider.defaultBaseUrl.ifBlank { "https://servidor.exemplo.com/v1/" }) },
                        supportingText = { Text(if (provider == AiProviderType.CUSTOM) "Somente HTTPS. Ex.: https://ia.suaempresa.com.br/v1/" else "Deixe o padrão, salvo se usar proxy corporativo.") },
                        isError = provider == AiProviderType.CUSTOM && baseUrl.isNotBlank() && !baseUrl.startsWith("https://"),
                        enabled = canConfigure, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (oauthMode) {
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
                            TextButton(onClick = { onOpenUrl(url) }) {
                                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Obter chave de API")
                            }
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
    if (confirmDisconnect) {
        ConfirmDialog(
            title = "Desconectar conta Google de ${provider.label}?",
            message = "O token de acesso será apagado do Keystore e a autorização revogada. O provedor volta ao modo \"Chave de API\"; se estiver ativo, o app usará a IA de demonstração até você configurar uma credencial.",
            confirmLabel = "Desconectar", tone = Tone.DANGER,
            onConfirm = { confirmDisconnect = false; onDisconnectGoogle() },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

/** Seletor "Autenticação": [Chave de API] [Entrar com conta]. A 2ª opção fica desabilitada onde não há OAuth público. */
@Composable
private fun AuthModeSelector(
    provider: AiProviderType,
    mode: AiAuthMode,
    enabled: Boolean,
    onSelect: (AiAuthMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Autenticação", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
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
                enabled = enabled && provider.supportsOAuth,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { SegmentedButtonDefaults.Icon(active = mode == AiAuthMode.OAUTH) { Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize)) } },
            ) { Text("Entrar com conta") }
        }
        if (!provider.supportsOAuth) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = LicitaColors.TextMuted, modifier = Modifier.size(14.dp).padding(top = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text(OAUTH_UNAVAILABLE, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
        }
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
