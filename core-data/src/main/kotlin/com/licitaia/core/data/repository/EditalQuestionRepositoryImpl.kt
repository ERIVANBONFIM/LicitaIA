package com.licitaia.core.data.repository

import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.api.EditalQuestionRequest
import com.licitaia.core.data.db.EditalQuestionDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.di.DataScope
import com.licitaia.core.data.edital.EditalStore
import com.licitaia.core.ai.withAiCompany
import com.licitaia.domain.edital.EditalDocumentBase
import com.licitaia.domain.edital.EditalExcerptSelector
import com.licitaia.domain.edital.EditalQuestion
import com.licitaia.domain.edital.EditalQuestionPrompt
import com.licitaia.domain.edital.EditalQuestionStatus
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.Tender
import com.licitaia.domain.proposal.OfficialTenderItem
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.EditalQuestionRepository
import com.licitaia.domain.repository.TenderItemsRepository
import com.licitaia.domain.security.Permission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Pergunte ao edital". A pergunta é gravada ANTES de chamar a IA (status PENDENTE) e atualizada com a resposta (OK) ou
 * com o motivo da falha (ERRO): nada se perde, nem quando o provedor falha. A chamada roda no escopo da camada de dados,
 * então sair da tela não interrompe a resposta. O texto do edital vai inteiro quando cabe; senão, o recorte de
 * [EditalExcerptSelector] (cabeçalho + trechos com os termos da pergunta). A empresa demo usa o provedor heurístico.
 */
