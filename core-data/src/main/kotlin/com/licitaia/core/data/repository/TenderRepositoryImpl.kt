package com.licitaia.core.data.repository

import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.api.TenderAnalysisRequest
import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.DocumentDao
import com.licitaia.core.data.db.NotificationDao
import com.licitaia.core.data.db.OpportunityDao
import com.licitaia.core.data.db.ProposalDao
import com.licitaia.core.data.db.TenderAnalysisDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.db.toTender
import com.licitaia.core.data.di.DataScope
import com.licitaia.core.data.edital.EditalOcrSupport
import com.licitaia.core.data.edital.EditalStore
import com.licitaia.core.data.edital.EditalTextPreparer
import com.licitaia.core.data.edital.PdfExtractionException
import com.licitaia.core.data.edital.PdfOcrEngine
import com.licitaia.core.data.edital.PdfTextExtractor
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AppNotification
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.EditalImportProgress
import com.licitaia.domain.model.EditalImportResult
import com.licitaia.domain.model.EditalSource
import com.licitaia.domain.model.ManualTenderDraft
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.security.Permission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TenderRepositoryImpl @Inject constructor(
    private val tenderDao: TenderDao,
    private val analysisDao: TenderAnalysisDao,
    private val proposalDao: ProposalDao,
    private val opportunityDao: OpportunityDao,
    private val companyDao: CompanyDao,
    private val documentDao: DocumentDao,
    private val notificationDao: NotificationDao,
    private val registry: ConnectorRegistry,
    private val gateway: AiGateway,
    private val audit: AuditRepository,
    private val access: RepositoryAccess,
    private val editalStore: EditalStore,
    private val pdfExtractor: PdfTextExtractor,
    private val pdfOcrEngine: PdfOcrEngine,
    @DataScope private val scope: CoroutineScope,
) : TenderRepository {

    private val interestMutex = Mutex()

    /** Importações/OCR em andamento por licitação (sobrevivem à saída da tela). */
    private val importJobs = HashMap<Long, Deferred<Result<EditalImportResult>>>()
    private val importProgress = MutableStateFlow<Map<Long, EditalImportProgress>>(emptyMap())

    init {
        // Análises interrompidas por morte do processo são retomadas na próxima abertura.
        scope.launch {
            runCatching {
                tenderDao.getByStatus(TenderStatus.EM_ANALISE)
                    .filter { analysisDao.get(it.id) == null }
                    .forEach { runAnalysis(it.id, background = true) }
            }
        }
    }

    override fun observeTenders(companyId: Long): Flow<List<Tender>> =
        tenderDao.observeByCompany(companyId).map { list -> if (access.owns(companyId)) list.map { it.toDomain() } else emptyList() }

    override fun observeTender(id: Long): Flow<Tender?> =
        tenderDao.observeById(id).map { it?.takeIf { e -> access.owns(e.companyId) }?.toDomain() }

    override fun observeAnalysis(tenderId: Long): Flow<TenderAnalysis?> =
        analysisDao.observe(tenderId).map { it?.toDomain() }

    override fun observeEditalText(tenderId: Long): Flow<String?> =
        tenderDao.observeById(tenderId)
            .map { it?.takeIf { e -> access.owns(e.companyId) }?.editalTextPath }
            .distinctUntilChanged()
            .mapLatest { path -> editalStore.readText(path) }

    override suspend fun markInterest(companyId: Long, opportunity: Opportunity): Long = withContext(Dispatchers.IO) {
        access.requireCompany(companyId)
        val id = interestMutex.withLock {
            tenderDao.getByOpportunity(companyId, opportunity.id)?.let { return@withLock it.id }
            val now = System.currentTimeMillis()
            opportunityDao.upsertAll(listOf(opportunity.toEntity(now)))
            val newId = tenderDao.upsert(opportunity.toTender(companyId, now, editalRegistered = opportunity.editalUrl != null).toEntity())
            audit.record(
                AuditAction.ANALISE, portal = opportunity.portal, tenderNumber = opportunity.number,
                details = "Interesse registrado; edital ${if (opportunity.editalUrl != null) "registrado" else "pendente"}; análise iniciada",
            )
            scope.launch { runAnalysis(newId, background = true) }
            newId
        }
        id
    }

    override suspend fun removeInterest(tenderId: Long) {
        withContext(Dispatchers.IO) {
            val tender = tenderDao.getById(tenderId) ?: return@withContext
            access.requireCompany(tender.companyId)
            proposalDao.deleteByTender(tenderId)
            analysisDao.delete(tenderId)
            tenderDao.delete(tenderId)
            runCatching { editalStore.delete(tender.companyId, tenderId) }
            audit.record(
                AuditAction.CADASTRO, portal = tender.portal, tenderNumber = tender.number,
                previousValue = tender.status.label, details = "Licitação removida das licitações de interesse",
            )
        }
    }

    override suspend fun findByOpportunity(companyId: Long, opportunityId: String): Tender? =
        tenderDao.getByOpportunity(companyId, opportunityId)?.takeIf { access.owns(it.companyId) }?.toDomain()

    override suspend fun analyze(tenderId: Long): Result<TenderAnalysis> = runAnalysis(tenderId, background = false)

    override suspend fun updateStatus(tenderId: Long, status: TenderStatus) {
        withContext(Dispatchers.IO) {
            val tender = tenderDao.getById(tenderId) ?: return@withContext
            access.requireCompany(tender.companyId)
            if (tender.status == status) return@withContext
            tenderDao.updateStatus(tenderId, status, System.currentTimeMillis())
            audit.record(
                AuditAction.CONFIGURACAO, portal = tender.portal, tenderNumber = tender.number,
                previousValue = tender.status.label, newValue = status.label, details = "Status da licitação alterado",
            )
        }
    }

    override suspend fun toggleChecklistItem(tenderId: Long, index: Int) {
        withContext(Dispatchers.IO) {
            val tender = tenderDao.getById(tenderId) ?: return@withContext
            access.requireCompany(tender.companyId)
            val entity = analysisDao.get(tenderId) ?: return@withContext
            val analysis = entity.toDomain()
            val item = analysis.checklist.getOrNull(index) ?: return@withContext
            val updated = analysis.checklist.toMutableList().also { it[index] = item.copy(done = !item.done) }
            analysisDao.upsert(analysis.copy(checklist = updated).toEntity())
        }
    }

    // ------------------------------------------------------------------ cadastro manual / edital

    override suspend fun createManual(companyId: Long, draft: ManualTenderDraft): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            access.requireCompany(companyId, Permission.ANALISAR)
            val errors = draft.validate()
            require(errors.isEmpty()) { errors.first() }
            val opportunityId = draft.opportunityId()
            interestMutex.withLock {
                tenderDao.getByOpportunity(companyId, opportunityId)?.let {
                    error("Esta licitação (${draft.portal.shortName} ${draft.number.trim()}) já está cadastrada nesta empresa.")
                }
                val now = System.currentTimeMillis()
                val tender = Tender(
                    companyId = companyId,
                    opportunityId = opportunityId,
                    portal = draft.portal,
                    number = draft.number.trim(),
                    agency = draft.agency.trim(),
                    objectDescription = draft.objectDescription.trim(),
                    modality = draft.modality,
                    segment = draft.segment,
                    uf = draft.uf.trim().uppercase(),
                    city = draft.city.trim(),
                    estimatedValue = draft.estimatedValue,
                    proposalDeadline = draft.proposalDeadline,
                    sessionAt = draft.sessionAt,
                    status = TenderStatus.INTERESSE,
                    editalRegistered = !draft.editalUrl.isNullOrBlank(),
                    createdAt = now,
                    updatedAt = now,
                )
                val id = tenderDao.upsert(tender.toEntity())
                audit.record(
                    AuditAction.CADASTRO, portal = draft.portal, tenderNumber = tender.number,
                    newValue = tender.agency,
                    details = "Licitação cadastrada manualmente" + (draft.editalUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { "; edital em $it" } ?: ""),
                )
                id
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            runCatching {
                audit.record(
                    AuditAction.CADASTRO, result = AuditResult.FALHA, portal = draft.portal, tenderNumber = draft.number.trim(),
                    reason = error.message, details = "Falha no cadastro manual da licitação",
                )
            }
        }
    }

    override fun observeEditalImportProgress(tenderId: Long): Flow<EditalImportProgress?> =
        importProgress.map { it[tenderId] }.distinctUntilChanged()

    /**
     * A importação roda no [scope] da camada de dados (não no da tela): se o usuário sair da tela no
     * meio de um OCR longo, o trabalho continua, o banco é atualizado e o progresso segue observável.
     * Uma segunda chamada para a mesma licitação enquanto há importação em andamento reaproveita o job.
     */
    override suspend fun attachEdital(tenderId: Long, source: EditalSource): Result<EditalImportResult> {
        val job = synchronized(importJobs) {
            importJobs[tenderId] ?: scope.async(Dispatchers.IO) {
                try {
                    doAttachEdital(tenderId, source)
                } finally {
                    synchronized(importJobs) { importJobs.remove(tenderId) }
                    importProgress.update { it - tenderId }
                }
            }.also { importJobs[tenderId] = it }
        }
        return job.await()
    }

    private fun publishProgress(tenderId: Long, stage: EditalImportProgress.Stage, page: Int = 0, total: Int = 0) {
        importProgress.update { it + (tenderId to EditalImportProgress(tenderId, stage, page, total)) }
    }

    private suspend fun doAttachEdital(tenderId: Long, source: EditalSource): Result<EditalImportResult> {
        val entity = tenderDao.getById(tenderId)
            ?: return Result.failure(IllegalArgumentException("Licitação não encontrada."))
        val tender = entity.toDomain()
        return try {
            access.requireCompany(tender.companyId, Permission.ANALISAR)
            val now = System.currentTimeMillis()
            val result = when (source) {
                is EditalSource.Pdf -> {
                    publishProgress(tenderId, EditalImportProgress.Stage.COPIANDO)
                    val pdf = editalStore.importPdf(tender.companyId, tenderId, source.uri)
                    publishProgress(tenderId, EditalImportProgress.Stage.EXTRAINDO)
                    val extraction = pdfExtractor.extract(pdf)
                    val extracted = EditalTextPreparer.normalize(extraction.text)
                    if (!EditalOcrSupport.needsOcr(extraction.scanned, EditalOcrSupport.meaningfulChars(extracted))) {
                        val textPath = editalStore.writeText(tender.companyId, tenderId, extracted).absolutePath
                        tenderDao.updateEdital(
                            id = tenderId, pdfPath = pdf.absolutePath, textPath = textPath, chars = extracted.length,
                            pages = extraction.totalPages, scanned = false, registered = true, now = now,
                        )
                        audit.record(
                            AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
                            newValue = "${extraction.totalPages} página(s) · ${extracted.length} caractere(s)",
                            details = "PDF do edital importado e texto extraído" + if (extraction.truncated) " (lidas ${extraction.pagesRead} de ${extraction.totalPages} páginas)" else "",
                        )
                        EditalImportResult(chars = extracted.length, pages = extraction.totalPages, scanned = false, storedPath = pdf.absolutePath)
                    } else {
                        // Sem camada de texto: guarda o PDF como escaneado e tenta o OCR local em seguida.
                        editalStore.textFile(tender.companyId, tenderId).delete()
                        tenderDao.updateEdital(
                            id = tenderId, pdfPath = pdf.absolutePath, textPath = null, chars = 0,
                            pages = extraction.totalPages, scanned = true, registered = true, now = now,
                        )
                        audit.record(
                            AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
                            result = AuditResult.PENDENTE, newValue = "${extraction.totalPages} página(s) · 0 caractere(s)",
                            details = "PDF do edital importado sem camada de texto (escaneado); iniciando OCR local",
                        )
                        runOcr(tender.copy(editalPdfPath = pdf.absolutePath, editalPages = extraction.totalPages), automatic = true)
                    }
                }
                is EditalSource.Ocr -> {
                    val path = tender.editalPdfPath?.takeIf { File(it).exists() }
                        ?: throw IllegalStateException("Importe o PDF do edital antes de reconhecer o texto.")
                    runOcr(tender.copy(editalPdfPath = path), automatic = false)
                }
                is EditalSource.Text -> {
                    val text = EditalTextPreparer.normalize(source.text)
                    require(text.length >= MIN_PASTED_CHARS) { "Cole um trecho maior do edital (mínimo de $MIN_PASTED_CHARS caracteres)." }
                    require(text.length <= PdfTextExtractor.HARD_CHAR_LIMIT) { "O texto colado é grande demais." }
                    val file = editalStore.writeText(tender.companyId, tenderId, text)
                    tenderDao.updateEdital(
                        id = tenderId, pdfPath = tender.editalPdfPath, textPath = file.absolutePath, chars = text.length,
                        pages = tender.editalPages, scanned = false, registered = true, now = now,
                    )
                    audit.record(
                        AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
                        newValue = "${text.length} caractere(s)", details = "Texto do edital colado manualmente",
                    )
                    EditalImportResult(chars = text.length, pages = tender.editalPages, scanned = false, storedPath = file.absolutePath)
                }
            }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            runCatching {
                audit.record(
                    AuditAction.GERACAO_DOCUMENTO, result = AuditResult.FALHA, portal = tender.portal, tenderNumber = tender.number,
                    reason = error.message, details = "Falha ao anexar o edital",
                )
            }
            val surfaced = when (error) {
                is PdfExtractionException, is IOException, is IllegalArgumentException, is IllegalStateException -> error
                else -> IllegalStateException(error.message ?: "Não foi possível importar o edital.", error)
            }
            Result.failure(surfaced)
        }
    }

    /**
     * OCR local do PDF já armazenado em [tender.editalPdfPath]. Publica o progresso página a página,
     * grava o `.txt` como no fluxo normal e marca `scanned = true` (texto reconhecido, não extraído).
     * @param automatic true quando disparado pela importação do PDF (texto insuficiente).
     */
    private suspend fun runOcr(tender: Tender, automatic: Boolean): EditalImportResult {
        val tenderId = tender.id
        val pdf = File(tender.editalPdfPath ?: error("PDF do edital ausente."))
        publishProgress(tenderId, EditalImportProgress.Stage.OCR, 0, tender.editalPages ?: 0)
        val ocr = try {
            pdfOcrEngine.recognize(pdf) { done, total -> publishProgress(tenderId, EditalImportProgress.Stage.OCR, done, total) }
        } catch (e: PdfExtractionException) {
            throw PdfExtractionException(
                (if (automatic) "O PDF foi importado, mas o reconhecimento de texto falhou: " else "O reconhecimento de texto falhou: ") +
                    e.message + " Você ainda pode colar o texto do edital.",
                e,
            )
        }
        val text = EditalTextPreparer.normalize(ocr.text)
        val now = System.currentTimeMillis()
        if (!ocr.usable) {
            editalStore.textFile(tender.companyId, tenderId).delete()
            tenderDao.updateEdital(
                id = tenderId, pdfPath = pdf.absolutePath, textPath = null, chars = 0,
                pages = ocr.totalPages, scanned = true, registered = true, now = now,
            )
            audit.record(
                AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number, result = AuditResult.PENDENTE,
                newValue = "${ocr.totalPages} página(s) · ${ocr.pagesWithText} com texto reconhecido",
                details = "OCR local concluído sem texto suficiente (imagem ilegível ou idioma não suportado); texto deve ser colado",
            )
            throw PdfExtractionException(
                "O OCR não reconheceu texto suficiente neste PDF (${ocr.pagesWithText} de ${ocr.pagesProcessed} página(s) com texto). " +
                    "Verifique a qualidade da digitalização ou cole o texto do edital.",
            )
        }
        val file = editalStore.writeText(tender.companyId, tenderId, text)
        tenderDao.updateEdital(
            id = tenderId, pdfPath = pdf.absolutePath, textPath = file.absolutePath, chars = text.length,
            pages = ocr.totalPages, scanned = true, registered = true, now = now,
        )
        audit.record(
            AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number, origin = AuditOrigin.SISTEMA,
            newValue = "${ocr.totalPages} página(s) · ${text.length} caractere(s) via OCR",
            details = buildString {
                append(if (automatic) "PDF escaneado: texto reconhecido por OCR local (ML Kit, sem rede)" else "OCR local executado manualmente (ML Kit, sem rede)")
                append("; ${ocr.pagesWithText} de ${ocr.pagesProcessed} página(s) com texto")
                if (ocr.truncated) append("; reconhecidas ${ocr.pagesProcessed} de ${ocr.totalPages} páginas (limite)")
            },
        )
        return EditalImportResult(chars = text.length, pages = ocr.totalPages, scanned = true, storedPath = pdf.absolutePath, ocr = true)
    }

    // ------------------------------------------------------------------ análise

    /**
     * Executa a análise com o provedor ativo usando o texto real do edital quando existir.
     * Sem fallback silencioso: se o provedor real falhar, a análise falha (auditada como FALHA) e a
     * licitação volta ao status anterior. Com o provedor de demonstração, a análise é heurística e
     * assim rotulada (providerName + aiFields vazio).
     */
    private suspend fun runAnalysis(tenderId: Long, background: Boolean): Result<TenderAnalysis> = withContext(Dispatchers.IO) {
        val entity = tenderDao.getById(tenderId)
            ?: return@withContext Result.failure(IllegalArgumentException("Licitação não encontrada."))
        val tender = entity.toDomain()
        if (!background) {
            runCatching { access.requireCompany(tender.companyId, Permission.ANALISAR) }
                .onFailure { return@withContext Result.failure(it) }
        }
        val company = companyDao.getById(tender.companyId)?.toDomain()
            ?: return@withContext Result.failure(IllegalStateException("Empresa da licitação não encontrada."))
        val previousStatus = tender.status
        val earlyStage = previousStatus == TenderStatus.INTERESSE || previousStatus == TenderStatus.EM_ANALISE ||
            previousStatus == TenderStatus.ANALISADA
        val now = System.currentTimeMillis()
        if (earlyStage && previousStatus != TenderStatus.EM_ANALISE) tenderDao.updateStatus(tenderId, TenderStatus.EM_ANALISE, now)

        val editalText = resolveEditalText(tender)
        val documents = documentDao.getByCompany(tender.companyId).map { it.toDomain() }
        val request = TenderAnalysisRequest(tender, company, documents, editalText, now)

        val provider = gateway.current()
        val heuristic = provider.type == AiProviderType.MOCK
        val outcome = try {
            Result.success(provider.analyzeTender(request))
        } catch (e: CancellationException) {
            if (earlyStage) runCatching { tenderDao.updateStatus(tenderId, previousStatus, System.currentTimeMillis()) }
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val analysis = outcome.getOrElse { error ->
            if (earlyStage) tenderDao.updateStatus(tenderId, previousStatus, System.currentTimeMillis())
            audit.record(
                AuditAction.ANALISE, result = AuditResult.FALHA, origin = AuditOrigin.IA, portal = tender.portal,
                tenderNumber = tender.number, reason = error.message,
                details = "Falha na análise com ${provider.displayName}; nenhuma análise heurística foi gerada no lugar",
            )
            if (background) {
                runCatching {
                    notificationDao.insert(
                        AppNotification(
                            companyId = tender.companyId, category = NotificationCategory.GERAL,
                            title = "Análise falhou — ${tender.portal.shortName} ${tender.number}",
                            body = error.message?.takeIf { it.isNotBlank() } ?: "O provedor de IA não respondeu. Abra a licitação para tentar novamente.",
                            createdAt = System.currentTimeMillis(), route = "tender/$tenderId/analysis",
                        ).toEntity(),
                    )
                }
            }
            return@withContext Result.failure(error)
        }

        val saved = analysis.copy(
            tenderId = tenderId,
            providerName = if (heuristic) HEURISTIC_PROVIDER_NAME else analysis.providerName,
            aiFields = if (heuristic) emptySet() else analysis.aiFields,
        )
        analysisDao.upsert(saved.toEntity())
        if (earlyStage) tenderDao.updateStatus(tenderId, TenderStatus.ANALISADA, System.currentTimeMillis())
        audit.record(
            AuditAction.ANALISE, origin = if (heuristic) AuditOrigin.SISTEMA else AuditOrigin.IA,
            portal = tender.portal, tenderNumber = tender.number,
            newValue = "${saved.recommendation.label} (score ${saved.fit.overall})",
            details = buildString {
                append(if (heuristic) "Análise heurística local (sem IA)" else "Análise concluída com ${saved.providerName}")
                append(if (editalText != null) "; texto do edital: ${editalText.length} caractere(s)" else "; sem texto do edital (apenas metadados)")
                if (!heuristic && saved.aiFields.isNotEmpty()) append("; campos da IA: ${saved.aiFields.sorted().joinToString(",")}")
            },
        )
        if (background) {
            runCatching {
                notificationDao.insert(
                    AppNotification(
                        companyId = tender.companyId, category = NotificationCategory.GERAL,
                        title = (if (heuristic) "Análise heurística — " else "Análise concluída — ") + "${tender.portal.shortName} ${tender.number}",
                        body = "${saved.recommendation.label} · score ${saved.fit.overall}/100 · ${tender.agency}",
                        createdAt = System.currentTimeMillis(), route = "tender/$tenderId/worth",
                    ).toEntity(),
                )
            }
        }
        Result.success(saved)
    }

    /**
     * Texto real do edital para a IA: 1) texto importado/colado pelo usuário; 2) texto público do
     * conector do portal, somente quando o conector NÃO é de demonstração. Nunca usa texto fictício.
     */
    private suspend fun resolveEditalText(tender: Tender): String? {
        val stored = if (tender.hasEditalText) editalStore.readText(tender.editalTextPath) else null
        val raw = stored ?: runCatching {
            val connector = registry.get(tender.portal)
            if (connector.capabilities.isMock || tender.isManual) null else connector.getTenderDetails(tender.opportunityId)?.editalText
        }.getOrNull()
        return raw?.takeIf { it.isNotBlank() }?.let { EditalTextPreparer.prepare(it, MAX_PROMPT_CHARS) }
    }

    private companion object {
        const val HEURISTIC_PROVIDER_NAME = "Heurística local (sem IA)"
        const val MAX_PROMPT_CHARS = 60_000
        const val MIN_PASTED_CHARS = 200
    }
}
