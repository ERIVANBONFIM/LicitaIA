package com.licitaia.feature.settings.portals

import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.feature.live.web.keepAliveHonestText
import com.licitaia.feature.settings.OptionChips
import com.licitaia.feature.settings.SwitchRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PortalsScreen(vm: PortalsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LaunchedEffect(Unit) { vm.events.collect { navigator.showMessage(it) } }

    state.confirmSignOut?.let { portal ->
        ConfirmDialog(
            title = "Sair de ${portal.displayName}?",
            message = "Os cookies deste portal serão removidos do navegador interno deste aparelho e o status voltará a \"Sem sessão\". Você precisará fazer login novamente no portal.",
            onConfirm = { vm.signOut(portal) },
            onDismiss = vm::dismissSignOut,
            confirmLabel = "Sair do portal", tone = Tone.DANGER, icon = Icons.AutoMirrored.Outlined.Logout,
        )
    }

    LicitaScaffold(title = "Portais oficiais", showBack = false) { padding ->
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            state.noSession -> ErrorState("Entre no LicitaIA e selecione a empresa.", Modifier.padding(padding), title = "Sem empresa ativa")
            state.error != null -> ErrorState(state.error ?: "", Modifier.padding(padding), onRetry = vm::retry)
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AlertBanner(
                    "Operação assistida",
                    "Você entra no portal pelo navegador interno do app, digitando login e senha somente na página oficial. CAPTCHA e MFA são sempre resolvidos por você. O app não acompanha lances nem executa ações no portal; confira a empresa ativa (${state.companyName}) no portal antes de enviar proposta, mensagem ou lance.",
                    Tone.INFO,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.connectedCount} sessão(ões) aberta(s)", style = MaterialTheme.typography.titleMedium,
                        color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f),
                    )
                    StatusBadge(state.roleLabel, Tone.NEUTRAL)
                }
                state.rows.forEach { row ->
                    PortalCard(
                        row = row,
                        canManage = state.canManage,
                        busy = row.portal in state.signingOut,
                        onOpen = { navigator.navigate(Routes.portalWeb(row.portal)) },
                        onSignOut = { vm.askSignOut(row.portal) },
                        keepAliveMinutes = state.keepAliveMinutes,
                        onKeepAlive = { vm.setKeepAlive(row.portal, it) },
                        onAutoCertLogin = { vm.setAutoCertLogin(row.portal, it) },
                    )
                }
                if (state.rows.any { it.requiresLogin }) {
                    CertificateHelpCard()
                    LicitaCard(Modifier.fillMaxWidth()) {
                        OptionChips(
                            title = "Intervalo do \"Manter sessão ativa\"",
                            description = "De quanto em quanto tempo o app recarrega sua página do portal (só nos portais com a opção ligada).",
                            options = AppSettings.PORTAL_KEEP_ALIVE_OPTIONS,
                            selected = state.keepAliveMinutes,
                            label = { "$it min" },
                            onSelect = vm::setKeepAliveMinutes,
                        )
                    }
                }
                Text(
                    "O app não vê sua senha; a sessão fica nos cookies do navegador interno deste aparelho e pode expirar pelo portal. " +
                        "CAPTCHA e MFA são sempre resolvidos por você. O status \"Sessão aberta\" é reconhecido pela navegação após o login (heurística), não por confirmação do portal." +
                        if (state.isolatedProfiles) " Os cookies ficam separados por empresa."
                        else " Este aparelho não separa cookies por empresa: saia do portal antes de trocar de empresa.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * Ajuda "Entrar com certificado digital (A1)": o .pfx é instalado no armazenamento de credenciais do Android e o
 * navegador interno pede ao sistema (KeyChain) para usá-lo quando o gov.br solicitar. O app não lê, exporta nem copia
 * a chave; guarda só qual certificado (alias) você escolheu para cada site.
 */
@Composable
private fun CertificateHelpCard() {
    val context = LocalContext.current
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = LicitaColors.GreenBright)
            Spacer(Modifier.width(8.dp))
            Text("Entrar com certificado digital (A1)", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Com o certificado A1 instalado no Android, o gov.br não pede CAPTCHA nem senha no login:\n" +
                "1. Copie o arquivo .pfx (ou .p12) do certificado para o celular.\n" +
                "2. Em Configurações do Android › Segurança › Criptografia e credenciais › Instalar um certificado › " +
                "\"Certificado de usuário de app e VPN\", escolha o arquivo e digite a senha do certificado " +
                "(o caminho muda um pouco conforme o fabricante; procure \"Instalar certificado\").\n" +
                "3. No portal, toque em Entrar com gov.br e escolha \"Seu certificado digital\". O Android pergunta qual " +
                "certificado usar — escolha o da empresa.\n" +
                "O app lembra a escolha por empresa e site (só o nome do certificado; a chave fica no Android e nunca é " +
                "copiada). Para usar outro, abra o portal › menu ⋮ › \"Trocar certificado digital\".",
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            "Abrir configurações de segurança",
            {
                runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            },
            Modifier.fillMaxWidth(), tone = Tone.NEUTRAL,
        )
    }
}

