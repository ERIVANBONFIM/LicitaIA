package com.licitaia.core.data.repository

import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.LicitaDatabase
import com.licitaia.core.data.db.UserDao
import com.licitaia.core.data.db.UserEntity
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.seed.DatabaseSeeder
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.core.data.settings.SessionPrefs
import com.licitaia.core.security.PasswordHasher
import com.licitaia.core.security.SecretStore
import com.licitaia.domain.auth.GoogleIdentity
import com.licitaia.domain.auth.NoLocalLinkException
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.UserRole
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vínculo Google ↔ conta local: só com a senha local correta; perfil e empresas são mantidos. */
class AuthGoogleLinkTest {
    private val db = mockk<LicitaDatabase>(relaxed = true)
    private val holder = SessionHolder()
    private val userDao = mockk<UserDao>()
    private val companyDao = mockk<CompanyDao>()
    private val hasher = mockk<PasswordHasher>()
    private val sessionPrefs = mockk<SessionPrefs>(relaxed = true)
    private val audit = mockk<AuditRepositoryImpl>(relaxed = true)
    private val seeder = mockk<DatabaseSeeder>(relaxed = true)

    private val company = Company(id = 1, name = "Minha empresa", tradeName = "Minha", cnpj = "11222333000181", segment = Segment.PERSONALIZADO, uf = "BA", city = "Salvador")
    private val local = UserEntity(id = 5, name = "Ana", email = "ana@example.com", role = UserRole.ADMIN, companyIds = listOf(1), passwordHash = "pbkdf2:hash", provider = "LOCAL", externalId = null)
    private val identity = GoogleIdentity(subject = "sub-123", email = "Ana@Example.com", emailVerified = true, name = "Ana G.")

    private fun repo() = AuthRepositoryImpl(db, holder, userDao, companyDao, hasher, mockk<SecretStore>(relaxed = true), sessionPrefs, audit, seeder)

    private fun stubUser(user: UserEntity?) {
        coEvery { userDao.getByEmail("ana@example.com") } returns user
        coEvery { userDao.getByExternalId("GOOGLE", "sub-123") } returns null
        coEvery { companyDao.getById(1) } returns company.toEntity()
    }

    @Test fun `google login with existing local account asks for the local password instead of auto linking`() = runTest {
        stubUser(local)
        val result = repo().loginWithGoogle(identity, remember = true)
        assertTrue(result.exceptionOrNull() is NoLocalLinkException)
        assertEquals("ana@example.com", (result.exceptionOrNull() as NoLocalLinkException).email)
        assertNull(holder.current)
        coVerify(exactly = 0) { userDao.upsert(any()) }
    }

    @Test fun `wrong local password refuses the link and changes nothing`() = runTest {
        stubUser(local)
        every { hasher.verify("errada", local.passwordHash) } returns false
        val result = repo().linkGoogleToLocal(identity, "errada", remember = true)
        assertTrue(result.isFailure)
        assertNull(holder.current)
        coVerify(exactly = 0) { userDao.upsert(any()) }
        coVerify(exactly = 0) { sessionPrefs.save(any(), any()) }
    }

    @Test fun `correct local password links identity keeping role companies and password`() = runTest {
        stubUser(local)
        every { hasher.verify("certa123", local.passwordHash) } returns true
        val saved = slot<UserEntity>()
        coEvery { userDao.upsert(capture(saved)) } returns 5L
        coEvery { userDao.getById(5) } answers { saved.captured }

        val result = repo().linkGoogleToLocal(identity, "certa123", remember = true)

        assertTrue(result.isSuccess)
        assertEquals("GOOGLE", saved.captured.provider)
        assertEquals("sub-123", saved.captured.externalId)
        assertEquals(local.passwordHash, saved.captured.passwordHash)
        assertEquals(UserRole.ADMIN, saved.captured.role)
        assertEquals(listOf(1L), saved.captured.companyIds)
        assertEquals("Ana", saved.captured.name)
        assertEquals(1L, holder.current?.activeCompany?.id)
        coVerify { sessionPrefs.save(5L, 1L) }
    }

    @Test fun `demo or unknown accounts cannot be linked`() = runTest {
        stubUser(null)
        every { hasher.verify(any(), any()) } returns true
        assertTrue(repo().linkGoogleToLocal(identity, "qualquer", remember = false).isFailure)
        stubUser(local.copy(demo = true))
        assertTrue(repo().linkGoogleToLocal(identity, "qualquer", remember = false).isFailure)
        stubUser(local.copy(externalId = "outro-sub"))
        assertTrue(repo().linkGoogleToLocal(identity, "qualquer", remember = false).isFailure)
        coVerify(exactly = 0) { userDao.upsert(any()) }
    }
}
