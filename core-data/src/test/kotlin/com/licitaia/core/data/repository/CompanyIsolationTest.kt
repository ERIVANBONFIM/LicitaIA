package com.licitaia.core.data.repository

import com.licitaia.core.data.db.*
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.core.security.PasswordHasher
import com.licitaia.domain.model.*
import com.licitaia.domain.repository.AuditRepository
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompanyIsolationTest {
    private val db = mockk<LicitaDatabase>()
    private val dao = mockk<CompanyDao>()
    private val holder = SessionHolder()
    private val company = Company(id = 1, name = "Minha empresa", tradeName = "Minha", cnpj = "11222333000181", segment = Segment.PERSONALIZADO, uf = "BA", city = "Salvador")
    private fun repository(): CompanyRepositoryImpl {
        every { db.companyDao() } returns dao
        every { dao.observeAll() } returns flowOf(listOf(company.toEntity(), company.copy(id = 2).toEntity()))
        return CompanyRepositoryImpl(db, holder, mockk<PasswordHasher>(), mockk<AuditRepository>(relaxed = true))
    }
    private fun login(role: UserRole = UserRole.ADMIN, demo: Boolean = false) {
        holder.set(AuthSession(UserProfile(id = 1, name = "Pessoa", email = "pessoa@example.com", role = role, companyIds = listOf(1), demo = demo), company))
    }
    @Test fun `company list is empty before login and only owned after login`() = runTest {
        val repo = repository()
        assertTrue(repo.observeCompanies().first().isEmpty())
        login()
        assertEquals(listOf(1L), repo.observeCompanies().first().map { it.id })
        login(demo = true)
        assertTrue(repo.observeCompanies().first().isEmpty())
    }
    @Test fun `admin cannot edit foreign company and operational role cannot edit own`() = runTest {
        val repo = repository()
        login()
        assertTrue(runCatching { repo.upsertCompany(company.copy(id = 2)) }.isFailure)
        login(UserRole.LICITACOES)
        assertTrue(runCatching { repo.upsertCompany(company) }.isFailure)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }
}
