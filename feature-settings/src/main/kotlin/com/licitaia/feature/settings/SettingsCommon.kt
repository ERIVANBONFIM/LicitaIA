package com.licitaia.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.theme.LicitaColors

/** Linha de preferência booleana. [haptic] vibra levemente ao alternar (preferência "feedback tátil"). */
@Composable
internal fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    haptic: Boolean = false,
) {
    val hapticFeedback = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) LicitaColors.TextPrimary else LicitaColors.TextMuted)
            Text(description, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            if (!enabled && disabledReason != null) {
                Text(disabledReason, style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = {
                if (haptic) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = LicitaColors.Blue,
                uncheckedThumbColor = LicitaColors.TextSecondary, uncheckedTrackColor = LicitaColors.SurfaceHigh,
                uncheckedBorderColor = LicitaColors.Outline,
            ),
        )
    }
}

/** Escolha única em chips (intervalos, timeouts...). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> OptionChips(
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = LicitaColors.Blue,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) LicitaColors.TextPrimary else LicitaColors.TextMuted)
        Text(description, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                SelectChip(label(option), option == selected, onClick = { if (enabled) onSelect(option) }, color = color)
            }
        }
    }
}

/** Atalho de navegação em card (Configurações → subtelas). */
@Composable
internal fun NavRow(icon: ImageVector, title: String, description: String, color: Color = LicitaColors.Blue, badge: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Text(description, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
            if (badge != null) {
                badge()
                Spacer(Modifier.width(6.dp))
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = LicitaColors.TextMuted)
        }
    }
}

internal fun minutesLabel(minutes: Int): String = when (minutes) {
    0 -> "Desativado"
    1 -> "1 min"
    else -> "$minutes min"
}
