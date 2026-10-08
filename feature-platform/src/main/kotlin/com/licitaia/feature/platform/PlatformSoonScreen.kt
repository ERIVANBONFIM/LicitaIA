package com.licitaia.feature.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.LicitaScaffold

/**
 * Estado gracioso para funções que ainda não têm endpoint na VPS (modo plataforma): Analisar Edital
 * (geração), Pregões ao Vivo, Sala de Guerra, Estratégias, Robô, Auditoria. Não trava o app; o modo local
 * continua oferecendo essas funções.
 */
@Composable
fun PlatformSoonScreen() {
    LicitaScaffold(title = "Em breve na nuvem", subtitle = "Modo plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyState(
                title = "Disponível em breve no modo nuvem",
                message = "Esta função ainda não está ligada à plataforma (VPS). Ela já funciona no modo local do aparelho; " +
                    "a versão em nuvem chega nas próximas etapas.",
            )
        }
    }
}
