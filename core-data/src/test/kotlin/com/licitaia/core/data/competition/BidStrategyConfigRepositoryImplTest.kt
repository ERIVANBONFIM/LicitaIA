package com.licitaia.core.data.competition

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.licitaia.core.data.repository.RepositoryAccess
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.domain.bidding.BidStrategyConfig
import com.licitaia.domain.bidding.DecrementMode
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.UserProfile
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuditRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Persistência da configuração padrão do robô de lances no DataStore (arquivo real em pasta temporária). */
class BidStrategyConfigRepositoryImplTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private val holder = SessionHolder()
    private val audit = mockk<AuditRepository>(relaxed = true)

    private fun session(role: UserRole, companyId: Long = 7) = AuthSession(
        user = UserProfile(id = 1, name = "Ana", email = "ana@empresa.com.br", role = role, companyIds = listOf(companyId)),
        activeCompany = Company(id = companyId, name = "Empresa", tradeName = "Empresa", cnpj = "12345678000190", segment = Segment.TI, uf = "MG", city = "BH"),
    )

    private fun newRepo(): BidStrategyConfigRepositoryImpl {
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { java.io.File(tmp.root, "prefs.preferences_pb") })
        return BidStrategyConfigRepositoryImpl(store, RepositoryAccess(holder), holder, audit)
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `salva, le de volta e isola por empresa`() = runBlocking {
        holder.set(session(UserRole.ADMIN))
        val repo = newRepo()
        assertEquals(BidStrategyConfig(), repo.get(7))

        val config = BidStrategyConfig(
            strategy = BidStrategy.AGRESSIVA, decrementMode = DecrementMode.PERCENTUAL, decrementPct = 1.25,
            minMarginPct = 12.0, reactOnlyWhenLosingFirst = false, finalBidEnabled = true, finalBidSecondsBefore = 8,
            minIntervalSeconds = 30, authorizationThresholdPct = 4.0,
        )
        repo.save(7, config)

        val saved = repo.get(7)
        assertEquals(config, saved.copy(updatedAt = 0, updatedBy = ""))
        assertTrue(saved.updatedAt > 0)
        assertEquals("Ana", saved.updatedBy)
        // Outra empresa continua no padrão.
        assertEquals(BidStrategyConfig(), repo.get(8))
        coVerify { audit.record(com.licitaia.domain.model.AuditAction.MUDANCA_REGRA, any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `persiste entre instancias do DataStore (reabrir o app)`() = runBlocking {
        holder.set(session(UserRole.DIRETORIA))
        newRepo().save(7, BidStrategyConfig(strategy = BidStrategy.PERSONALIZADA, decrementValue = 3.5))
        // Fecha o DataStore (o arquivo só é liberado quando o escopo termina) antes de abrir de novo.
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopened = newRepo().get(7)
        assertEquals(BidStrategy.PERSONALIZADA, reopened.strategy)
        assertEquals(3.5, reopened.decrementValue, 0.0)
    }

    @Test
    fun `perfil sem permissao ou configuracao invalida nao grava`() = runBlocking {
        holder.set(session(UserRole.TECNICO))
        val repo = newRepo()
        try {
            repo.save(7, BidStrategyConfig(strategy = BidStrategy.AGRESSIVA))
            fail("Técnico não pode alterar regras do robô")
        } catch (_: IllegalStateException) {
        }
        holder.set(session(UserRole.ADMIN))
        try {
            repo.save(7, BidStrategyConfig(decrementValue = 0.0))
            fail("Decremento zero é inválido")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(BidStrategyConfig(), repo.get(7))
    }
}
