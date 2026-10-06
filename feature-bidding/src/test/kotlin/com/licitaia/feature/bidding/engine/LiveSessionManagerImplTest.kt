package com.licitaia.feature.bidding.engine

import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.live.LiveSessionStore
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.BidEvent
import com.licitaia.domain.model.BidEventType
import com.licitaia.domain.model.BidResult
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.model.RobotStatus
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.model.UserProfile
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompetitionRepository
import com.licitaia.domain.repository.TenderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionManagerImplTest {

    private val company = Company(id = 7, name = "Empresa", tradeName = "Empresa", cnpj = "00000000000191", segment = Segment.TI, uf = "CE", city = "Fortaleza")
    private val user = UserProfile(id = 1, name = "Ana", email = "ana@x.com", role = UserRole.ADMIN, companyIds = listOf(7))
    private val rule = BidRule(
        mode = RobotMode.MANUAL, strategy = BidStrategy.CONSERVADORA,
        initialPrice = 100_000.0, floorPrice = 80_000.0, costPrice = 70_000.0, reductionValue = 1_000.0, minMarginPct = 15.0,
        authorizationThresholdPct = 5.0,
    )

    /** Store em memória: sessões e eventos por sessão (mais recente primeiro). */
    private class FakeStore : LiveSessionStore {
        val sessions = linkedMapOf<String, LiveSession>()
        val events = mutableListOf<BidEvent>()
        private val eventsFlow = MutableStateFlow<List<BidEvent>>(emptyList())
        override suspend fun loadSessions(companyId: Long) = sessions.values.filter { it.companyId == companyId && it.status != LiveStatus.ENCERRADA }
        override suspend fun saveSession(session: LiveSession) { sessions[session.id] = session }
        override suspend fun deleteSession(sessionId: String) { sessions.remove(sessionId) }
        override suspend fun appendEvent(event: BidEvent) { events += event; eventsFlow.value = events.toList() }
        override fun observeEvents(sessionId: String): Flow<List<BidEvent>> = eventsFlow.map { l -> l.filter { it.sessionId == sessionId }.reversed() }
    }

    private class Harness(private val scope: TestScope, session: AuthSession) {
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val store = FakeStore()
        val notifier: AppNotifier = mockk(relaxed = true)
        val audit: AuditRepository = mockk(relaxed = true)
        val competition: CompetitionRepository = mockk(relaxed = true)
        val tenders: TenderRepository = mockk(relaxed = true)
        val auth: AuthRepository = mockk<AuthRepository>(relaxed = true).also { every { it.session } returns MutableStateFlow<AuthSession?>(session) }
        var now = 1_000_000L
        val manager = LiveSessionManagerImpl(store, notifier, audit, auth, competition, tenders, dispatcher, { now })

        /** Executa as tarefas pendentes do escalonador (ator, persistência, stateIn) e devolve as sessões visíveis. */
        fun sessions(): List<LiveSession> {
            val flow = manager.sessions // inicializa o stateIn (lazy) antes de rodar o escalonador
            scope.testScheduler.runCurrent()
            return flow.value
        }
    }

    private fun spec(tenderId: Long? = null) = LiveSessionSpec(
        companyId = 7, tenderId = tenderId, portal = Portal.COMPRAS_GOV, tenderNumber = "PE 1/2026", agency = "Órgão",
        itemLabel = "Item 1", objectDescription = "Objeto do pregão", rule = rule, competitors = 3,
    )

    @Test
    fun `sessao assistida nasce aguardando sem robo e e persistida`() = runTest {
        val h = Harness(this, AuthSession(user, company))
        val id = h.manager.openSession(spec())
        val s = h.sessions().single()
        assertEquals(id, s.id)
        assertEquals(LiveStatus.AGUARDANDO, s.status)
        assertEquals(RobotStatus.INATIVO, s.robotStatus)
        assertEquals(RobotMode.MANUAL, s.rule.mode)
        assertTrue(h.store.sessions.containsKey(id))
        assertTrue(h.store.events.any { it.type == BidEventType.SESSION_OPENED })
    }

    @Test
    fun `registro abaixo do piso e recusado e auditado como bloqueado`() = runTest {
        val h = Harness(this, AuthSession(user, company))
        val id = h.manager.openSession(spec())
        val result = h.manager.recordOurBid(id, 79_000.0)
        assertTrue(result is BidResult.Rejected)
        assertTrue((result as BidResult.Rejected).reason.contains("piso"))
        val s = h.sessions().single()
        assertEquals(null, s.ourLastBid)
        assertTrue(h.store.events.any { it.type == BidEventType.BID_BLOCKED })
        coVerify { h.audit.record(AuditAction.LANCE, AuditResult.BLOQUEADO, any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `registro valido atualiza telemetria margem e alerta quando perto do piso`() = runTest {
        val h = Harness(this, AuthSession(user, company))
        val id = h.manager.openSession(spec())
        h.manager.recordCompetitorBid(id, 95_000.0)
        val ok = h.manager.recordOurBid(id, 94_000.0)
        assertTrue(ok is BidResult.Accepted)
        var s = h.sessions().single()
        assertEquals(1, s.position)
        assertEquals(94_000.0, s.bestBid!!, 1e-9)
        assertEquals(LiveStatus.EM_DISPUTA, s.status)
        assertEquals(rule.marginPct(94_000.0), s.currentMarginPct, 1e-9)

        // concorrente cobre → perdemos posição; nosso novo lance perto do piso → alerta LANCES
        h.manager.recordCompetitorBid(id, 83_000.0)
        s = h.sessions().single()
        assertEquals(2, s.position)
        h.manager.recordOurBid(id, 82_000.0)
        s = h.sessions().single()
        assertEquals(1, s.position)
        assertEquals(2_000.0, s.ourLastBid!! - BidRuleEngine.effectiveFloor(s.rule), 1e-9)
        coVerify(atLeast = 1) { h.notifier.notify(NotificationCategory.LANCES, any(), any(), any(), any(), id, 7L) }
        assertEquals(2, h.store.events.count { it.type == BidEventType.OUR_BID })
        coVerify(atLeast = 2) { h.audit.record(AuditAction.LANCE, AuditResult.SUCESSO, any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fechamento com resultado gera registro de concorrencia e atualiza a licitacao`() = runTest {
        val h = Harness(this, AuthSession(user, company))
        val recorded = slot<CompetitionRecord>()
        coEvery { h.competition.insert(capture(recorded)) } returns 1L
        val id = h.manager.openSession(spec(tenderId = 42))
        h.manager.recordOurBid(id, 90_000.0)
        h.manager.recordOurBid(id, 88_000.0)
        h.manager.finishSession(id, won = true, finalValue = 88_000.0)

        assertTrue(recorded.isCaptured)
        val r = recorded.captured
        assertEquals(7L, r.companyId)
        assertTrue(r.won)
        assertEquals(88_000.0, r.closingValue, 1e-9)
        assertEquals(88_000.0, r.ourFinalBid, 1e-9)
        assertEquals(2, r.bidsCount)
        assertEquals(Segment.TI, r.segment)
        assertEquals(rule.marginPct(88_000.0), r.ourMarginPct, 1e-9)
        coVerify { h.tenders.updateStatus(42L, TenderStatus.VENCIDA) }

        val s = h.sessions().single()
        assertEquals(LiveStatus.ENCERRADA, s.status)
        assertTrue(s.isWinning)
        assertEquals(LiveStatus.ENCERRADA, h.store.sessions[id]!!.status)
        assertTrue(h.store.events.any { it.type == BidEventType.SESSION_CLOSED })

        // remover da lista depois de encerrada
        h.manager.closeSession(id)
        assertTrue(h.sessions().isEmpty())
    }

    @Test
    fun `derrota marca licitacao como perdida`() = runTest {
        val h = Harness(this, AuthSession(user, company))
        val recorded = slot<CompetitionRecord>()
        coEvery { h.competition.insert(capture(recorded)) } returns 1L
        val id = h.manager.openSession(spec(tenderId = 9))
        h.manager.recordOurBid(id, 86_000.0)
        h.manager.recordCompetitorBid(id, 85_000.0)
        h.manager.finishSession(id, won = false, finalValue = 85_000.0)
        assertFalse(recorded.captured.won)
        assertEquals(86_000.0, recorded.captured.ourFinalBid, 1e-9)
        coVerify { h.tenders.updateStatus(9L, TenderStatus.PERDIDA) }
    }

    @Test
    fun `cronometro conta alerta abaixo de 60s e respeita o silencio da sala de guerra`() = runTest {
        val h = Harness(this, AuthSession(user, company))
        val id = h.manager.openSession(spec())
        h.manager.startTimer(id, 65)
        assertTrue(h.sessions().single().timerRunning)
        advanceTimeBy(6_500)
        val s = h.sessions().single()
        assertNotNull(s.remainingSeconds)
        assertTrue(s.remainingSeconds!! <= 59)
        coVerify(atLeast = 1) { h.notifier.notify(NotificationCategory.LANCES, "Fechamento iminente", any(), any(), any(), id, 7L) }
        h.manager.stopTimer(id)
        assertFalse(h.sessions().single().timerRunning)

        // silenciar: novo cronômetro curto não deve notificar
        assertEquals(1, h.manager.setAlertsMuted(true))
        assertTrue(h.manager.alertsMuted.value)
        val id2 = h.manager.openSession(spec())
        h.manager.startTimer(id2, 30)
        advanceTimeBy(2_500)
        coVerify(exactly = 0) { h.notifier.notify(NotificationCategory.LANCES, "Fechamento iminente", any(), any(), any(), id2, 7L) }
        h.manager.stopTimer(id2)
        h.manager.closeSession(id)
        h.manager.closeSession(id2)
    }

    @Test
    fun `mudanca de piso gera auditoria MUDANCA_PISO e passa a aceitar lances ate o novo piso`() = runTest {
        val h = Harness(this, AuthSession(user, company))
        val id = h.manager.openSession(spec())
        assertTrue(h.manager.recordOurBid(id, 79_000.0) is BidResult.Rejected)

        h.manager.updateRule(id, rule.copy(floorPrice = 75_000.0))
        val s = h.sessions().single()
        assertEquals(75_000.0, s.rule.floorPrice, 1e-9)
        assertEquals(RobotMode.MANUAL, s.rule.mode)
        coVerify { h.audit.record(AuditAction.MUDANCA_PISO, AuditResult.SUCESSO, any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(h.store.events.any { it.type == BidEventType.RULE_CHANGED && it.description.contains("Piso alterado") })
        assertEquals(75_000.0, h.store.sessions[id]!!.rule.floorPrice, 1e-9)

        assertTrue(h.manager.recordOurBid(id, 79_000.0) is BidResult.Accepted)
        assertTrue(h.manager.recordOurBid(id, 74_000.0) is BidResult.Rejected)
    }

    @Test
    fun `rbac de regras - operador nao altera piso, diretoria altera piso sem operar lances`() = runTest {
        // LICITACOES opera sessões, mas não tem APROVAR_PISO.
        val operator = Harness(this, AuthSession(user.copy(role = UserRole.LICITACOES), company))
        val id = operator.manager.openSession(spec())
        val denied = runCatching { operator.manager.updateRule(id, rule.copy(floorPrice = 75_000.0)) }
        assertTrue(denied.isFailure)
        assertEquals(80_000.0, operator.sessions().single().rule.floorPrice, 1e-9)
        coVerify(exactly = 0) { operator.audit.record(AuditAction.MUDANCA_PISO, any(), any(), any(), any(), any(), any(), any(), any(), any()) }

        // DIRETORIA: enxerga/restaura e altera piso e estratégia, mas não registra lances.
        val board = Harness(this, AuthSession(user.copy(role = UserRole.DIRETORIA), company))
        board.store.sessions[id] = operator.store.sessions[id]!!
        board.manager.restoreOrSeed(7)
        val restored = board.sessions().single()
        assertEquals(id, restored.id)
        board.manager.updateRule(id, rule.copy(floorPrice = 75_000.0, strategy = BidStrategy.AGRESSIVA))
        val s = board.sessions().single()
        assertEquals(75_000.0, s.rule.floorPrice, 1e-9)
        assertEquals(BidStrategy.AGRESSIVA, s.rule.strategy)
        coVerify { board.audit.record(AuditAction.MUDANCA_PISO, AuditResult.SUCESSO, any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(runCatching { board.manager.recordOurBid(id, 90_000.0) }.isFailure)
    }

    @Test
    fun `restauracao traz sessoes abertas com cronometro parado e robo inativo, sem sessoes ficticias`() = runTest {
        val first = Harness(this, AuthSession(user, company))
        val id = first.manager.openSession(spec(tenderId = 3))
        first.manager.recordOurBid(id, 90_000.0)
        first.manager.startTimer(id, 120)
        advanceTimeBy(3_500)
        first.manager.stopTimer(id)
        val persisted = first.store.sessions[id]!!
        assertEquals(90_000.0, persisted.ourLastBid!!, 1e-9)

        // "Reabrir o app": novo motor sobre o mesmo store (cronômetro gravado como rodando é ignorado por segurança).
        val second = Harness(this, AuthSession(user, company))
        second.store.sessions[id] = persisted.copy(timerRunning = true, robotStatus = RobotStatus.ATIVO)
        second.manager.restoreOrSeed(7)
        val restored = second.sessions().single()
        assertEquals(id, restored.id)
        assertEquals(LiveStatus.EM_DISPUTA, restored.status)
        assertEquals(RobotStatus.INATIVO, restored.robotStatus)
        assertFalse(restored.timerRunning)
        assertEquals(RobotMode.MANUAL, restored.rule.mode)
        assertEquals(90_000.0, restored.ourLastBid!!, 1e-9)
        assertTrue(second.store.events.any { it.type == BidEventType.SESSION_OPENED && it.description.contains("restaurado") })

        // Chamada repetida não duplica nem cria sessões extras.
        second.manager.restoreOrSeed(7)
        assertEquals(1, second.sessions().size)
    }

    @Test
    fun `perfil sem permissao nao abre sessao e sessoes de outra empresa ficam invisiveis`() = runTest {
        val viewer = user.copy(role = UserRole.TECNICO)
        val h = Harness(this, AuthSession(viewer, company))
        val failed = runCatching { h.manager.openSession(spec()) }
        assertTrue(failed.isFailure)

        val other = Harness(this, AuthSession(user, company))
        val wrongCompany = runCatching { other.manager.openSession(spec().copy(companyId = 99)) }
        assertTrue(wrongCompany.isFailure)
        assertTrue(other.sessions().isEmpty())
    }
}
