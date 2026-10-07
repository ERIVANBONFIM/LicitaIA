package com.licitaia.feature.settings.companies

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.UserProfile
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.BrDocuments
import com.licitaia.domain.util.Formatters
import com.licitaia.domain.util.missingProposalData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompaniesScreen(viewModel: CompaniesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LaunchedEffect(Unit) { viewModel.events.collect { navigator.showMessage(it) } }
    val noPermission = "O perfil ${state.session?.user?.role?.label ?: ""} não tem a permissão \"Gerenciar empresas e usuários\"."
    val demoBlocked = "Na demonstração não é possível criar empresas nem vincular usuários. Saia da demonstração e crie sua conta para usar dados reais."

    LicitaScaffold(
        title = "Empresas e Perfis",
        showBack = false,
        actions = {
            if (!state.loading && state.error == null && !state.noSession) {
                IconButton(
                    onClick = {
                        when {
                            state.canCreateCompany -> viewModel.newCompany()
                            state.demo -> navigator.showMessage(demoBlocked)
                            else -> navigator.showMessage(noPermission)
                        }
                    },
                ) {
                    Icon(if (state.canCreateCompany) Icons.Outlined.Add else Icons.Outlined.Lock, contentDescription = "Nova empresa")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> SkeletonList(items = 3)
                state.noSession -> ErrorState("Sessão encerrada. Entre novamente para gerenciar empresas.")
                state.error != null -> ErrorState(state.error ?: "", onRetry = viewModel::retry)
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!state.canManage) {
                        item(key = "rbac") { AlertBanner("Somente leitura", "$noPermission Você ainda pode trocar entre as empresas às quais tem acesso.", Tone.WARNING) }
                    }
                    if (state.demo) {
                        item(key = "demo") { AlertBanner("Modo demonstração", "Espaço isolado com dados fictícios. Você pode editar a empresa de demonstração, mas não criar empresas nem gerir usuários.", Tone.WARNING) }
                    }
                    item(key = "companiesHeader") { SectionHeader("Empresas (${state.companies.size})") }
                    if (state.companies.isEmpty()) {
                        item(key = "noCompanies") { Text("Nenhuma empresa cadastrada.", color = LicitaColors.TextSecondary) }
                    }
                    items(state.companies, key = { "company-${it.id}" }) { company ->
                        CompanyCard(
                            company = company,
                            isActive = company.id == state.session?.activeCompany?.id,
                            canSwitch = state.canSwitchTo(company),
                            switching = state.switchingTo == company.id,
                            canManage = state.canManage,
                            hasAccess = state.session?.let { company.id in it.user.companyIds || it.user.role == UserRole.ADMIN } ?: false,
                            modifier = Modifier.animateItem(),
                            onSwitch = { viewModel.switchCompany(company) },
                            onEdit = { viewModel.editCompany(company) },
                        )
                    }

                    item(key = "usersHeader") {
                        SectionHeader(
                            "Usuários de ${state.session?.activeCompany?.let { it.tradeName.ifBlank { it.name } } ?: "—"} (${state.users.size})",
                            actionLabel = if (state.canManageUsers) "Novo usuário" else null,
                            onAction = viewModel::newUser,
                        )
                    }
                    if (state.users.isEmpty()) {
                        item(key = "noUsers") { Text("Nenhum usuário vinculado a esta empresa.", color = LicitaColors.TextSecondary) }
                    }
                    items(state.users, key = { "user-${it.id}" }) { user ->
                        UserCard(
                            user = user,
                            isSelf = user.id == state.session?.user?.id,
                            canManage = state.canManageUsers,
                            modifier = Modifier.animateItem(),
                            onEdit = { viewModel.editUser(user) },
                            onDelete = { viewModel.askDeleteUser(user) },
                        )
                    }

                    if (state.canManageUsers && state.unassignedUsers.isNotEmpty()) {
                        item(key = "pendingHeader") { SectionHeader("Aguardando vínculo (${state.unassignedUsers.size})") }
                        item(key = "pendingHint") {
                            Text(
                                "Contas identificadas (ex.: via Google) que ainda não têm acesso a nenhuma empresa. " +
                                    "Ao vincular, você define o perfil — nenhum acesso é concedido automaticamente.",
                                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                            )
                        }
                        items(state.unassignedUsers, key = { "pending-${it.id}" }) { user ->
                            PendingUserCard(user = user, modifier = Modifier.animateItem(), onLink = { viewModel.linkUserToActiveCompany(user) })
                        }
                    }

                    item(key = "matrixHeader") { SectionHeader("Matriz de permissões por perfil") }
                    item(key = "matrix") { PermissionMatrix(initialRole = state.session?.user?.role ?: UserRole.LICITACOES) }
                }
            }
        }
    }

    state.companyForm?.let { form ->
        ModalBottomSheet(onDismissRequest = viewModel::dismissCompanyForm, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LicitaColors.SurfaceElevated) {
            CompanyEditor(form, viewModel)
        }
    }
    state.userForm?.let { form ->
        ModalBottomSheet(onDismissRequest = viewModel::dismissUserForm, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LicitaColors.SurfaceElevated) {
            UserEditor(form, state.companies, viewModel)
        }
    }
    state.confirmDeleteUser?.let { user ->
        ConfirmDialog(
            title = "Remover ${user.name}?",
            message = "O usuário perderá o acesso ao LicitaIA. A trilha de auditoria das ações dele é preservada.",
            confirmLabel = "Remover", tone = Tone.DANGER, icon = Icons.Outlined.DeleteOutline,
            onConfirm = viewModel::deleteUserConfirmed,
            onDismiss = { viewModel.askDeleteUser(null) },
        )
    }
}