@Singleton
class EditalQuestionRepositoryImpl @Inject constructor(
    private val questionDao: EditalQuestionDao,
    private val tenderDao: TenderDao,
    private val editalStore: EditalStore,
    private val items: TenderItemsRepository,
    private val gateway: AiGateway,
    private val audit: AuditRepository,
    private val access: RepositoryAccess,
    @DataScope private val scope: CoroutineScope,
) : EditalQuestionRepository {

    private val answering = MutableStateFlow<Set<Long>>(emptySet())

    override fun observeQuestions(tenderId: Long): Flow<List<EditalQuestion>> =
        questionDao.observeByTender(tenderId).map { list -> list.filter { access.owns(it.companyId) }.map { it.toDomain() } }

    override fun observeAnswering(): Flow<Set<Long>> = answering.asStateFlow()

    override suspend fun ask(tenderId: Long, question: String): Result<EditalQuestion> = withContext(Dispatchers.IO) {
        val text = question.trim().replace(Regex("\\s+"), " ")
        val tender = try {
            require(text.isNotEmpty()) { "Digite a pergunta." }
            require(text.length <= EditalQuestionPrompt.MAX_QUESTION_CHARS) { "Pergunta longa demais (máximo de ${EditalQuestionPrompt.MAX_QUESTION_CHARS} caracteres)." }
            val entity = tenderDao.getById(tenderId) ?: error("Licitação não encontrada.")
            access.requireCompany(entity.companyId, Permission.ANALISAR)
            entity.toDomain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val pending = EditalQuestion(
            companyId = tender.companyId, tenderId = tender.id, question = text,
            createdAt = System.currentTimeMillis(), status = EditalQuestionStatus.PENDENTE,
        )
        val id = questionDao.insert(pending.toEntity())
        answerInBackground(pending.copy(id = id), tender)
    }

    override suspend fun retry(questionId: Long): Result<EditalQuestion> = withContext(Dispatchers.IO) {
        val (question, tender) = try {
            val entity = questionDao.getById(questionId) ?: error("Pergunta não encontrada.")
            access.requireCompany(entity.companyId, Permission.ANALISAR)
            check(questionId !in answering.value) { "Esta pergunta já está sendo respondida." }
            val tenderEntity = tenderDao.getById(entity.tenderId) ?: error("Licitação não encontrada.")
            check(tenderEntity.companyId == entity.companyId) { "Pergunta de outra empresa." }
            entity.toDomain() to tenderEntity.toDomain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val pending = question.copy(answer = "", provider = "", model = null, sources = emptyList(), status = EditalQuestionStatus.PENDENTE)
        questionDao.update(pending.toEntity())
        answerInBackground(pending, tender)
    }

    override suspend fun delete(questionId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = questionDao.getById(questionId) ?: return@withContext Result.success(Unit)
            access.requireCompany(entity.companyId, Permission.ANALISAR)
            questionDao.delete(questionId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Responde no escopo da camada de dados (sobrevive à saída da tela); quem chama apenas aguarda. */
    private suspend fun answerInBackground(question: EditalQuestion, tender: Tender): Result<EditalQuestion> {
        answering.update { it + question.id }
        val job = scope.async(Dispatchers.IO) {
            try {
                answer(question, tender)
            } finally {
                answering.update { it - question.id }
            }
        }
        return job.await()
    }

    private suspend fun answer(question: EditalQuestion, tender: Tender): Result<EditalQuestion> =
        withAiCompany(tender.companyId) { answerInScope(question, tender) }

    private suspend fun answerInScope(question: EditalQuestion, tender: Tender): Result<EditalQuestion> {
        val provider = runCatching { gateway.current() }.getOrNull()
        val providerName = provider?.let { if (it.type == AiProviderType.MOCK) HEURISTIC_PROVIDER_NAME else it.displayName }.orEmpty()
        return try {
            checkNotNull(provider) { "Nenhum provedor de IA disponível." }
            val editalText = if (tender.hasEditalText) editalStore.readText(tender.editalTextPath) else null
            check(!editalText.isNullOrBlank()) {
                "Esta licitação ainda não tem o texto do edital. Baixe o edital oficial ou importe o PDF e pergunte de novo."
            }
            // Base inteira (todos os documentos, Edital e TR primeiro) quando cabe no provedor; senão recorte com as seções
            // inteiras cujo título casa com a pergunta + trechos de todos os documentos.
            val excerpt = EditalExcerptSelector.select(editalText, question.question, fullBaseChars)
            val official = if (EditalQuestionPrompt.wantsItems(question.question)) officialItems(tender.id) else emptyList()
            val request = EditalQuestionRequest(
                system = EditalQuestionPrompt.SYSTEM,
                prompt = EditalQuestionPrompt.build(
                    tender, question.question, excerpt, official,
                    documents = EditalDocumentBase.documentsIn(editalText).map { it.displayName },
                ),
                question = question.question,
                editalExcerpt = excerpt.text,
            )
            val reply = provider.askEdital(request)
            val (body, sources) = EditalQuestionPrompt.splitSources(reply.text)
            val answered = question.copy(
                // Metadado de cobertura junto do provedor (sem coluna nova): "lido: base completa" / "trechos de N documentos".
                answer = body.ifBlank { reply.text.trim() }, provider = "$providerName · ${excerpt.coverageLabel}", model = reply.model,
                sources = sources, status = EditalQuestionStatus.OK,
            )
            questionDao.update(answered.toEntity())
            runCatching {
                audit.record(
                    AuditAction.ANALISE, origin = if (provider.type == AiProviderType.MOCK) AuditOrigin.SISTEMA else AuditOrigin.IA,
                    portal = tender.portal, tenderNumber = tender.number,
                    details = "Pergunta ao edital respondida com $providerName" +
                        (if (answered.unsourced) " (sem fonte citada — marcada para conferência)" else "") +
                        (if (excerpt.complete) " (base completa: ${excerpt.originalChars} caracteres)"
                        else " (recorte: ${excerpt.text.length} de ${excerpt.originalChars} caracteres, ${excerpt.documents} documento(s), ${excerpt.fullSections} seção(ões) inteira(s))") +
                        (if (official.isNotEmpty()) "; ${official.size} item(ns) oficiais" else ""),
                )
            }
            Result.success(answered)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { markError(question, providerName, "Resposta interrompida. Toque em \"Tentar de novo\".") }
            throw e
        } catch (e: Exception) {
            val message = e.message?.takeIf(String::isNotBlank) ?: "O provedor de IA não respondeu."
            markError(question, providerName, message)
            runCatching {
                audit.record(
                    AuditAction.ANALISE, result = AuditResult.FALHA, origin = AuditOrigin.IA, portal = tender.portal,
                    tenderNumber = tender.number, reason = message, details = "Falha ao responder pergunta ao edital com ${providerName.ifBlank { "IA" }}",
                )
            }
            Result.failure(IllegalStateException(message, e))
        }
    }

    private suspend fun markError(question: EditalQuestion, providerName: String, message: String) {
        val failed = question.copy(answer = message, provider = providerName, model = null, sources = emptyList(), status = EditalQuestionStatus.ERRO)
        runCatching { questionDao.update(failed.toEntity()) }
    }

    /** Itens oficiais (cache da aba "Itens"); falha de consulta não impede a resposta. */
    private suspend fun officialItems(tenderId: Long): List<OfficialTenderItem> =
        items.officialItems(tenderId).getOrNull()?.items.orEmpty()

    /** Até quantos caracteres a base inteira vai para a IA (configurável aqui; padrão [EditalExcerptSelector.FULL_BASE_MAX_CHARS]). */
    internal var fullBaseChars: Int = EditalExcerptSelector.FULL_BASE_MAX_CHARS

    private companion object {
        const val HEURISTIC_PROVIDER_NAME = "Heurística local (sem IA)"
    }
}
