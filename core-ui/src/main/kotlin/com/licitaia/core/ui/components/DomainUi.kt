package com.licitaia.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.model.ProposalStatus
import com.licitaia.domain.model.Recommendation
import com.licitaia.domain.model.RiskLevel
import com.licitaia.domain.model.RobotStatus
import com.licitaia.domain.model.TenderStatus

// Mapeamento único enum de domínio → tom visual, para manter os status consistentes no app.

fun Recommendation.tone(): Tone = when (this) {
    Recommendation.PARTICIPAR -> Tone.SUCCESS
    Recommendation.AVALIAR -> Tone.WARNING
    Recommendation.NAO_PARTICIPAR -> Tone.DANGER
}

fun RiskLevel.tone(): Tone = when (this) {
    RiskLevel.BAIXO -> Tone.SUCCESS
    RiskLevel.MEDIO -> Tone.WARNING
    RiskLevel.ALTO, RiskLevel.CRITICO -> Tone.DANGER
}

fun DocumentStatus.tone(): Tone = when (this) {
    DocumentStatus.VALIDO -> Tone.SUCCESS
    DocumentStatus.VENCE_EM_BREVE -> Tone.WARNING
    DocumentStatus.VENCIDO -> Tone.DANGER
    DocumentStatus.AUSENTE -> Tone.NEUTRAL
}

fun ProposalStatus.tone(): Tone = when (this) {
    ProposalStatus.RASCUNHO -> Tone.NEUTRAL
    ProposalStatus.EM_REVISAO -> Tone.WARNING
    ProposalStatus.APROVADA, ProposalStatus.ENVIADA_SIMULADA -> Tone.SUCCESS
    ProposalStatus.REJEITADA -> Tone.DANGER
}

fun TenderStatus.tone(): Tone = when (this) {
    TenderStatus.INTERESSE, TenderStatus.EM_ANALISE, TenderStatus.ANALISADA, TenderStatus.PROPOSTA_EM_ELABORACAO -> Tone.INFO
    TenderStatus.AGUARDANDO_APROVACAO -> Tone.WARNING
    TenderStatus.APROVADA, TenderStatus.PRONTA_PARA_ENVIO, TenderStatus.ENVIADA_SIMULADA, TenderStatus.VENCIDA -> Tone.SUCCESS
    TenderStatus.EM_DISPUTA -> Tone.INFO
    TenderStatus.PERDIDA -> Tone.DANGER
    TenderStatus.DESCARTADA -> Tone.NEUTRAL
}

fun LiveStatus.tone(): Tone = when (this) {
    LiveStatus.EM_DISPUTA -> Tone.SUCCESS
    LiveStatus.AGUARDANDO -> Tone.INFO
    LiveStatus.PAUSADA -> Tone.WARNING
    LiveStatus.CAPTCHA_PENDENTE, LiveStatus.ERRO -> Tone.DANGER
    LiveStatus.ENCERRADA -> Tone.NEUTRAL
}

fun RobotStatus.tone(): Tone = when (this) {
    RobotStatus.ATIVO -> Tone.SUCCESS
    RobotStatus.PAUSADO, RobotStatus.AGUARDANDO_AUTORIZACAO, RobotStatus.PARADO_NO_PISO -> Tone.WARNING
    RobotStatus.BLOQUEADO_CAPTCHA, RobotStatus.ERRO -> Tone.DANGER
    RobotStatus.CONTROLE_MANUAL -> Tone.INFO
    RobotStatus.INATIVO, RobotStatus.ENCERRADO -> Tone.NEUTRAL
}

fun PortalConnectionStatus.tone(): Tone = when (this) {
    PortalConnectionStatus.CONECTADO -> Tone.SUCCESS
    PortalConnectionStatus.SESSAO_EXPIRADA, PortalConnectionStatus.MFA_PENDENTE -> Tone.WARNING
    PortalConnectionStatus.DESCONECTADO -> Tone.NEUTRAL
}

fun AuditResult.tone(): Tone = when (this) {
    AuditResult.SUCESSO -> Tone.SUCCESS
    AuditResult.FALHA -> Tone.DANGER
    AuditResult.BLOQUEADO -> Tone.WARNING
    AuditResult.PENDENTE -> Tone.INFO
}

fun NotificationCategory.tone(): Tone = when (this) {
    NotificationCategory.CAPTCHA, NotificationCategory.CRITICA -> Tone.DANGER
    NotificationCategory.LANCES, NotificationCategory.DOCUMENTOS -> Tone.WARNING
    NotificationCategory.MENSAGENS, NotificationCategory.SESSOES -> Tone.INFO
    NotificationCategory.RADAR -> Tone.SUCCESS
    NotificationCategory.GERAL -> Tone.NEUTRAL
}

fun Portal.color(): Color = when (this) {
    Portal.PNCP -> LicitaColors.Blue
    Portal.COMPRAS_GOV -> LicitaColors.BlueBright
    Portal.BLL -> LicitaColors.GreenBright
    Portal.LICITANET -> LicitaColors.Purple
    Portal.PORTAL_COMPRAS_PUBLICAS -> LicitaColors.Cyan
}

/** Identificação visual consistente do portal. */
@Composable
fun PortalChip(portal: Portal, modifier: Modifier = Modifier) {
    val color = portal.color()
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(portal.shortName, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Selo padrão "SIMULAÇÃO" exibido onde houver robô/envio. */
@Composable
fun SimulationBadge(modifier: Modifier = Modifier) {
    StatusBadge("SIMULAÇÃO", Tone.INFO, modifier)
}
