package com.licitaia.feature.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.platform.db.PlatformTenderEntity
import com.licitaia.core.platform.session.PlatformSession
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors

@Composable
fun PlatformTendersScreen(viewModel: PlatformTendersViewModel = hiltViewModel()) {
    val tenders by viewModel.tenders.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val pending by viewModel.pendingMutations.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    // Sessão perdida (logout ou 401 na sincronização) → volta ao login da plataforma.
    LaunchedEffect(session) {
        if (session is PlatformSession.SignedOut) {
            navigator.navigate(Routes.PLATFORM_LOGIN)
        }
    }

    val companyName = (session as? PlatformSession.SignedIn)?.companyName

    LicitaScaffold(
        title = "Licitações (plataforma)",
        subtitle = companyName,
        showBack = true,
        actions = {
            IconButton(onClick = viewModel::refresh, enabled = !sync.syncing) {
                if (sync.syncing) {
                    CircularProgressIndicator(Modifier.height(20.dp).padding(2.dp), strokeWidth = 2.dp, color = LicitaColors.Blue)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Sincronizar")
                }
            }
            IconButton(onClick = viewModel::logout) { Icon(Icons.Outlined.Logout, contentDescription = "Sair da plataforma") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                sync.message?.let { msg ->
                    item {
                        AlertBanner(
                            if (sync.isError) "Sincronização" else "Atualizado",
                            msg,
                            if (sync.isError) Tone.WARNING else Tone.SUCCESS,
                        )
                    }
                }
                if (pending > 0) {
                    item { AlertBanner("Fila offline", "$pending ação(ões) aguardando envio quando houver conexão.", Tone.INFO) }
                }
                if (tenders.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Nenhuma licitação ainda",
                            message = if (sync.syncing) "Sincronizando com a plataforma…" else "Toque em atualizar para sincronizar as licitações da sua empresa.",
                            actionLabel = if (sync.syncing) null else "Sincronizar agora",
                            onAction = if (sync.syncing) null else viewModel::refresh,
                        )
                    }
                } else {
                    items(tenders, key = { it.id }) { t -> TenderRow(t) }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun TenderRow(t: PlatformTenderEntity) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                t.numero.ifBlank { "Licitação" },
                style = MaterialTheme.typography.titleSmall,
                color = LicitaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            t.fase?.let { StatusBadge(it.replace('_', ' '), Tone.INFO) }
        }
        Spacer(Modifier.height(6.dp))
        Text(t.orgao, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(t.objeto, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        InfoRow("Valor estimado", PlatformFormat.currency(t.valorEstimado))
        InfoRow("Abertura", PlatformFormat.dateTime(t.dataAbertura))
        InfoRow("Encerramento", PlatformFormat.dateTime(t.dataEncerramento))
        listOfNotNull(t.portal, t.estado?.let { uf -> t.cidade?.let { "$it/$uf" } ?: uf }).takeIf { it.isNotEmpty() }?.let {
            InfoRow("Portal / Local", it.joinToString(" · "))
        }
    }
}
