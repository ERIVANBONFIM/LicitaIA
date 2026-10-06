package com.licitaia.core.data.repository

import com.licitaia.core.data.session.SessionHolder
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.UserProfile
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.security.Permission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fronteira de autorização local usada por todos os repositórios escopados por empresa. */
class RepositoryAccessTest {
    private val holder = SessionHolder()
    private val access = RepositoryAccess(holder)
    private val company1 = Company(id = 1, name = "Minha", tradeName = "Minha", cnpj = "11222333000181", segment = Segment.PERSONALIZADO, uf = "BA", city = "Salvador")
    private val company2 = company1.copy(id = 2, name = "Outra", tradeName = "Outra")

    private fun login(role: UserRole = UserRole.ADMIN, demo: Boolean = false, active: Company = company1, companyIds: List<Long> = listOf(1, 2)) {
        holder.set(AuthSession(UserProfile(id = 1, name = "Pessoa", email = "p@example.com", role = role, companyIds = companyIds, demo = demo), active))
    }

    @Test fun `nothing is owned without session`() {
        assertFalse(access.owns(1))
        assertTrue(runCatching { access.requireCompany(1) }.isFailure)
    }

    @Test fun `only the active company is owned even when the user is linked to others`() {
        login()
        assertTrue(access.owns(1))
        assertFalse(access.owns(2)) // vinculada, mas não ativa: evita operar na empresa errada
        assertFalse(access.owns(0))
        assertFalse(access.owns(99))
    }

    @Test fun `active company outside the user's links is denied`() {
        login(active = company2, companyIds = listOf(1))
        assertFalse(access.owns(2))
    }

    @Test fun `demo account never owns real data`() {
        login(demo = true)
        assertFalse(access.owns(1))
        assertTrue(runCatching { access.requireCompany(1) }.isFailure)
    }

    @Test fun `rbac is enforced on top of ownership`() {
        login(role = UserRole.LICITACOES)
        assertTrue(runCatching { access.requireCompany(1, Permission.OPERAR_SESSOES) }.isSuccess)
        assertTrue(runCatching { access.requireCompany(1, Permission.CONFIGURAR_IA) }.isFailure)
        assertTrue(runCatching { access.requireCompany(1, Permission.APROVAR_PISO) }.isFailure)
        login(role = UserRole.ADMIN)
        assertTrue(runCatching { access.requireCompany(1, Permission.CONFIGURAR_IA) }.isSuccess)
    }
}
