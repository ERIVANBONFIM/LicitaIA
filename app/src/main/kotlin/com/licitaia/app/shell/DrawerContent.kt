package com.licitaia.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.licitaia.core.ui.components.DemoBadge
import com.licitaia.core.ui.components.PulsingDot
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.Company

private data class DrawerItem(val route: String, val label: String, val icon: ImageVector)

private data class DrawerGroup(val title: String?, val items: List<DrawerItem>)

/** Itens do menu lateral, na ordem do SDD §4.1, agrupados para leitura. */
private val drawerGroups = listOf(
    DrawerGroup(
        null,
        listOf(DrawerItem(Routes.DASHBOARD, "Início", Icons.Outlined.Home)),
    ),
    DrawerGroup(
        "Oportunidades",
        listOf(
            DrawerItem(Routes.SEARCH, "Buscar Licitações", Icons.Outlined.Search),
            DrawerItem(Routes.RADAR, "Radar de Licitações", Icons.Outlined.Radar),
            DrawerItem(Routes.INTERESTS, "Licitações de Interesse", Icons.Outlined.StarOutline),
            DrawerItem(Routes.ANALYZE, "Analisar Edital", Icons.Outlined.Analytics),
            DrawerItem(Routes.PARTICIPATIONS, "Minhas Participações", Icons.Outlined.Inventory2),
        ),
    ),
    DrawerGroup(
        "Operação ao vivo",
        listOf(
            DrawerItem(Routes.LIVE, "Pregões ao Vivo", Icons.Outlined.Gavel),
            DrawerItem(Routes.WARROOM, "Sala de Guerra", Icons.Outlined.Shield),
            DrawerItem(Routes.STRATEGY, "Estratégias do robô", Icons.Outlined.Tune),
            DrawerItem(Routes.MESSAGES, "Mensagens do Pregoeiro", Icons.Outlined.Forum),
            DrawerItem(Routes.COMPETITION, "Concorrência", Icons.Outlined.Groups),
            DrawerItem(Routes.ROBOT, "Robô de Lances", Icons.Outlined.SmartToy),
        ),
    ),
    DrawerGroup(
        "Gestão",
        listOf(
            DrawerItem(Routes.DOCUMENTS, "Documentos", Icons.Outlined.Folder),
            DrawerItem(Routes.PORTALS, "Portais Conectados", Icons.Outlined.Hub),
            DrawerItem(Routes.AUDIT, "Auditoria", Icons.Outlined.History),
            DrawerItem(Routes.COMPANIES, "Empresas e Perfis", Icons.Outlined.Business),
            DrawerItem(Routes.SETTINGS, "Configurações", Icons.Outlined.Settings),
            DrawerItem(Routes.SECURITY, "Segurança", Icons.Outlined.Lock),
        ),
    ),
)

@Composable
fun DrawerContent(
    session: AuthSession?,
    companies: List<Company>,
    currentRoute: String?,
    criticalPending: Boolean,
    onNavigate: (String) -> Unit,
    onSwitchCompany: (Long) -> Unit,
    onLogout: () -> Unit,
    onExitDemo: () -> Unit = onLogout,
    onResetDemo: () -> Unit = {},
) {
    val demo = session?.user?.demo == true
    ModalDrawerSheet(
        drawerContainerColor = LicitaColors.Surface,
        drawerContentColor = LicitaColors.TextPrimary,
        modifier = Modifier.width(312.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DrawerHeader(session, companies, onSwitchCompany)
            drawerGroups.forEach { group ->
                if (group.title != null) {
                    Text(
                        group.title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = LicitaColors.TextMuted,
                        modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                group.items.forEach { item ->
                    DrawerRow(
                        label = item.label,
                        icon = item.icon,
                        selected = currentRoute == item.route,
                        alert = criticalPending && (item.route == Routes.LIVE || item.route == Routes.WARROOM),
                        onClick = { onNavigate(item.route) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp, horizontal = 20.dp), color = LicitaColors.Outline)
            if (demo) {
                Text(
                    "Demonstração".uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = LicitaColors.TextMuted,
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
                )
                DrawerRow(
                    label = "Reiniciar demonstração",
                    icon = Icons.Outlined.RestartAlt,
                    selected = false,
                    alert = false,
                    tint = LicitaColors.Yellow,
                    onClick = onResetDemo,
                )
                DrawerRow(
                    label = "Sair da demonstração",
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    selected = false,
                    alert = false,
                    tint = LicitaColors.RedBright,
                    onClick = onExitDemo,
                )
            } else {
                DrawerRow(
                    label = "Sair",
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    selected = false,
                    alert = false,
                    tint = LicitaColors.RedBright,
                    onClick = onLogout,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DrawerHeader(session: AuthSession?, companies: List<Company>, onSwitchCompany: (Long) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(LicitaColors.HeroGradient)
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(LicitaColors.BrandGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Radar, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("LicitaIA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Licitações com inteligência", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
            }
        }
        if (session != null) {
            Spacer(Modifier.height(18.dp))
            if (session.user.demo) {
                DemoBadge(compact = false)
                Text(
                    "Dados fictícios em espaço isolado. Nada aqui afeta empresas reais.",
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
            Text(session.user.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${session.user.role.label} · ${session.user.email}",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(LicitaColors.Background.copy(alpha = 0.45f))
                        .clickable(enabled = companies.size > 1) { menuOpen = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Business, contentDescription = null, tint = LicitaColors.GreenBright, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Empresa ativa", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
                        Text(
                            session.activeCompany.tradeName, style = MaterialTheme.typography.titleSmall,
                            color = LicitaColors.GreenBright, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (companies.size > 1) {
                        Icon(Icons.Outlined.UnfoldMore, contentDescription = "Trocar empresa", tint = LicitaColors.TextSecondary)
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = LicitaColors.SurfaceElevated) {
                    companies.forEach { company ->
                        DropdownMenuItem(
                            text = { Text(company.tradeName) },
                            leadingIcon = {
                                if (company.id == session.activeCompany.id) {
                                    Icon(Icons.Outlined.Check, contentDescription = null, tint = LicitaColors.Green)
                                }
                            },
                            onClick = {
                                menuOpen = false
                                if (company.id != session.activeCompany.id) onSwitchCompany(company.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    alert: Boolean,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    val color = tint ?: if (selected) LicitaColors.BlueBright else LicitaColors.TextPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) LicitaColors.Blue.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (tint != null || selected) color else LicitaColors.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label, style = MaterialTheme.typography.bodyLarge, color = color,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (alert) PulsingDot(LicitaColors.Red)
    }
}
