package com.licitaia.feature.platform

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors

/** Hub "Mais" do modo plataforma: seções lidas direto da VPS. */
@Composable
fun PlatformHubScreen() {
    val navigator = LocalAppNavigator.current
    LicitaScaffold(title = "Mais da plataforma", subtitle = "Dados sincronizados da VPS", showBack = true) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HubRow(Icons.Outlined.Business, LicitaColors.Green, "Empresas e Perfis", "Empresa e usuários da conta") {
                navigator.navigate(Routes.PLATFORM_DIRECTORY)
            }
            HubRow(Icons.Outlined.Radar, LicitaColors.Blue, "Radares salvos", "Filtros de busca salvos na plataforma") {
                navigator.navigate(Routes.PLATFORM_RADAR)
            }
            HubRow(Icons.Outlined.Folder, LicitaColors.Blue, "Documentos", "Documentos e certidões da empresa") {
                navigator.navigate(Routes.PLATFORM_DOCS)
            }
            HubRow(Icons.Outlined.Groups, LicitaColors.Yellow, "Concorrência", "Concorrentes mapeados") {
                navigator.navigate(Routes.PLATFORM_COMPETITORS)
            }
            HubRow(Icons.Outlined.Forum, LicitaColors.Blue, "Mensagens do pregoeiro", "Mensagens do chat de disputa") {
                navigator.navigate(Routes.PLATFORM_MESSAGES)
            }
        }
    }
}

@Composable
private fun HubRow(icon: ImageVector, color: androidx.compose.ui.graphics.Color, title: String, subtitle: String, onClick: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = LicitaColors.TextMuted)
        }
    }
}
