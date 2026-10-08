package com.licitaia.app.shell

import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.auth.PinVerification
import kotlinx.coroutines.delay

/**
 * Sobreposição de bloqueio: exige PIN e/ou biometria após timeout ou abertura a frio.
 * [onBiometricSuccess] recebe o [BiometricPrompt.AuthenticationResult] do callback do sistema —
 * é a única forma de desbloquear sem PIN.
 */
@Composable
fun LockScreen(
    userName: String,
    biometricEnabled: Boolean,
    hasPin: Boolean,
    onPin: (String, (PinVerification) -> Unit) -> Unit,
    onBiometricSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    /** Instante (epoch ms) até o qual o PIN está bloqueado; null = livre. */
    var lockedUntil by remember { mutableStateOf<Long?>(null) }
    var remainingSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(lockedUntil) {
        val until = lockedUntil ?: return@LaunchedEffect
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0L) { lockedUntil = null; remainingSeconds = 0L; error = null; break }
            remainingSeconds = (left + 999) / 1000
            delay(250L)
        }
    }
    val pinLocked = lockedUntil != null

    val canBiometric = remember(biometricEnabled) {
        biometricEnabled && activity != null &&
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun promptBiometric() {
        val host = activity ?: return
        runCatching {
            val prompt = BiometricPrompt(
                host,
                ContextCompat.getMainExecutor(host),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onBiometricSuccess(result)
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_CANCELED) {
                            error = errString.toString()
                        }
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Desbloquear LicitaPRO")
                    .setSubtitle("Confirme sua identidade para continuar")
                    .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                    .build(),
            )
        }.onFailure { error = "Biometria indisponível neste aparelho." }
    }

    LaunchedEffect(canBiometric) { if (canBiometric) promptBiometric() }
    BackHandler { /* bloqueado: voltar não fecha a sobreposição */ }

    Box(
        Modifier
            .fillMaxSize()
            .background(LicitaColors.Background)
            // Consome toques para não vazar para a tela por baixo.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 380.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(LicitaColors.BrandGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("LicitaPRO bloqueado", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                if (userName.isBlank()) "Confirme sua identidade para continuar." else "Olá, $userName. Confirme sua identidade para continuar.",
                style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            val submitPin = {
                if (pin.length >= 4 && !pinLocked) onPin(pin) { outcome ->
                    when (outcome) {
                        PinVerification.Success -> Unit
                        is PinVerification.Wrong -> {
                            error = if (outcome.remainingAttempts > 0) {
                                "PIN incorreto. ${outcome.remainingAttempts} tentativa(s) antes do bloqueio."
                            } else "PIN incorreto. A próxima tentativa errada bloqueia temporariamente."
                            pin = ""
                        }
                        is PinVerification.Locked -> {
                            lockedUntil = System.currentTimeMillis() + outcome.retryAfterMs
                            remainingSeconds = (outcome.retryAfterMs + 999) / 1000
                            error = null
                            pin = ""
                        }
                    }
                }
            }
            if (hasPin) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter(Char::isDigit).take(6); error = null },
                    label = { Text("PIN") },
                    singleLine = true,
                    enabled = !pinLocked,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitPin() }),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    if (pinLocked) "Tente novamente em ${remainingSeconds}s" else "Desbloquear",
                    onClick = submitPin, enabled = pin.length >= 4 && !pinLocked, modifier = Modifier.fillMaxWidth(),
                )
                if (pinLocked) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Muitas tentativas incorretas. Tente novamente em ${remainingSeconds}s" +
                            if (canBiometric) " ou use a biometria." else ".",
                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.Yellow, textAlign = TextAlign.Center,
                    )
                }
            }
            if (canBiometric) {
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Usar biometria", onClick = { promptBiometric() }, icon = Icons.Outlined.Fingerprint, modifier = Modifier.fillMaxWidth(), tone = Tone.SUCCESS)
            }
            if (!hasPin && !canBiometric) {
                Text(
                    "Nenhum método de desbloqueio está disponível neste aparelho. Saia e entre novamente com sua senha.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.Yellow, textAlign = TextAlign.Center,
                )
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = LicitaColors.RedBright, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onLogout) { Text("Sair e entrar com senha", color = LicitaColors.TextSecondary) }
        }
    }
}
