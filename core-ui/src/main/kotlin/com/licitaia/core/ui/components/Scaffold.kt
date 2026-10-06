package com.licitaia.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.LocalShellState
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors

/**
 * Scaffold padrão de TODAS as telas logadas: barra superior com menu/voltar, título,
 * empresa ativa sempre visível e sino de notificações (vermelho pulsante quando há
 * CAPTCHA/alerta crítico pendente).
 *
 * @param showBack true = seta de voltar; false = ícone do menu lateral (telas de topo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicitaScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBack: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val navigator = LocalAppNavigator.current
    val shell = LocalShellState.current
    Scaffold(
        modifier = modifier,
        containerColor = LicitaColors.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LicitaColors.Background,
                    titleContentColor = LicitaColors.TextPrimary,
                    navigationIconContentColor = LicitaColors.TextPrimary,
                    actionIconContentColor = LicitaColors.TextPrimary,
                ),
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar") }
                    } else {
                        IconButton(onClick = navigator::openDrawer) { Icon(Icons.Outlined.Menu, contentDescription = "Menu") }
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (shell.demo) {
                                Spacer(Modifier.width(8.dp))
                                DemoBadge()
                            }
                        }
                        val sub = subtitle ?: shell.companyName.takeIf { it.isNotBlank() }
                        if (sub != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (subtitle == null) {
                                    Icon(Icons.Outlined.Business, contentDescription = null, tint = LicitaColors.GreenBright, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    sub, style = MaterialTheme.typography.labelSmall,
                                    color = if (subtitle == null) LicitaColors.GreenBright else LicitaColors.TextSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    actions()
                    NotificationBell(
                        unread = shell.unreadNotifications,
                        critical = shell.criticalPending,
                        onClick = { navigator.navigate(Routes.NOTIFICATIONS) },
                    )
                },
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        content = content,
    )
}

/** Selo persistente do modo demonstração (dados fictícios, espaço isolado). */
@Composable
fun DemoBadge(modifier: Modifier = Modifier, compact: Boolean = true) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LicitaColors.Yellow.copy(alpha = 0.22f))
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 2.dp else 4.dp),
    ) {
        Text(
            "DEMONSTRAÇÃO",
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = LicitaColors.Yellow, fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

/** Sino de notificações com contador; fica vermelho e pulsa quando [critical]. */
@Composable
fun NotificationBell(unread: Int, critical: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.padding(end = 4.dp)) {
        IconButton(onClick = onClick) {
            Icon(
                if (critical) Icons.Outlined.NotificationsActive else Icons.Outlined.Notifications,
                contentDescription = "Notificações",
                tint = if (critical) LicitaColors.Red else LicitaColors.TextPrimary,
            )
        }
        if (critical) {
            PulsingDot(LicitaColors.Red, Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = 8.dp), size = 10.dp)
        } else if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 6.dp)
                    .clip(CircleShape)
                    .background(LicitaColors.Blue)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(if (unread > 99) "99+" else "$unread", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Chip de filtro simples (seleção única ou múltipla controlada pelo chamador). */
@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = LicitaColors.Blue) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) color.copy(alpha = 0.18f) else LicitaColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .widthIn(min = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelMedium,
            color = if (selected) color else LicitaColors.TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
        )
    }
}
