package com.licitaia.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Company
import com.licitaia.domain.util.Formatters

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val companies by viewModel.companies.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val focus = LocalFocusManager.current
    // Activity atual: o Credential Manager exibe o seletor de contas do sistema a partir dela.
    val context = LocalContext.current

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) navigator.onLoggedIn()
    }
    // Login/cadastro na plataforma → abre o SHELL completo (dashboard/menu/barra) em modo plataforma.
    LaunchedEffect(state.platformSignedIn) {
        if (state.platformSignedIn) navigator.onLoggedIn()
    }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(LicitaColors.Background)
            .background(
                Brush.verticalGradient(
                    0f to LicitaColors.Blue.copy(alpha = 0.20f),
                    0.45f to Color.Transparent,
                    1f to LicitaColors.Green.copy(alpha = 0.07f),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            AnimatedVisibility(entered, enter = fadeIn() + slideInVertically { -it / 3 }) {
                BrandHeader()
            }
            Spacer(Modifier.height(28.dp))

            AnimatedVisibility(entered, enter = fadeIn() + slideInVertically { it / 5 }) {
                LicitaCard(Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
                    // Interruptor de modo no topo do card: PLATAFORMA (padrão) | Local.
                    ModeToggle(platformMode = state.platformMode, enabled = !state.busy, onSelect = viewModel::setPlatformMode)
                    Spacer(Modifier.height(14.dp))

                    val createLabel = if (state.platformMode) "Criar conta" else "Criar conta local"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectChip("Entrar", state.mode == AuthMode.LOGIN, { viewModel.setMode(AuthMode.LOGIN) })
                        SelectChip(createLabel, state.mode == AuthMode.REGISTER, { viewModel.setMode(AuthMode.REGISTER) })
                    }
                    Spacer(Modifier.height(14.dp))

                    AnimatedVisibility(state.generalError != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Column {
                            AlertBanner("Não foi possível continuar", state.generalError.orEmpty(), Tone.DANGER)
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    if (state.platformMode) {
                        PlatformForm(state, viewModel, focus)
                    } else {
                        LocalForm(state, companies, viewModel, focus, context)
                    }

                    AnimatedVisibility(state.info != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            AlertBanner("Aviso", state.info.orEmpty(), Tone.INFO, actionLabel = "OK", onAction = viewModel::dismissInfo)
                        }
                    }
                }
            }

            // Vínculo Google ↔ conta local e cadastro de empresa Google (apenas modo local).
            if (!state.platformMode) {
                state.linkPending?.let { pending ->
                    Spacer(Modifier.height(14.dp))
                    LinkLocalAccountCard(
                        pending = pending,
                        password = state.linkPassword,
                        error = state.linkError,
                        loading = state.googleLoading,
                        onPassword = viewModel::onLinkPassword,
                        onLink = { focus.clearFocus(); viewModel.linkGoogle() },
                        onCancel = viewModel::cancelLink,
                    )
                }

                state.googlePending?.let { pending ->
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(value = state.companyName, onValueChange = viewModel::onCompanyName, label = { Text("Razão social da minha empresa") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = state.cnpj, onValueChange = viewModel::onCnpj, label = { Text("CNPJ (14 dígitos)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Criar minha empresa e entrar", viewModel::createGoogleCompany, Modifier.fillMaxWidth(), loading = state.googleLoading)
                    GooglePendingCard(
                        pending = pending,
                        loading = state.googleLoading,
                        onRetry = viewModel::retryGoogleAccess,
                        onSignOut = viewModel::signOutGoogle,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = LicitaColors.TextMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.platformMode) "Conta na plataforma LicitaPRO · sincroniza com a nuvem da sua empresa"
                    else "Autenticação local ou Google · dados guardados neste aparelho",
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Toda ação vinculante exige confirmação humana.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Interruptor segmentado PLATAFORMA | Local no topo do card. */
@Composable
private fun ModeToggle(platformMode: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LicitaColors.SurfaceHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToggleSegment("Plataforma", Icons.Outlined.CloudQueue, platformMode, enabled, Modifier.weight(1f)) { onSelect(true) }
        ToggleSegment("Local", Icons.Outlined.Lock, !platformMode, enabled, Modifier.weight(1f)) { onSelect(false) }
    }
}

@Composable
private fun ToggleSegment(label: String, icon: ImageVector, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) LicitaColors.Blue else Color.Transparent)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Color.White else LicitaColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else LicitaColors.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Formulário do modo PLATAFORMA (VPS): Entrar = /auth/login; Criar conta = /auth/register (ou convite). */
@Composable
private fun PlatformForm(state: LoginUiState, viewModel: LoginViewModel, focus: androidx.compose.ui.focus.FocusManager) {
    val isLogin = state.mode == AuthMode.LOGIN
    AnimatedVisibility(state.mode == AuthMode.REGISTER, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        AuthField(
            value = state.name, onValueChange = viewModel::onName, label = "Nome completo",
            icon = Icons.Outlined.Person, error = state.nameError, enabled = !state.busy,
        )
    }
    AuthField(
        value = state.email, onValueChange = viewModel::onEmail, label = "E-mail",
        icon = Icons.Outlined.Email, error = state.emailError, enabled = !state.busy, keyboardType = KeyboardType.Email,
    )
    PasswordField(
        value = state.password, onValueChange = viewModel::onPassword, error = state.passwordError, enabled = !state.busy,
        imeAction = if (isLogin) ImeAction.Done else ImeAction.Next,
        onDone = { focus.clearFocus(); viewModel.submit() },
    )
    AnimatedVisibility(state.mode == AuthMode.REGISTER, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column {
            AuthField(
                value = state.inviteCode, onValueChange = viewModel::onInviteCode, label = "Código de convite (opcional)",
                icon = Icons.Outlined.VpnKey, error = null, enabled = !state.busy,
                helper = "Tem um código? Preencha só isto. Sem código, informe CNPJ e razão social abaixo.",
            )
            AuthField(
                value = state.companyName, onValueChange = viewModel::onCompanyName, label = "Razão social",
                icon = Icons.Outlined.Business, error = state.companyError, enabled = !state.busy,
            )
            AuthField(
                value = state.cnpj, onValueChange = viewModel::onCnpj, label = "CNPJ (somente números)",
                icon = Icons.Outlined.Badge, error = state.cnpjError, enabled = !state.busy,
                keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                onDone = { focus.clearFocus(); viewModel.submit() },
                helper = if (state.cnpj.length == 14) Formatters.cnpj(state.cnpj) else "${state.cnpj.length}/14 dígitos",
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    PrimaryButton(
        text = if (isLogin) "Entrar na plataforma" else "Criar conta e entrar",
        onClick = { focus.clearFocus(); viewModel.submit() },
        modifier = Modifier.fillMaxWidth(),
        loading = state.loading,
    )
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = viewModel::checkConnectivity, enabled = !state.checkingConnectivity, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (state.checkingConnectivity) "Testando conexão…" else "Testar conexão com a plataforma",
            style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary,
        )
    }
    state.connectivity?.let {
        AlertBanner("Conectividade", it, Tone.INFO)
    }
}

/** Formulário do modo LOCAL (neste aparelho): fluxo atual, incluindo Google e "lembrar sessão". */
@Composable
private fun LocalForm(
    state: LoginUiState,
    companies: List<Company>,
    viewModel: LoginViewModel,
    focus: androidx.compose.ui.focus.FocusManager,
    context: android.content.Context,
) {
    val isLogin = state.mode == AuthMode.LOGIN
    AnimatedVisibility(state.mode == AuthMode.REGISTER, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        AuthField(
            value = state.name, onValueChange = viewModel::onName, label = "Nome completo",
            icon = Icons.Outlined.Person, error = state.nameError, enabled = !state.busy,
        )
    }

    AuthField(
        value = state.email, onValueChange = viewModel::onEmail, label = "E-mail",
        icon = Icons.Outlined.Email, error = state.emailError, enabled = !state.busy, keyboardType = KeyboardType.Email,
    )

    PasswordField(
        value = state.password, onValueChange = viewModel::onPassword, error = state.passwordError, enabled = !state.busy,
        imeAction = if (isLogin) ImeAction.Done else ImeAction.Next,
        onDone = { focus.clearFocus(); viewModel.submit() },
    )

    AnimatedVisibility(state.mode == AuthMode.REGISTER, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column {
            AuthField(
                value = state.companyName, onValueChange = viewModel::onCompanyName, label = "Empresa",
                icon = Icons.Outlined.Business, error = state.companyError, enabled = !state.busy,
            )
            AuthField(
                value = state.cnpj, onValueChange = viewModel::onCnpj, label = "CNPJ (somente números)",
                icon = Icons.Outlined.Badge, error = state.cnpjError, enabled = !state.busy,
                keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                onDone = { focus.clearFocus(); viewModel.submit() },
                helper = if (state.cnpj.length == 14) Formatters.cnpj(state.cnpj) else "${state.cnpj.length}/14 dígitos",
            )
        }
    }

    AnimatedVisibility(state.mode == AuthMode.LOGIN, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column {
            CompanySelector(companies, state.companyId, viewModel::onCompany, enabled = !state.busy)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = !state.busy) { viewModel.onRemember(!state.remember) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.remember, onCheckedChange = viewModel::onRemember, enabled = !state.busy)
                Text("Lembrar sessão neste aparelho", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    PrimaryButton(
        text = if (isLogin) "Entrar" else "Criar conta e entrar",
        onClick = { focus.clearFocus(); viewModel.submit() },
        modifier = Modifier.fillMaxWidth(),
        loading = state.loading,
    )
    if (isLogin) {
        Spacer(Modifier.height(14.dp))
        OrDivider()
        Spacer(Modifier.height(14.dp))
        GoogleSignInButton(
            loading = state.googleLoading,
            enabled = !state.busy && state.googleConfigured,
            onClick = { focus.clearFocus(); viewModel.signInWithGoogle(context) },
            onCancel = viewModel::cancelGoogle,
        )
        if (!state.googleConfigured) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Login Google indisponível neste build: falta o ID do cliente OAuth (ver README).",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Campo de senha com alternância mostrar/ocultar, reutilizado nos dois modos. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    enabled: Boolean,
    imeAction: ImeAction,
    onDone: () -> Unit,
) {
    val focus = LocalFocusManager.current
    var showPassword by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Senha") },
        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (showPassword) "Ocultar senha" else "Mostrar senha",
                )
            }
        },
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onDone() },
            onNext = { focus.moveFocus(FocusDirection.Down) },
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = LicitaColors.Outline)
        Text("  ou  ", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        HorizontalDivider(Modifier.weight(1f), color = LicitaColors.Outline)
    }
}

/** Botão "Entrar com Google" no padrão de marca (fundo claro, logotipo "G" colorido). */
@Composable
private fun GoogleSignInButton(loading: Boolean, enabled: Boolean, onClick: () -> Unit, onCancel: () -> Unit) {
    Column {
        Surface(
            onClick = onClick,
            enabled = enabled && !loading,
            shape = MaterialTheme.shapes.medium,
            color = if (enabled) Color(0xFFF2F4F8) else LicitaColors.SurfaceHigh,
            contentColor = if (enabled) Color(0xFF1F1F1F) else LicitaColors.TextMuted,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LicitaColors.Blue)
                } else {
                    GoogleLogo(Modifier.size(20.dp), dimmed = !enabled)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    if (loading) "Aguardando o Google…" else "Entrar com Google",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        AnimatedVisibility(loading, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancelar", color = LicitaColors.TextSecondary) }
        }
    }
}

/** E-mail da conta Google já pertence a uma conta local: pede a senha dela antes de vincular. */
@Composable
private fun LinkLocalAccountCard(
    pending: LinkPending,
    password: String,
    error: String?,
    loading: Boolean,
    onPassword: (String) -> Unit,
    onLink: () -> Unit,
    onCancel: () -> Unit,
) {
    var show by rememberSaveable { mutableStateOf(false) }
    LicitaCard(Modifier.fillMaxWidth().widthIn(max = 520.dp), accent = LicitaColors.Blue) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Link, contentDescription = null, tint = LicitaColors.BlueBright)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Vincular ao Google", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Text(pending.email, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Já existe uma conta local com este e-mail. Digite a senha dela para vincular ao Google. " +
                "Perfil, empresas e senha são mantidos; depois você entra por qualquer um dos dois.",
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text("Senha da conta local") },
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (show) "Ocultar senha" else "Mostrar senha")
                }
            },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onLink() }),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Cancelar", onCancel, Modifier.weight(1f), enabled = !loading, tone = Tone.NEUTRAL)
            PrimaryButton("Vincular e entrar", onLink, Modifier.weight(1f), loading = loading)
        }
    }
}

