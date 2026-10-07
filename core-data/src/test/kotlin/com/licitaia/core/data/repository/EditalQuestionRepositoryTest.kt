package com.licitaia.core.data.repository

import com.licitaia.ai.api.AIProvider
import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.api.AiProviderException
import com.licitaia.ai.api.EditalAnswer
import com.licitaia.ai.api.EditalQuestionRequest
import com.licitaia.core.data.db.EditalQuestionDao
import com.licitaia.core.data.db.EditalQuestionEntity
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.edital.EditalStore
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.domain.edital.EditalQuestionStatus
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.UserProfile
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.proposal.OfficialTenderItem
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.TenderItems
import com.licitaia.domain.repository.TenderItemsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Pergunte ao edital": gravação sempre (OK/ERRO), novas tentativas, isolamento por empresa e RBAC. */
class EditalQuestionRepositoryTest {

    /** DAO em memória com a mesma semântica das consultas Room. */
    private class MemoryDao : EditalQuestionDao {
        val rows = MutableStateFlow<List<EditalQuestionEntity>>(emptyList())
        private var nextId = 1L
        override fun observeByTender(tenderId: Long): Flow<List<EditalQuestionEntity>> =
            rows.map { list -> list.filter { it.tenderId == tenderId }.sortedWith(compareBy({ it.createdAt }, { it.id })) }
        override suspend fun getById(id: Long) = rows.value.firstOrNull { it.id == id }
        override suspend fun insert(entity: EditalQuestionEntity): Long {
            val id = nextId++
            rows.value = rows.value + entity.copy(id = id)
            return id
        }
        override suspend fun update(entity: EditalQuestionEntity) {
            rows.value = rows.value.map { if (it.id == entity.id) entity else it }
        }
        override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun deleteByTender(tenderId: Long) { rows.value = rows.value.filterNot { it.tenderId == tenderId } }
        override suspend fun deleteByCompany(companyId: Long) { rows.value = rows.value.filterNot { it.companyId == companyId } }
    }

    private val company1 = Company(1, "Empresa Um", "Um", "11222333000181", Segment.TI, "BA", "Salvador")
    private val company2 = Company(2, "Empresa Dois", "Dois", "27147548000115", Segment.TI, "BA", "Salvador")
    private val holder = SessionHolder()
    private val dao = MemoryDao()
    private val tenderDao = mockk<TenderDao>()
    private val store = mockk<EditalStore>()
    private val items = mockk<TenderItemsRepository>()
    private val gateway = mockk<AiGateway>()
    private val audit = mockk<AuditRepository>(relaxed = true)
    private val provider = mockk<AIProvider>()
    private val edital = "EDITAL DE PREGÃO ELETRÔNICO Nº 10/2026\nObjeto: link de internet.\n" +
        "8. DA HABILITAÇÃO\n8.2 Os documentos de habilitação são: certidão negativa federal, FGTS e atestado de capacidade técnica.\n"

    private fun tender(id: Long, companyId: Long, withText: Boolean = true) = Tender(
        id = id, companyId = companyId, opportunityId = "PNCP:$id", portal = Portal.PNCP, number = "$id/2026", agency = "Prefeitura",
        objectDescription = "Link de internet", modality = Modality.PREGAO_ELETRONICO, segment = Segment.TI, uf = "BA", city = "Salvador",
        estimatedValue = 100_000.0, proposalDeadline = 0, sessionAt = 0,
        editalTextPath = if (withText) "/editais/$companyId/$id.txt" else null, editalChars = if (withText) edital.length else 0,
    ).toEntity()

    private fun login(company: Company = company1, role: UserRole = UserRole.LICITACOES) =
        holder.set(AuthSession(UserProfile(id = 1, name = "Pessoa", email = "p@example.com", role = role, companyIds = listOf(1, 2)), company))

    private fun repository(scope: kotlinx.coroutines.CoroutineScope): EditalQuestionRepositoryImpl {
        coEvery { tenderDao.getById(10) } returns tender(10, 1)
        coEvery { tenderDao.getById(20) } returns tender(20, 2)
        coEvery { tenderDao.getById(11) } returns tender(11, 1, withText = false)
        coEvery { store.readText(any()) } returns edital
        coEvery { items.officialItems(any(), any()) } returns Result.success(
            TenderItems(listOf(OfficialTenderItem(1, "Link dedicado 100 Mbps", 12.0, "mês", 1_000.0)), "PNCP", 0),
        )
        coEvery { gateway.current() } returns provider
        every { provider.type } returns AiProviderType.OPENAI
        every { provider.displayName } returns "ChatGPT"
        return EditalQuestionRepositoryImpl(dao, tenderDao, store, items, gateway, audit, RepositoryAccess(holder), scope)
    }

