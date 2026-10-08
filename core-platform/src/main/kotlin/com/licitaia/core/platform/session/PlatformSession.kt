package com.licitaia.core.platform.session

import com.licitaia.core.platform.net.UserDto

/** Estado de sessão da plataforma exposto à UI. */
sealed interface PlatformSession {
    /** Ainda carregando do cofre (primeira leitura). */
    data object Unknown : PlatformSession

    /** Sem sessão: mostrar o login da plataforma. */
    data object SignedOut : PlatformSession

    /** Com sessão ativa. */
    data class SignedIn(val user: UserDto) : PlatformSession {
        val companyName: String get() = user.empresa?.razaoSocial?.takeIf { it.isNotBlank() } ?: "—"
        val isViewer: Boolean get() = user.role.equals("viewer", ignoreCase = true)
    }
}