/** Logotipo "G" desenhado em vetor (sem dependência de assets). */
@Composable
private fun GoogleLogo(modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val alpha = if (dimmed) 0.4f else 1f
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = size.minDimension * 0.22f
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
        drawArc(Color(0xFFEA4335).copy(alpha = alpha), 180f, 90f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFFFBBC05).copy(alpha = alpha), 110f, 70f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFF34A853).copy(alpha = alpha), 40f, 70f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFF4285F4).copy(alpha = alpha), -5f, 45f, false, topLeft, arcSize, style = style)
        drawLine(
            Color(0xFF4285F4).copy(alpha = alpha),
            start = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.5f),
            end = androidx.compose.ui.geometry.Offset(size.width - inset, size.height * 0.5f),
            strokeWidth = stroke,
        )
    }
}

/** Conta Google identificada, mas sem vínculo com empresa: explica o próximo passo. */
@Composable
private fun GooglePendingCard(pending: GooglePending, loading: Boolean, onRetry: () -> Unit, onSignOut: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth().widthIn(max = 520.dp), accent = LicitaColors.Yellow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Person, contentDescription = null, tint = LicitaColors.Yellow)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Conta verificada · cadastre sua empresa", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Text(pending.email, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Olá, ${pending.name}. Sua conta Google foi reconhecida, mas ainda não está vinculada a nenhuma empresa do LicitaPRO. " +
                "Para uso pessoal, cadastre sua empresa para começar. " +
                "Você também pode criar sua própria empresa acima e administrá-la neste aparelho.",
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Sair da conta Google", onSignOut, Modifier.weight(1f), enabled = !loading, tone = Tone.NEUTRAL)
            PrimaryButton("Verificar acesso", onRetry, Modifier.weight(1f), loading = loading)
        }
    }
}

@Composable
private fun BrandHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(LicitaColors.BrandGradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Gavel, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            buildAnnotatedString {
                append("Licita")
                withStyle(SpanStyle(color = LicitaColors.GreenBright)) { append("PRO") }
            },
            style = MaterialTheme.typography.displaySmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Inteligência para vencer licitações",
            style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    error: String?,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    helper: String? = null,
) {
    val focus = LocalFocusManager.current
    val supporting = error ?: helper
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        isError = error != null,
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { focus.moveFocus(FocusDirection.Down) },
            onDone = { onDone?.invoke() ?: focus.clearFocus() },
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanySelector(companies: List<Company>, selectedId: Long?, onSelect: (Long?) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val selected = companies.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected?.let { it.tradeName.ifBlank { it.name } } ?: "Padrão do usuário",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Empresa ativa (opcional)") },
            leadingIcon = { Icon(Icons.Outlined.Business, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Padrão do usuário") },
                onClick = { onSelect(null); expanded = false },
            )
            companies.forEach { company ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(company.tradeName.ifBlank { company.name })
                            Text(Formatters.cnpj(company.cnpj), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
                        }
                    },
                    onClick = { onSelect(company.id); expanded = false },
                )
            }
        }
    }
}
