package com.licitaia.feature.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.platform.net.EmpresaDetailDto
import com.licitaia.core.platform.net.UsuarioDto
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.theme.LicitaColors

@Composable
fun PlatformDirectoryScreen(viewModel: PlatformDirectoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LicitaScaffold(title = "Empresas e Perfis", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LicitaColors.Blue)
                }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    item { Text("Empresas", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextMuted) }
                    items(state.empresas, key = { it.id }) { EmpresaCard(it) }
                    item { Spacer(Modifier.height(4.dp)) }
                    item { Text("Usuários (${state.usuarios.size})", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextMuted) }
                    items(state.usuarios, key = { it.id }) { UsuarioCard(it) }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EmpresaCard(e: EmpresaDetailDto) {
    LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Green) {
        Text(e.razaoSocial.ifBlank { e.nomeFantasia ?: "Empresa" }, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        e.nomeFantasia?.takeIf { it.isNotBlank() }?.let { InfoRow("Nome fantasia", it) }
        InfoRow("CNPJ", e.cnpj.ifBlank { "—" })
        InfoRow("Local", listOfNotNull(e.cidade, e.estado).joinToString("/").ifBlank { "—" })
    }
}

@Composable
private fun UsuarioCard(u: UsuarioDto) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Text(u.nome.ifBlank { "Usuário" }, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(u.email, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        StatusBadge(u.role, Tone.INFO)
        if (!u.ativo) {
            Spacer(Modifier.height(6.dp))
            StatusBadge("inativo", Tone.WARNING)
        }
    }
}
