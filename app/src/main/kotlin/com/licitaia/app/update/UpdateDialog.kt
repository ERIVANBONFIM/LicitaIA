package com.licitaia.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.theme.LicitaColors
import java.util.Locale

/** Diálogo global "Nova versão disponível" com notas, tamanho, download e instalação. */
@Composable
fun UpdateDialog(state: UpdateUiState, viewModel: UpdateViewModel) {
    val update = state.update ?: return
    val context = LocalContext.current

    // APK baixado e verificado: entrega ao instalador uma única vez.
    LaunchedEffect(state.installRequested, state.file) {
        if (state.installRequested && state.file != null) viewModel.reopenInstaller(context)
    }

    val downloading = state.phase == UpdatePhase.DOWNLOADING
    Dialog(
        onDismissRequest = { if (!downloading) viewModel.later() },
        properties = DialogProperties(dismissOnBackPress = !downloading, dismissOnClickOutside = false),
    ) {
        val shape = MaterialTheme.shapes.extraLarge
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(LicitaColors.SurfaceElevated)
                .border(1.dp, LicitaColors.Outline, shape)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Outlined.SystemUpdate, LicitaColors.Blue, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Nova versão ${update.versionName} disponível",
                        style = MaterialTheme.typography.titleMedium,
                        color = LicitaColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        buildString {
                            append(update.tag)
                            update.apkSize?.let { append(" · ").append(formatBytes(it)) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LicitaColors.TextSecondary,
                    )
                }
                StatusBadge("GitHub", Tone.INFO)
            }

            Spacer(Modifier.height(14.dp))

            if (update.title.isNotBlank() && update.title != update.tag) {
                Text(update.title, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
            }
            Text("Notas da versão", style = MaterialTheme.typography.labelLarge, color = LicitaColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 220.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(LicitaColors.Surface)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    update.notes.ifBlank { "Sem notas publicadas para esta versão." },
                    style = MaterialTheme.typography.bodySmall,
                    color = LicitaColors.TextPrimary,
                )
            }

            Spacer(Modifier.height(14.dp))

            when (state.phase) {
                UpdatePhase.OFFER -> {
                    Text(
                        "O download só começa quando você tocar em \"Atualizar agora\". Depois, o instalador do Android pedirá confirmação.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LicitaColors.TextSecondary,
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Atualizar agora", onClick = { viewModel.updateNow(context) }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Download)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Depois", onClick = viewModel::later, modifier = Modifier.fillMaxWidth(), tone = Tone.NEUTRAL)
                }

                UpdatePhase.PERMISSION -> {
                    AlertBanner(
                        "Autorize a instalação",
                        "Como o LicitaIA não vem da loja, o Android exige permitir \"instalar apps desconhecidos\" para este app. Toque em Autorizar, ative a opção e volte para continuar.",
                        Tone.WARNING,
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Autorizar nas configurações", onClick = { viewModel.openInstallPermission(context) }, modifier = Modifier.fillMaxWidth(), tone = Tone.WARNING)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Depois", onClick = viewModel::later, modifier = Modifier.fillMaxWidth(), tone = Tone.NEUTRAL)
                }

                UpdatePhase.DOWNLOADING -> {
                    val progress = state.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = LicitaColors.Blue, trackColor = LicitaColors.SurfaceHigh)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = LicitaColors.Blue, trackColor = LicitaColors.SurfaceHigh)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Baixando APK…", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                        Text(
                            buildString {
                                append(formatBytes(state.downloadedBytes))
                                state.totalBytes?.let { append(" / ").append(formatBytes(it)) }
                                progress?.let { append("  ").append(String.format(Locale.getDefault(), "%d%%", (it * 100).toInt())) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = LicitaColors.TextPrimary,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton("Cancelar download", onClick = viewModel::cancelDownload, modifier = Modifier.fillMaxWidth(), tone = Tone.DANGER)
                }

                UpdatePhase.READY -> {
                    AlertBanner(
                        "APK pronto",
                        "O instalador do Android foi aberto. Se ele não apareceu ou você cancelou, toque em \"Abrir instalador\". Seus dados são preservados na atualização.",
                        Tone.SUCCESS,
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Abrir instalador", onClick = { viewModel.reopenInstaller(context) }, modifier = Modifier.fillMaxWidth(), tone = Tone.SUCCESS, icon = Icons.Outlined.SystemUpdate)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = viewModel::dismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Fechar", color = LicitaColors.TextSecondary)
                    }
                }

                UpdatePhase.ERROR -> {
                    AlertBanner("Não foi possível atualizar", state.error ?: "Erro desconhecido.", Tone.DANGER)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Tentar novamente", onClick = { viewModel.updateNow(context) }, modifier = Modifier.fillMaxWidth())
                    if (update.pageUrl != null) {
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("Abrir release no navegador", onClick = { viewModel.openReleasePage(context) }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.OpenInNew, tone = Tone.NEUTRAL)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = viewModel::later, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Depois", color = LicitaColors.TextSecondary)
                    }
                }
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
