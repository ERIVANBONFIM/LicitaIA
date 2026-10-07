package com.licitaia.feature.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SimulationBadge
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.repository.AiConfigRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.SettingsRepository
import com.licitaia.domain.update.AppUpdateChecker
import com.licitaia.domain.update.UpdateCheckResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val settings: AppSettings = AppSettings(),
    val session: AuthSession? = null,
    val activeAi: AiProviderType = AiProviderType.MOCK,
)

/** Resultado da verificação manual de atualização mostrado na tela. */
data class UpdateCheckUi(val checking: Boolean = false, val message: String? = null, val tone: Tone = Tone.INFO)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    auth: AuthRepository,
    private val settingsRepository: SettingsRepository,
    aiConfig: AiConfigRepository,
    private val updateChecker: AppUpdateChecker,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings, auth.session, aiConfig.observeActive().catch { emit(AiProviderType.MOCK) },
    ) { settings, session, ai ->
        SettingsUiState(loading = false, settings = settings, session = session, activeAi = ai)
    }
        .catch { emit(SettingsUiState(loading = false, error = it.message ?: "Falha ao carregar as configurações.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _updateCheck = MutableStateFlow(UpdateCheckUi())
    val updateCheck: StateFlow<UpdateCheckUi> = _updateCheck.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { runCatching { settingsRepository.update(transform) } }
    }

    /** Verificação manual (GitHub Releases). Se houver versão nova, o diálogo global é aberto pelo app. */
    fun checkUpdates() {
        if (_updateCheck.value.checking) return
        _updateCheck.value = UpdateCheckUi(checking = true)
        viewModelScope.launch {
            val result = runCatching { updateChecker.checkNow() }
                .getOrElse { UpdateCheckResult.Failed(it.message ?: "Falha ao verificar atualizações.") }
            _updateCheck.value = when (result) {
                is UpdateCheckResult.Available -> UpdateCheckUi(message = "Nova versão ${result.update.versionName} (${result.update.tag}) disponível.", tone = Tone.SUCCESS)
                UpdateCheckResult.UpToDate -> UpdateCheckUi(message = "Você já está na versão mais recente.", tone = Tone.SUCCESS)
                is UpdateCheckResult.NoRelease -> UpdateCheckUi(message = result.reason, tone = Tone.WARNING)
                UpdateCheckResult.Offline -> UpdateCheckUi(message = "Sem conexão. Conecte-se à internet e tente novamente.", tone = Tone.WARNING)
                is UpdateCheckResult.Failed -> UpdateCheckUi(message = result.message, tone = Tone.DANGER)
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val updateCheck by viewModel.updateCheck.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    LicitaScaffold(title = "Configurações", showBack = false) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> SkeletonList(items = 3)
                state.error != null -> ErrorState(state.error ?: "")
                else -> {
                    val s = state.settings
                    val haptic = s.hapticFeedback
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionHeader("Áreas")
                        NavRow(
                            Icons.Outlined.AutoAwesome, "Provedor de IA",
                            if (state.activeAi == AiProviderType.MOCK) "Configure um provedor real" else "Ativo: ${state.activeAi.label}", LicitaColors.Purple,
                            badge = { if (state.activeAi == AiProviderType.MOCK) StatusBadge("Configurar", Tone.WARNING) },
                        ) { navigator.navigate(Routes.AI_SETTINGS) }
                        NavRow(
                            Icons.Outlined.Security, "Segurança",
                            "Biometria, PIN, tempo de sessão e captura de tela", LicitaColors.Green,
                            badge = { if (s.biometricLock) StatusBadge("Bloqueio ativo", Tone.SUCCESS) },
                        ) { navigator.navigate(Routes.SECURITY) }
                        NavRow(Icons.Outlined.Hub, "Portais conectados", "Compras.gov, BLL, Licitanet e PCP", LicitaColors.Cyan) { navigator.navigate(Routes.PORTALS) }
                        NavRow(Icons.Outlined.Business, "Empresas e perfis", "Multiempresa, usuários e matriz de permissões", LicitaColors.Yellow) { navigator.navigate(Routes.COMPANIES) }

                        SectionHeader("Busca de licitações")
                        DailySyncCard(haptic = haptic)

                        SectionHeader("Preservação dos dados")
                        BackupCard()
                        SectionHeader("Alertas de CAPTCHA")
                        LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Red) {
                            OptionChips(
                                title = "Repetir alerta de CAPTCHA a cada",
                                description = "Enquanto um CAPTCHA estiver pendente a sessão fica pausada e o alerta se repete neste intervalo até ser resolvido ou pausado manualmente. O LicitaIA nunca resolve CAPTCHA automaticamente.",
                                options = AppSettings.CAPTCHA_REPEAT_OPTIONS,
                                selected = s.captchaRepeatMinutes,
                                label = ::minutesLabel,
                                onSelect = { v -> viewModel.update { it.copy(captchaRepeatMinutes = v) } },
                                color = LicitaColors.Red,
                            )
                            if (s.captchaRepeatMinutes == 0) {
                                AlertBanner("Repetição desativada", "Você receberá apenas o primeiro alerta de cada CAPTCHA. Recomendamos 3 ou 5 minutos durante disputas.", Tone.WARNING)
                                Spacer(Modifier.height(4.dp))
                            }
                            HorizontalDivider(color = LicitaColors.OutlineSoft)
                            SwitchRow("Vibrar no CAPTCHA", "Vibração junto ao alerta sonoro/visual", s.captchaVibrate, { v -> viewModel.update { it.copy(captchaVibrate = v) } }, haptic = haptic)
                        }

                        SectionHeader("Notificações e interface")
                        LicitaCard(Modifier.fillMaxWidth()) {
                            SwitchRow("Push local", "Notificações do sistema para lances, mensagens, documentos e sessões", s.pushEnabled, { v -> viewModel.update { it.copy(pushEnabled = v) } }, haptic = haptic)
                            HorizontalDivider(color = LicitaColors.OutlineSoft)
                            SwitchRow("Feedback tátil", "Vibração leve ao alternar opções e confirmar ações", s.hapticFeedback, { v -> viewModel.update { it.copy(hapticFeedback = v) } }, haptic = true)
                            HorizontalDivider(color = LicitaColors.OutlineSoft)
                            SwitchRow("Mostrar barra inferior", "Atalhos rápidos para Dashboard, Ao vivo, Mensagens e Sala de Guerra", s.showBottomBar, { v -> viewModel.update { it.copy(showBottomBar = v) } }, haptic = haptic)
                        }

                        SectionHeader("Sobre")
                        LicitaCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBubble(Icons.Outlined.Shield, LicitaColors.Blue)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("LicitaIA", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                                    Text("Versão $version", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                                }
                                StatusBadge("Uso pessoal", Tone.INFO)
                            }
                            Spacer(Modifier.height(10.dp))
                            InfoRow("Usuário", state.session?.user?.name ?: "—")
                            InfoRow("Perfil", state.session?.user?.role?.label ?: "—")
                            InfoRow("Empresa ativa", state.session?.activeCompany?.let { it.tradeName.ifBlank { it.name } } ?: "—")
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = LicitaColors.OutlineSoft)
                            Spacer(Modifier.height(8.dp))
                            // Atualização via GitHub Releases (manual; a automática roda ao abrir o app, a cada 6 h).
                            Row(
                                Modifier.fillMaxWidth().let { m -> if (updateCheck.checking) m else m.clickable(onClick = viewModel::checkUpdates) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconBubble(Icons.Outlined.SystemUpdate, LicitaColors.Cyan)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Verificar atualizações", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                    Text(
                                        if (updateCheck.checking) "Consultando GitHub Releases…" else "Verificação automática ao abrir o app (a cada 6 h). Nada é baixado sem a sua confirmação.",
                                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                                    )
                                }
                                if (updateCheck.checking) {
                                    CircularProgressIndicator(Modifier.padding(start = 8.dp).height(22.dp).width(22.dp), strokeWidth = 2.dp, color = LicitaColors.Cyan)
                                } else {
                                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = LicitaColors.TextMuted)
                                }
                            }
                            updateCheck.message?.let { msg ->
                                Spacer(Modifier.height(8.dp))
                                AlertBanner("Atualizações", msg, updateCheck.tone)
                            }
                            Spacer(Modifier.height(8.dp))
                            AlertBanner(
                                "Operação assistida",
                                "Busca pública no PNCP e acesso manual aos portais oficiais. Login, CAPTCHA, propostas e lances são operados por você no portal. A análise de edital exige provedor de IA configurado.",
                                Tone.INFO,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

private fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    "${info.versionName ?: "dev"} ($code)"
}.getOrDefault("dev")
