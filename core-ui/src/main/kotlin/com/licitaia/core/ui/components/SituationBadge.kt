package com.licitaia.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.licitaia.domain.model.OfficialSituation
import com.licitaia.domain.model.SituationSeverity

/** Cor do selo da situação oficial: vermelho (cancelada/revogada/anulada), âmbar (suspensa), cinza (deserta/fracassada). */
fun SituationSeverity.tone(): Tone = when (this) {
    SituationSeverity.RED -> Tone.DANGER
    SituationSeverity.AMBER -> Tone.WARNING
    SituationSeverity.GRAY -> Tone.NEUTRAL
}

/** Selo colorido da situação oficial (nada quando a contratação está normal). */
@Composable
fun OfficialSituationBadge(situation: OfficialSituation?, modifier: Modifier = Modifier) {
    val s = situation ?: return
    StatusBadge(s.label, s.severity.tone(), modifier)
}

/** Selo de situação a partir do texto do portal ("Compra suspensa", "Revogada"...); nada quando normal. */
@Composable
fun OfficialSituationBadge(text: String?, modifier: Modifier = Modifier) {
    OfficialSituationBadge(OfficialSituation.fromText(text), modifier)
}
