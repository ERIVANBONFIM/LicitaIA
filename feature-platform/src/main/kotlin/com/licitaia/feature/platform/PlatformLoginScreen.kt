package com.licitaia.feature.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.platform.session.PlatformSession
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors

@Composable
fun PlatformLoginScreen(viewModel: PlatformLoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var showPassword by remember { mutableStateOf(false) }

    // Já autenticado → vai direto à lista de licitações da plataforma.
    LaunchedEffect(session) {
        if (session is PlatformSession.SignedIn) {
            navigator.navigate(Routes.PLATFORM_TENDERS)
        }
    }

    LicitaScaffold(title = "Plataforma LicitaPRO", subtitle = "Modo online (opcional)", showBack = true) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Blue) {
                IconBubble(Icons.Outlined.Cloud, LicitaColors.Blue)
                Spacer(Modifier.height(10.dp))
                Text("Entrar na plataforma", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Text(
                    "Acesse sua conta LicitaPRO para sincronizar as licitações da sua empresa. O modo local atual continua funcionando normalmente.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmail,
                    label = { Text("E-mail") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.senha,
                    onValueChange = viewModel::onSenha,
                    label = { Text("Senha") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPassword) "Ocultar senha" else "Mostrar senha",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Spacer(Modifier.height(10.dp))
                    AlertBanner("Não foi possível entrar", it, Tone.DANGER)
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Entrar", viewModel::login, Modifier.fillMaxWidth(), loading = state.loading)
            }

            LicitaCard(Modifier.fillMaxWidth()) {
                Text("Diagnóstico", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Text(
                    "Testa a conexão com a VPS sem usar credenciais.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(
                    if (state.checkingConnectivity) "Testando…" else "Testar conexão",
                    viewModel::checkConnectivity,
                    Modifier.fillMaxWidth(),
                    enabled = !state.checkingConnectivity,
                    tone = Tone.NEUTRAL,
                )
                state.connectivity?.let {
                    Spacer(Modifier.height(10.dp))
                    AlertBanner("Conectividade", it, Tone.INFO)
                }
            }
        }
    }
}
