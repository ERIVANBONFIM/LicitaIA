package com.licitaia.feature.settings.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.feature.settings.OptionChips
import com.licitaia.feature.settings.SwitchRow
import com.licitaia.feature.settings.minutesLabel

private data class BiometricAvailability(val available: Boolean, val reason: String?)

private fun checkBiometrics(context: Context): BiometricAvailability = runCatching {
    val manager = BiometricManager.from(context)
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    when (manager.canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability(true, null)
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability(false, "Nenhuma biometria ou bloqueio de tela cadastrado no aparelho")
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability(false, "Este aparelho não possui sensor biométrico")
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability(false, "Sensor biométrico indisponível no momento")
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability(false, "O aparelho precisa de atualização de segurança")
        else -> BiometricAvailability(false, "Biometria não suportada neste aparelho")
    }
}.getOrDefault(BiometricAvailability(false, "Não foi possível consultar a biometria"))

@Composable
fun SecurityScreen(viewModel: SecurityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    val biometrics = remember { checkBiometrics(context) }
    LaunchedEffect(Unit) { viewModel.events.collect { navigator.showMessage(it) } }

    LicitaScaffold(title = "Segurança", showBack = false) { padding ->
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
                        SectionHeader("Bloqueio do aplicativo")
                        LicitaCard(Modifier.fillMaxWidth(), accent = if (s.biometricLock || state.hasPin) LicitaColors.Green else null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBubble(Icons.Outlined.Fingerprint, LicitaColors.Green)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Proteção de acesso", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                    Text(
                                        when {
                                            s.biometricLock && state.hasPin -> "Biometria com PIN como alternativa"
                                            s.biometricLock -> "Biometria ativa"
                                            state.hasPin -> "PIN ativo"
                                            else -> "Nenhum bloqueio configurado"
                                        },
                                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                                    )
                                }
                                StatusBadge(
                                    if (s.biometricLock || state.hasPin) "Protegido" else "Aberto",
                                    if (s.biometricLock || state.hasPin) Tone.SUCCESS else Tone.WARNING,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            SwitchRow(
                                "Bloqueio por biometria",
                                "Exige impressão digital ou rosto ao abrir o app e ao voltar após o tempo limite",
                                checked = s.biometricLock && biometrics.available,
                                onCheckedChange = { v -> viewModel.update("Bloqueio biométrico", onOff(s.biometricLock), onOff(v)) { it.copy(biometricLock = v) } },
                                enabled = biometrics.available,
                                disabledReason = biometrics.reason,
                                haptic = haptic,
                            )
                            HorizontalDivider(color = LicitaColors.OutlineSoft)
                            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("PIN de desbloqueio", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                    Text(
                                        if (state.hasPin) "PIN definido (4–6 dígitos). Usado como alternativa à biometria." else "Defina um PIN numérico de 4 a 6 dígitos.",
                                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Outlined.Pin, contentDescription = null, tint = if (state.hasPin) LicitaColors.Green else LicitaColors.TextMuted)
                            }
                            ButtonRow {
                                if (state.hasPin) {
                                    SecondaryButton("Alterar PIN", { viewModel.startPinFlow(PinFlow.CHANGE) }, Modifier.weight(1f))
                                    SecondaryButton("Remover", { viewModel.startPinFlow(PinFlow.REMOVE) }, Modifier.weight(1f), tone = Tone.DANGER)
                                } else {
                                    SecondaryButton("Definir PIN", { viewModel.startPinFlow(PinFlow.SET) }, Modifier.weight(1f), icon = Icons.Outlined.Pin)
                                }
                            }
                            if (s.biometricLock && !state.hasPin) {
                                Spacer(Modifier.height(8.dp))
                                AlertBanner("Defina um PIN", "Recomendado como alternativa caso a biometria falhe.", Tone.WARNING)
                            }
                        }

                        SectionHeader("Sessão")
                        LicitaCard(Modifier.fillMaxWidth()) {
                            OptionChips(
                                title = "Tempo limite de sessão",
                                description = "Após este tempo em segundo plano o app volta bloqueado (quando há biometria ou PIN).",
                                options = SESSION_TIMEOUT_OPTIONS,
                                selected = s.sessionTimeoutMinutes,
                                label = ::minutesLabel,
                                onSelect = { v -> viewModel.update("Tempo limite de sessão", minutesLabel(s.sessionTimeoutMinutes), minutesLabel(v)) { it.copy(sessionTimeoutMinutes = v) } },
                            )
                            HorizontalDivider(color = LicitaColors.OutlineSoft)
                            SwitchRow(
                                "Lembrar sessão", "Mantém o login local entre aberturas do app (as sessões dos portais são independentes)",
                                s.rememberSession, { v -> viewModel.update("Lembrar sessão", onOff(s.rememberSession), onOff(v)) { it.copy(rememberSession = v) } }, haptic = haptic,
                            )
                            HorizontalDivider(color = LicitaColors.OutlineSoft)
                            SwitchRow(
                                "Proteção de captura de tela", "Bloqueia screenshots e gravação em telas sensíveis (custos, piso, chaves)",
                                s.screenshotProtection, { v -> viewModel.update("Proteção de captura", onOff(s.screenshotProtection), onOff(v)) { it.copy(screenshotProtection = v) } }, haptic = haptic,
                            )
                        }

                        SectionHeader("Garantias de segurança")
                        LicitaCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBubble(Icons.Outlined.Shield, LicitaColors.Blue, size = 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("O que o LicitaIA garante sempre", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                            }
                            Spacer(Modifier.height(10.dp))
                            listOf(
                                "Toda comunicação com portais e provedores de IA usa HTTPS.",
                                "Credenciais de portais e chaves de IA ficam cifradas no Android Keystore, fora do banco e dos backups.",
                                "Senhas, tokens e chaves nunca são gravados em logs nem na auditoria.",
                                "Ações vinculantes (proposta, lance, resposta ao pregoeiro) exigem sua confirmação explícita antes de o robô agir no portal.",
                                "CAPTCHA e MFA são sempre resolvidos manualmente por você — o app apenas pausa a sessão e avisa.",
                                "Todas as ações relevantes geram trilha de auditoria por usuário e empresa.",
                            ).forEach { line ->
                                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = LicitaColors.Green, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(line, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    state.pinDialog?.let { dialog -> PinDialog(dialog, viewModel) }
}

private fun onOff(v: Boolean) = if (v) "ativado" else "desativado"

@Composable
private fun PinDialog(dialog: PinDialogState, viewModel: SecurityViewModel) {
    val title = when (dialog.flow) {
        PinFlow.SET -> "Definir PIN"
        PinFlow.CHANGE -> "Alterar PIN"
        PinFlow.REMOVE -> "Remover PIN"
    }
    val prompt = when (dialog.step) {
        PinStep.CURRENT -> "Digite o PIN atual"
        PinStep.NEW -> "Digite o novo PIN (4 a 6 dígitos)"
        PinStep.CONFIRM -> "Repita o novo PIN"
    }
    AlertDialog(
        onDismissRequest = { if (!dialog.busy) viewModel.dismissPinDialog() },
        icon = { Icon(Icons.Outlined.Pin, contentDescription = null, tint = LicitaColors.Blue) },
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(prompt, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = dialog.input,
                    onValueChange = viewModel::onPinInput,
                    singleLine = true,
                    enabled = !dialog.busy,
                    isError = dialog.error != null,
                    supportingText = { Text(dialog.error ?: "${dialog.input.length}/6") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.submitPinStep() }),
                    textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, letterSpacing = 8.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = viewModel::submitPinStep, enabled = !dialog.busy && dialog.input.length >= 4) {
                Text(if (dialog.step == PinStep.CONFIRM || dialog.flow == PinFlow.REMOVE && dialog.step == PinStep.CURRENT) "Confirmar" else "Continuar")
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissPinDialog, enabled = !dialog.busy) { Text("Cancelar") } },
        containerColor = LicitaColors.SurfaceElevated,
    )
}
