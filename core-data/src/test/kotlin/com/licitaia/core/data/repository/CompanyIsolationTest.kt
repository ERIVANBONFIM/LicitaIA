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
    private val demoCompany = company.copy(id = 9, name = "Demo Telecom Ltda", tradeName = "Demo Telecom", demo = true)
    private val userDao = mockk<UserDao>()
    private fun repository(): CompanyRepositoryImpl {
        every { db.companyDao() } returns dao
        every { db.userDao() } returns userDao
        every { dao.observeAll() } returns flowOf(listOf(company.toEntity(), company.copy(id = 2).toEntity(), demoCompany.toEntity()))
        every { userDao.observeAll() } returns flowOf(
            listOf(
                UserEntity(id = 1, name = "Pessoa", email = "pessoa@example.com", role = UserRole.ADMIN, companyIds = listOf(1), passwordHash = "x"),
                UserEntity(id = 2, name = "Pendente", email = "google@example.com", role = UserRole.LICITACOES, companyIds = emptyList(), passwordHash = "", provider = "GOOGLE", externalId = "sub"),
                UserEntity(id = 3, name = "Visitante", email = "demo@licitaia.app", role = UserRole.ADMIN, companyIds = listOf(9), passwordHash = "", demo = true),
            ),
        )
        return CompanyRepositoryImpl(db, holder, mockk<PasswordHasher>(), mockk<AuditRepository>(relaxed = true))
    }
    private fun login(role: UserRole = UserRole.ADMIN, demo: Boolean = false, active: Company = company, companyIds: List<Long> = listOf(1)) {
        holder.set(AuthSession(UserProfile(id = if (demo) 3 else 1, name = "Pessoa", email = "pessoa@example.com", role = role, companyIds = companyIds, demo = demo), active))
    }
    @Test fun `company list is empty before login and only owned after login`() = runTest {
        val repo = repository()
        assertTrue(repo.observeCompanies().first().isEmpty())
        login()
        assertEquals(listOf(1L), repo.observeCompanies().first().map { it.id })
        login(demo = true)
        assertTrue(repo.observeCompanies().first().isEmpty())
    }
    @Test fun `demo user sees only the demo company and real users never see it`() = runTest {
        val repo = repository()
        login(demo = true, active = demoCompany, companyIds = listOf(9))
        assertEquals(listOf(9L), repo.observeCompanies().first().map { it.id })
        // Mesmo "vinculado" por engano à empresa real, o demo não a vê.
        login(demo = true, active = demoCompany, companyIds = listOf(1, 9))
        assertEquals(listOf(9L), repo.observeCompanies().first().map { it.id })
        // Usuário real vinculado por engano à empresa demo não a vê.
        login(companyIds = listOf(1, 9))
        assertEquals(listOf(1L), repo.observeCompanies().first().map { it.id })
    }
    @Test fun `user lists are isolated between demo and real realms`() = runTest {
        val repo = repository()
        login(companyIds = listOf(1, 9))
        assertEquals(listOf(1L), repo.observeUsers(1).first().map { it.id })
        assertTrue(repo.observeUsers(9).first().none { it.demo })
        assertEquals(listOf(2L), repo.observeUnassignedUsers().first().map { it.id })
        login(demo = true, active = demoCompany, companyIds = listOf(9))
        assertEquals(listOf(3L), repo.observeUsers(9).first().map { it.id })
        assertTrue(repo.observeUnassignedUsers().first().isEmpty())
    }
    @Test fun `demo cannot create companies or manage users`() = runTest {
        val repo = repository()
        login(demo = true, active = demoCompany, companyIds = listOf(9))
        assertTrue(runCatching { repo.upsertCompany(demoCompany.copy(id = 0, cnpj = "11222333000181")) }.isFailure)
        assertTrue(runCatching { repo.upsertUser(UserProfile(name = "Novo", email = "novo@example.com", role = UserRole.LICITACOES, companyIds = listOf(9)), "senha123") }.isFailure)
        assertTrue(runCatching { repo.deleteUser(1) }.isFailure)
        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 0) { userDao.upsert(any()) }
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
