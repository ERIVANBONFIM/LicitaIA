package com.licitaia.core.platform.session

import com.licitaia.core.platform.net.UserDto
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.UserProfile
import com.licitaia.domain.model.UserRole
import kotlin.math.absoluteValue

/**
 * Ponte de IDENTIDADE entre a sessão da PLATAFORMA (VPS) e a sessão local que o shell/telas locais consomem.
 *
 * No modo plataforma não há sessão local; para reaproveitar o MESMO app (dashboard, menu, Segurança, IA,
 * Portais) montamos uma [AuthSession] sintética com uma identidade ESTÁVEL derivada do `empresa.id` real da
 * VPS (um UUID). As telas/repos locais escopam por `companyId: Long`, então convertemos o UUID num Long
 * POSITIVO, grande e determinístico — estável por empresa, isolado das contas locais (ids pequenos
 * sequenciais) e que satisfaz a verificação de posse (`RepositoryAccess.owns`: exige `> 0`, pertencer ao
 * usuário e "mesmo mundo" não-demo).
 */
object PlatformIdentity {

    /** Base dos ids sintéticos (1 bilhão): os ids locais autoincrement nunca chegam perto, evitando colisão. */
    private const val SCOPE_BASE = 1_000_000_000L

    /** Long positivo, grande e estável derivado do UUID da empresa da VPS. */
    fun companyScopeId(empresaId: String?): Long {
        val id = empresaId?.trim().orEmpty()
        if (id.isEmpty()) return SCOPE_BASE
        // 30 bits do hash → sempre < ~1.07e9; somado à base fica entre 1e9 e ~2.07e9, sempre positivo.
        val h = (id.hashCode().toLong().absoluteValue) and 0x3FFF_FFFFL
        return SCOPE_BASE + h
    }

    /** Sessão local sintética para o modo plataforma (identidade estável, não-demo). */
    fun session(user: UserDto): AuthSession {
        val scopeId = companyScopeId(user.empresa?.id)
        val role = when (user.role.lowercase()) {
            "admin" -> UserRole.ADMIN
            "diretoria" -> UserRole.DIRETORIA
            "financeiro" -> UserRole.FINANCEIRO
            "tecnico", "viewer" -> UserRole.TECNICO
            else -> UserRole.LICITACOES
        }
        val name = user.empresa?.razaoSocial?.takeIf { it.isNotBlank() } ?: "Minha empresa"
        return AuthSession(
            user = UserProfile(
                id = scopeId,
                name = user.nome.ifBlank { user.email },
                email = user.email,
                role = role,
                companyIds = listOf(scopeId),
                demo = false,
            ),
            activeCompany = Company(
                id = scopeId,
                name = name,
                tradeName = name,
                cnpj = user.empresa?.cnpj.orEmpty(),
                segment = Segment.PERSONALIZADO,
                uf = "",
                city = "",
                demo = false,
            ),
        )
    }
}