@Composable
private fun CompanyCard(
    company: Company, isActive: Boolean, canSwitch: Boolean, switching: Boolean, canManage: Boolean, hasAccess: Boolean,
    modifier: Modifier, onSwitch: () -> Unit, onEdit: () -> Unit,
) {
    LicitaCard(modifier.fillMaxWidth().animateContentSize(), accent = if (isActive) LicitaColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.Business, if (isActive) LicitaColors.Green else LicitaColors.Blue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(company.tradeName.ifBlank { company.name }, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (company.tradeName.isNotBlank() && company.tradeName != company.name) {
                    Text(company.name, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("CNPJ ${Formatters.cnpj(company.cnpj)}", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
            if (isActive) StatusBadge("ATIVA", Tone.SUCCESS, pulsing = true)
            if (canManage) {
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Editar", tint = LicitaColors.TextSecondary) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusBadge(company.segment.label, Tone.INFO)
            StatusBadge("${company.city}/${company.uf}", Tone.NEUTRAL)
            StatusBadge("IA: ${company.preferredAi?.label?.substringBefore(" /")?.substringBefore(" (") ?: "global"}", Tone.NEUTRAL)
        }
        company.missingProposalData().takeIf { it.isNotEmpty() && canManage }?.let { missing ->
            Text(
                "Para o PDF da proposta, complete: ${missing.joinToString(" e ")}.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow, modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (!isActive) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                if (switching) "Trocando…" else "Tornar empresa ativa", onSwitch, Modifier.fillMaxWidth(),
                enabled = canSwitch && !switching, icon = Icons.Outlined.SwapHoriz,
            )
            if (!hasAccess) {
                Text("Você não tem acesso a esta empresa. Peça a um administrador para vinculá-la ao seu usuário.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun UserCard(user: UserProfile, isSelf: Boolean, canManage: Boolean, modifier: Modifier, onEdit: () -> Unit, onDelete: () -> Unit) {
    LicitaCard(modifier.fillMaxWidth(), onClick = if (canManage) onEdit else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.Person, roleColor(user.role), size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user.name, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isSelf) {
                        Spacer(Modifier.width(6.dp))
                        StatusBadge("você", Tone.INFO)
                    }
                }
                Text(user.email, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${user.companyIds.size} empresa(s) · ${user.role.description}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusBadge(user.role.label, roleTone(user.role))
            if (canManage && !isSelf) {
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remover", tint = LicitaColors.TextMuted) }
            }
        }
    }
}

@Composable
private fun PendingUserCard(user: UserProfile, modifier: Modifier, onLink: () -> Unit) {
    LicitaCard(modifier.fillMaxWidth(), accent = LicitaColors.Yellow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.Person, LicitaColors.Yellow, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.name, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(user.email, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Identificado via ${user.provider.label} · sem empresa", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
            TextButton(onClick = onLink) { Text("Vincular", color = LicitaColors.BlueBright, fontWeight = FontWeight.Bold) }
        }
    }
}

private fun roleTone(role: UserRole): Tone = when (role) {
    UserRole.ADMIN -> Tone.DANGER
    UserRole.DIRETORIA -> Tone.WARNING
    UserRole.LICITACOES -> Tone.INFO
    UserRole.FINANCEIRO -> Tone.SUCCESS
    UserRole.TECNICO -> Tone.NEUTRAL
}

private fun roleColor(role: UserRole) = when (role) {
    UserRole.ADMIN -> LicitaColors.Red
    UserRole.DIRETORIA -> LicitaColors.Yellow
    UserRole.LICITACOES -> LicitaColors.Blue
    UserRole.FINANCEIRO -> LicitaColors.Green
    UserRole.TECNICO -> LicitaColors.Purple
}

@Composable
private fun PermissionMatrix(initialRole: UserRole) {
    var role by rememberSaveable { mutableStateOf(initialRole) }
    val granted = Rbac.permissionsOf(role)
    LicitaCard(Modifier.fillMaxWidth().animateContentSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(UserRole.entries.toList()) { r -> SelectChip(r.label, role == r, { role = r }, color = roleColor(r)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(role.description, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        Text("${granted.size} de ${Permission.entries.size} permissões", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        Spacer(Modifier.height(8.dp))
        Permission.entries.forEach { p ->
            val has = p in granted
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (has) Icons.Outlined.Check else Icons.Outlined.Remove, contentDescription = null,
                    tint = if (has) LicitaColors.Green else LicitaColors.TextMuted, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(p.label, style = MaterialTheme.typography.bodyMedium, color = if (has) LicitaColors.TextPrimary else LicitaColors.TextMuted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("A matriz é fixa por perfil (SDD §22). Para conceder outra permissão, altere o perfil do usuário.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
    }
}

// ------------------------------------------------------------------ editores

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanyEditor(form: CompanyForm, viewModel: CompaniesViewModel) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (form.isNew) "Nova empresa" else "Editar empresa", style = MaterialTheme.typography.headlineSmall, color = LicitaColors.TextPrimary)
        form.errors["form"]?.let { AlertBanner("Não foi possível salvar", it, Tone.DANGER) }
        OutlinedTextField(
            value = form.name, onValueChange = { v -> viewModel.updateCompanyForm { it.copy(name = v.take(150)) } },
            label = { Text("Razão social") }, singleLine = true, isError = form.errors.containsKey("name"),
            supportingText = form.errors["name"]?.let { { Text(it) } }, enabled = !form.busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.tradeName, onValueChange = { v -> viewModel.updateCompanyForm { it.copy(tradeName = v.take(100)) } },
            label = { Text("Nome fantasia") }, singleLine = true, enabled = !form.busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.cnpjDigits,
            onValueChange = { v -> viewModel.updateCompanyForm { it.copy(cnpjDigits = v.filter { c -> c.isDigit() }.take(14)) } },
            label = { Text("CNPJ") }, singleLine = true, enabled = !form.busy,
            visualTransformation = CnpjVisualTransformation,
            isError = form.errors.containsKey("cnpj"),
            supportingText = {
                Text(
                    form.errors["cnpj"] ?: when {
                        form.cnpjDigits.length < 14 -> "${form.cnpjDigits.length}/14 dígitos"
                        isValidCnpj(form.cnpjDigits) -> "CNPJ com dígitos verificadores válidos"
                        else -> "Atenção: dígitos verificadores não conferem"
                    },
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
        )
        Text("Segmento", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segment.entries.forEach { s -> SelectChip(s.label, form.segment == s, { viewModel.updateCompanyForm { it.copy(segment = s) } }) }
        }
        Text("UF", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(BRAZIL_UFS) { uf -> SelectChip(uf, form.uf == uf, { viewModel.updateCompanyForm { it.copy(uf = uf) } }) }
        }
        OutlinedTextField(
            value = form.city, onValueChange = { v -> viewModel.updateCompanyForm { it.copy(city = v.take(80)) } },
            label = { Text("Cidade") }, singleLine = true, isError = form.errors.containsKey("city"),
            supportingText = form.errors["city"]?.let { { Text(it) } }, enabled = !form.busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
        )
        Text("IA preferida", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("Provedor global", form.preferredAi == null, { viewModel.updateCompanyForm { it.copy(preferredAi = null) } })
            AiProviderType.entries.filter { it != AiProviderType.MOCK }.forEach { p -> SelectChip(p.label, form.preferredAi == p, { viewModel.updateCompanyForm { it.copy(preferredAi = p) } }, color = LicitaColors.Purple) }
        }

        // Dados que saem na proposta comercial em PDF (opcionais; campos vazios não aparecem no documento).
        FormSection("Endereço", "Sai no cabeçalho e na qualificação da proponente no PDF da proposta.")
        FormField(form, "Logradouro e número", form.street, { v -> viewModel.updateCompanyForm { it.copy(street = v.take(150)) } }, capitalization = KeyboardCapitalization.Words)
        FormField(form, "Complemento", form.complement, { v -> viewModel.updateCompanyForm { it.copy(complement = v.take(80)) } }, capitalization = KeyboardCapitalization.Sentences)
        FormField(form, "Bairro", form.district, { v -> viewModel.updateCompanyForm { it.copy(district = v.take(80)) } }, capitalization = KeyboardCapitalization.Words)
        FormField(
            form, "CEP", form.zipDigits, { v -> viewModel.updateCompanyForm { it.copy(zipDigits = v.filter { c -> c.isDigit() }.take(8)) } },
            errorKey = "zip", keyboardType = KeyboardType.Number, mask = CepMask,
            hint = "Cidade/UF: ${listOf(form.city.trim(), form.uf).filter { it.isNotEmpty() }.joinToString("/")}",
        )

        FormSection("Contato")
        FormField(
            form, "Telefone com DDD", form.phoneDigits, { v -> viewModel.updateCompanyForm { it.copy(phoneDigits = v.filter { c -> c.isDigit() }.take(11)) } },
            errorKey = "phone", keyboardType = KeyboardType.Phone, mask = PhoneMask,
        )
        FormField(form, "E-mail", form.email, { v -> viewModel.updateCompanyForm { it.copy(email = v.trim().take(120)) } }, errorKey = "email", keyboardType = KeyboardType.Email)

        FormSection("Representante legal", "Assina a proposta: nome, CPF e cargo saem no bloco de assinatura.")
        FormField(form, "Nome completo", form.legalRepName, { v -> viewModel.updateCompanyForm { it.copy(legalRepName = v.take(120)) } }, capitalization = KeyboardCapitalization.Words)
        FormField(
            form, "CPF", form.legalRepCpfDigits, { v -> viewModel.updateCompanyForm { it.copy(legalRepCpfDigits = v.filter { c -> c.isDigit() }.take(11)) } },
            errorKey = "cpf", keyboardType = KeyboardType.Number, mask = CpfMask,
            hint = when {
                form.legalRepCpfDigits.isEmpty() -> null
                form.legalRepCpfDigits.length < 11 -> "${form.legalRepCpfDigits.length}/11 dígitos"
                BrDocuments.isValidCpf(form.legalRepCpfDigits) -> "CPF com dígitos verificadores válidos"
                else -> "Atenção: dígitos verificadores não conferem"
            },
        )
        FormField(form, "Cargo", form.legalRepRole, { v -> viewModel.updateCompanyForm { it.copy(legalRepRole = v.take(80)) } }, capitalization = KeyboardCapitalization.Sentences)

        FormSection("Dados bancários (opcional)", "Para pagamento: aparecem na proposta somente se preenchidos.")
        FormField(form, "Banco", form.bankName, { v -> viewModel.updateCompanyForm { it.copy(bankName = v.take(80)) } }, capitalization = KeyboardCapitalization.Words)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Texto livre: agência/conta podem ter dígito verificador com hífen ou "X".
            FormField(form, "Agência", form.bankAgency, { v -> viewModel.updateCompanyForm { it.copy(bankAgency = v.take(12)) } }, Modifier.weight(1f))
            FormField(form, "Conta", form.bankAccount, { v -> viewModel.updateCompanyForm { it.copy(bankAccount = v.take(20)) } }, Modifier.weight(1f), imeAction = ImeAction.Done)
        }
        Spacer(Modifier.height(4.dp))
        ButtonRow {
            SecondaryButton("Cancelar", viewModel::dismissCompanyForm, Modifier.weight(1f), enabled = !form.busy, tone = Tone.NEUTRAL)
            PrimaryButton(if (form.isNew) "Cadastrar" else "Salvar", viewModel::saveCompany, Modifier.weight(1f), loading = form.busy)
        }
    }
}

@Composable
private fun UserEditor(form: UserForm, companies: List<Company>, viewModel: CompaniesViewModel) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (form.isNew) "Novo usuário" else "Editar usuário", style = MaterialTheme.typography.headlineSmall, color = LicitaColors.TextPrimary)
        form.errors["form"]?.let { AlertBanner("Não foi possível salvar", it, Tone.DANGER) }
        OutlinedTextField(
            value = form.name, onValueChange = { v -> viewModel.updateUserForm { it.copy(name = v.take(100)) } },
            label = { Text("Nome") }, singleLine = true, isError = form.errors.containsKey("name"),
            supportingText = form.errors["name"]?.let { { Text(it) } }, enabled = !form.busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.email, onValueChange = { v -> viewModel.updateUserForm { it.copy(email = v.take(120)) } },
            label = { Text("E-mail") }, singleLine = true, isError = form.errors.containsKey("email"),
            supportingText = form.errors["email"]?.let { { Text(it) } }, enabled = !form.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.password, onValueChange = { v -> viewModel.updateUserForm { it.copy(password = v.take(64)) } },
            label = { Text(if (form.isNew) "Senha inicial" else "Nova senha (opcional)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), isError = form.errors.containsKey("password"),
            supportingText = { Text(form.errors["password"] ?: "Mínimo de 6 caracteres. Nunca exibida nem registrada.") }, enabled = !form.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(),
        )
        Text("Perfil", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(UserRole.entries.toList()) { r -> SelectChip(r.label, form.role == r, { viewModel.updateUserForm { it.copy(role = r) } }, color = roleColor(r)) }
        }
        Text(form.role.description, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        form.errors["role"]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LicitaColors.Red) }
        if (companies.size > 1) {
            Text("Acesso às empresas", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
            companies.forEach { c ->
                val selected = c.id in form.companyIds
                SelectChip(
                    (if (selected) "✓ " else "") + c.tradeName.ifBlank { c.name }, selected,
                    onClick = {
                        viewModel.updateUserForm {
                            val ids = if (selected) it.companyIds - c.id else it.companyIds + c.id
                            if (ids.isEmpty()) it else it.copy(companyIds = ids)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ButtonRow {
            SecondaryButton("Cancelar", viewModel::dismissUserForm, Modifier.weight(1f), enabled = !form.busy, tone = Tone.NEUTRAL)
            PrimaryButton(if (form.isNew) "Criar usuário" else "Salvar", viewModel::saveUser, Modifier.weight(1f), loading = form.busy)
        }
    }
}

@Composable
private fun FormSection(title: String, hint: String? = null) {
    Spacer(Modifier.height(6.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
    hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
}

/** Campo de texto do editor da empresa: erro por [errorKey] (prioritário) ou [hint] no texto de apoio. */
@Composable
private fun FormField(
    form: CompanyForm,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    errorKey: String? = null,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Next,
    mask: VisualTransformation = VisualTransformation.None,
) {
    val error = errorKey?.let { form.errors[it] }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true, enabled = !form.busy,
        isError = error != null, visualTransformation = mask,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType, imeAction = imeAction),
        modifier = modifier,
    )
}

private val CepMask = DigitMaskTransformation { "#####-###" }
private val CpfMask = DigitMaskTransformation { "###.###.###-##" }
/** Fixo (DD) ####-#### até 10 dígitos; celular (DD) #####-#### com 11. */
private val PhoneMask = DigitMaskTransformation { length -> if (length <= 10) "(##) ####-####" else "(##) #####-####" }

/**
 * Máscara de exibição sobre um texto só de dígitos: cada `#` de [maskFor] (escolhida pelo nº de dígitos) recebe um
 * dígito; os separadores só aparecem até o último dígito digitado. O valor guardado continua só com dígitos.
 */
internal class DigitMaskTransformation(private val maskFor: (Int) -> String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val (out, positions) = apply(text.text)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = positions[offset.coerceIn(0, positions.lastIndex)]
            override fun transformedToOriginal(offset: Int): Int {
                val o = offset.coerceIn(0, out.length)
                return (0 until positions.lastIndex).count { positions[it] < o }
            }
        }
        return TransformedText(AnnotatedString(out), mapping)
    }

    /** Texto mascarado e, para cada offset original (0..n), a posição correspondente no texto mascarado. */
    fun apply(digits: String): Pair<String, IntArray> {
        val mask = maskFor(digits.length)
        val out = StringBuilder()
        val positions = IntArray(digits.length + 1)
        var i = 0
        for (m in mask) {
            if (i >= digits.length) break
            if (m == '#') {
                positions[i] = out.length
                out.append(digits[i++])
            } else {
                out.append(m)
            }
        }
        while (i < digits.length) { positions[i] = out.length; out.append(digits[i++]) }
        positions[digits.length] = out.length
        return out.toString() to positions
    }
}

/** Máscara ##.###.###/####-## sobre um texto só de dígitos. */
private object CnpjVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text.take(14)
        val out = StringBuilder()
        digits.forEachIndexed { i, c ->
            when (i) {
                2, 5 -> out.append('.')
                8 -> out.append('/')
                12 -> out.append('-')
            }
            out.append(c)
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = when {
                offset <= 2 -> offset
                offset <= 5 -> offset + 1
                offset <= 8 -> offset + 2
                offset <= 12 -> offset + 3
                else -> offset + 4
            }.coerceAtMost(out.length)

            override fun transformedToOriginal(offset: Int): Int = when {
                offset <= 2 -> offset
                offset <= 6 -> offset - 1
                offset <= 10 -> offset - 2
                offset <= 15 -> offset - 3
                else -> offset - 4
            }.coerceIn(0, digits.length)
        }
        return TransformedText(AnnotatedString(out.toString()), mapping)
    }
}