@Composable
private fun PortalCard(
    row: PortalRow,
    canManage: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onSignOut: () -> Unit,
    keepAliveMinutes: Int,
    onKeepAlive: (Boolean) -> Unit,
    onAutoCertLogin: (Boolean) -> Unit,
) {
    val (label, tone) = when {
        !row.requiresLogin -> "Consulta pública" to Tone.INFO
        row.status == PortalConnectionStatus.CONECTADO -> "Sessão aberta" to Tone.SUCCESS
        row.status == PortalConnectionStatus.SESSAO_EXPIRADA -> "Sessão expirada" to Tone.WARNING
        row.status == PortalConnectionStatus.MFA_PENDENTE -> "MFA pendente" to Tone.WARNING
        else -> "Sem sessão" to Tone.NEUTRAL
    }
    LicitaCard(Modifier.fillMaxWidth(), accent = if (row.status == PortalConnectionStatus.CONECTADO && row.requiresLogin) LicitaColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PortalChip(row.portal)
            Spacer(Modifier.width(8.dp))
            Text(row.portal.displayName, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            StatusBadge(label, tone, pulsing = row.requiresLogin && row.status == PortalConnectionStatus.CONECTADO)
        }
        Spacer(Modifier.height(6.dp))
        // Capacidade real declarada pelo conector (PortalRepository.capabilities), não texto fixo por portal.
        Text(
            (listOf(row.accessLabel) + row.accessNotes).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = if (row.hasPublicApi) LicitaColors.BlueBright else LicitaColors.TextSecondary,
        )
        Text(
            when {
                row.hasPublicApi -> "Busca de editais por consulta pública, sem credenciais."
                row.status == PortalConnectionStatus.CONECTADO -> "Sessão aberta · desde ${shortDateTime(row.session?.lastLoginAt)}"
                row.status == PortalConnectionStatus.SESSAO_EXPIRADA -> "O portal encerrou a sessão. Entre novamente para continuar."
                else -> "Entre com seu cadastro na página oficial. O app não coleta usuário nem senha."
            },
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
        )
        Text(row.startUrl, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        row.capabilities?.limitations?.takeIf { it.isNotEmpty() }?.let { limits ->
            Spacer(Modifier.height(4.dp))
            Text(limits.joinToString(" "), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
        if (row.requiresLogin) {
            SwitchRow(
                title = "Manter sessão ativa",
                description = keepAliveHonestText(keepAliveMinutes) +
                    if (row.keepAliveOn && row.status != PortalConnectionStatus.CONECTADO) " Volta a funcionar quando você entrar no portal." else "",
                checked = row.keepAliveOn,
                onCheckedChange = onKeepAlive,
            )
        }
        if (row.requiresLogin && row.supportsAutoCertLogin) {
            SwitchRow(
                title = "Entrar automaticamente com certificado digital",
                description = "Com o certificado já escolhido num login manual, o app clica sozinho nas etapas do login " +
                    "(Fornecedor Brasileiro › Entrar com gov.br › Seu certificado digital › empresa com o CNPJ ativo) e, com " +
                    "\"Manter sessão ativa\", tenta reconectar uma vez se a sessão cair. Nunca digita CPF/senha; se aparecer " +
                    "CAPTCHA ou código, para e você conclui.",
                checked = row.autoCertLoginOn,
                onCheckedChange = onAutoCertLogin,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!row.requiresLogin) {
                SecondaryButton("Abrir", onOpen, Modifier.weight(1f), icon = Icons.Outlined.OpenInNew)
            } else {
                PrimaryButton(
                    if (row.status == PortalConnectionStatus.CONECTADO) "Abrir portal" else "Entrar no portal",
                    onOpen, Modifier.weight(1f), icon = Icons.Outlined.Language,
                    tone = if (row.status == PortalConnectionStatus.CONECTADO) Tone.SUCCESS else Tone.INFO,
                )
                if (row.status != PortalConnectionStatus.DESCONECTADO) {
                    SecondaryButton(
                        if (busy) "Saindo…" else "Sair do portal", onSignOut, Modifier.weight(1f),
                        enabled = canManage && !busy, icon = Icons.AutoMirrored.Outlined.Logout, tone = Tone.DANGER,
                    )
                }
            }
        }
    }
}

/** dd/MM HH:mm (pt-BR). */
private fun shortDateTime(millis: Long?): String =
    if (millis == null) "—" else SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(millis))
