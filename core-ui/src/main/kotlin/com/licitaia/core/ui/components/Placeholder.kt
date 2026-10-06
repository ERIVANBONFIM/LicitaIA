package com.licitaia.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Tela provisória usada enquanto uma rota ainda não foi implementada. */
@Composable
fun PlaceholderScreen(title: String, showBack: Boolean = false) {
    LicitaScaffold(title = title, showBack = showBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            EmptyState(
                title = title,
                message = "Esta tela está em construção.",
                icon = Icons.Outlined.Construction,
            )
        }
    }
}