    @Test fun `answered question is persisted with answer, sources, provider and model`() = runTest {
        val repo = repository(this)
        login()
        val request = slot<EditalQuestionRequest>()
        coEvery { provider.askEdital(capture(request)) } returns EditalAnswer(
            "São exigidas certidão federal, FGTS e atestado.\nFonte: item 8.2 — Da habilitação", "gpt-5",
        )

        val result = repo.ask(10, "  Quais documentos de habilitação?  ")

        assertTrue(result.isSuccess)
        val saved = repo.observeQuestions(10).first().single()
        assertEquals("Quais documentos de habilitação?", saved.question)
        assertEquals("São exigidas certidão federal, FGTS e atestado.", saved.answer)
        assertEquals(listOf("item 8.2 — Da habilitação"), saved.sources)
        // Provedor + cobertura da leitura (edital pequeno: a base inteira foi enviada).
        assertEquals("ChatGPT · lido: base completa", saved.provider)
        assertEquals("gpt-5", saved.model)
        assertEquals(EditalQuestionStatus.OK, saved.status)
        assertEquals(1L, saved.companyId)
        // O prompt leva a pergunta, a instrução de responder só pelo edital e o texto do edital.
        assertTrue(request.captured.prompt.contains("PERGUNTA: Quais documentos de habilitação?"))
        assertTrue(request.captured.prompt.contains("8.2 Os documentos de habilitação"))
        assertTrue(request.captured.system.contains("SOMENTE com informação escrita literalmente"))
        // Pergunta sem relação com itens não consulta os itens oficiais.
        coVerify(exactly = 0) { items.officialItems(any(), any()) }
    }

    @Test fun `item related question sends official items to the prompt`() = runTest {
        val repo = repository(this)
        login()
        val request = slot<EditalQuestionRequest>()
        coEvery { provider.askEdital(capture(request)) } returns EditalAnswer("12 meses.\nFonte: item 1", null)
        repo.ask(10, "Qual a quantidade do item 1?")
        assertTrue(request.captured.prompt.contains("ITENS OFICIAIS PUBLICADOS"))
        assertTrue(request.captured.prompt.contains("Item 1: Link dedicado 100 Mbps"))
    }

    @Test fun `ai failure is persisted as error and retry overwrites the same record`() = runTest {
        val repo = repository(this)
        login()
        coEvery { provider.askEdital(any()) } throws AiProviderException("ChatGPT: limite de uso atingido.")

        val failed = repo.ask(10, "Qual o prazo de entrega?")

        assertTrue(failed.isFailure)
        val error = repo.observeQuestions(10).first().single()
        assertEquals(EditalQuestionStatus.ERRO, error.status)
        assertEquals("ChatGPT: limite de uso atingido.", error.answer)

        coEvery { provider.askEdital(any()) } returns EditalAnswer("30 dias.\nFonte: item 5.1", "gpt-5")
        val retried = repo.retry(error.id)

        assertTrue(retried.isSuccess)
        val list = repo.observeQuestions(10).first()
        assertEquals(1, list.size)
        assertEquals(error.id, list.single().id)
        assertEquals(EditalQuestionStatus.OK, list.single().status)
        assertEquals("30 dias.", list.single().answer)
    }

    @Test fun `question without edital text is still recorded with an explanatory error`() = runTest {
        val repo = repository(this)
        login()
        val result = repo.ask(11, "Qual a forma de pagamento?")
        assertTrue(result.isFailure)
        val saved = repo.observeQuestions(11).first().single()
        assertEquals(EditalQuestionStatus.ERRO, saved.status)
        assertTrue(saved.answer.contains("texto do edital"))
        coVerify(exactly = 0) { provider.askEdital(any()) }
    }

    @Test fun `history is isolated by company`() = runTest {
        val repo = repository(this)
        login(company1)
        coEvery { provider.askEdital(any()) } returns EditalAnswer("Sim.\nFonte: item 3", null)
        repo.ask(10, "Há visita técnica obrigatória?")
        val id = dao.rows.value.single().id

        login(company2)
        assertTrue(repo.observeQuestions(10).first().isEmpty())
        // Não pergunta nem apaga na licitação/pergunta de outra empresa.
        assertTrue(repo.ask(10, "Outra pergunta?").isFailure)
        assertTrue(repo.delete(id).isFailure)
        assertTrue(repo.retry(id).isFailure)
        assertEquals(1, dao.rows.value.size)

        login(company1)
        assertEquals(1, repo.observeQuestions(10).first().size)
    }

    @Test fun `profile without analysis permission cannot ask or delete but can read`() = runTest {
        val repo = repository(this)
        login()
        coEvery { provider.askEdital(any()) } returns EditalAnswer("Sim.", null)
        repo.ask(10, "Exclusiva ME/EPP?")
        val id = dao.rows.value.single().id

        login(role = UserRole.FINANCEIRO)
        assertTrue(repo.ask(10, "Quais as penalidades?").isFailure)
        assertTrue(repo.delete(id).isFailure)
        assertEquals(1, repo.observeQuestions(10).first().size)
    }

    @Test fun `delete removes only the chosen question`() = runTest {
        val repo = repository(this)
        login()
        coEvery { provider.askEdital(any()) } returns EditalAnswer("Resposta.", null)
        repo.ask(10, "Pergunta um?")
        repo.ask(10, "Pergunta dois?")
        val first = repo.observeQuestions(10).first().first()
        assertTrue(repo.delete(first.id).isSuccess)
        val remaining = repo.observeQuestions(10).first()
        assertEquals(listOf("Pergunta dois?"), remaining.map { it.question })
        assertNull(dao.rows.value.firstOrNull { it.id == first.id })
    }
}
