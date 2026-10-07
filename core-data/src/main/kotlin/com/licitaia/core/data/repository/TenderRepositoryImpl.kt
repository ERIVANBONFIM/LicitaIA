package com.licitaia.core.data.repository

import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.api.TenderAnalysisRequest
import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.OfficialDocument
import com.licitaia.connector.api.OfficialDocumentSource
import com.licitaia.core.ai.withAiCompany
import com.licitaia.domain.edital.EditalDocKind
import com.licitaia.domain.edital.EditalDocumentBase
import com.licitaia.domain.edital.EditalSourceDocument
import kotlinx.coroutines.delay
import com.licitaia.core.data.edital.EditalDownloadException
import com.licitaia.core.data.edital.EditalDownloader
import com.licitaia.domain.model.PncpControlNumbers
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.pncpControlNumber
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
    private val questionDao: com.licitaia.core.data.db.EditalQuestionDao,
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
    private val editalDownloader: EditalDownloader,
    @DataScope private val scope: CoroutineScope,
) : TenderRepository {

    private val interestMutex = Mutex()

    /** Motivo da última falha ao baixar o edital oficial, por licitação (só em memória). */
    private val fetchErrors = MutableStateFlow<Map<Long, String>>(emptyMap())

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
            tenderDao.getByOpportunity(companyId, opportunity.id)?.let { existing ->
                // Licitação de antes da v14: recebe a UASG agora.
                com.licitaia.domain.model.UasgCode.of(opportunity)?.let { u -> runCatching { tenderDao.fillUasg(existing.id, u) } }
                return@withLock existing.id
            }
            val now = System.currentTimeMillis()
            opportunityDao.upsertAll(listOf(opportunity.toEntity(now)))
            val newId = tenderDao.upsert(opportunity.toTender(companyId, now, editalRegistered = opportunity.editalUrl != null).toEntity())
            val pncpControl = PncpControlNumbers.fromOpportunityId(opportunity.id)
            audit.record(
                AuditAction.ANALISE, portal = opportunity.portal, tenderNumber = opportunity.number,
                details = "Interesse registrado; edital ${if (opportunity.editalUrl != null) "registrado" else "pendente"}; " +
                    if (pncpControl != null) "download do edital oficial (PNCP $pncpControl) e análise iniciados" else "análise iniciada",
            )
            scope.launch {
                // Com número de controle PNCP: baixa o edital oficial ANTES da análise para a IA receber o texto real.
                // Falha no download (sem rede, sem PDF) não impede o interesse nem a análise (metadados); o card mostra o motivo.
                if (pncpControl != null) {
                    try {
                        fetchOfficialEdital(newId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // já registrado em fetchErrors/auditoria
                    }
                }
                runAnalysis(newId, background = true)
            }
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
            // A FK de edital_questions já apaga em cascata; explícito para não depender do PRAGMA foreign_keys.
            questionDao.deleteByTender(tenderId)
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
    override suspend fun attachEdital(tenderId: Long, source: EditalSource): Result<EditalImportResult> =
        runImportJob(tenderId) { doAttachEdital(tenderId, source) }

    override suspend fun fetchOfficialEdital(tenderId: Long): Result<EditalImportResult> =
        runImportJob(tenderId) { doFetchOfficialEdital(tenderId) }.also { result ->
            fetchErrors.update { errors ->
                result.exceptionOrNull()
                    ?.let { errors + (tenderId to (it.message?.takeIf(String::isNotBlank) ?: "Falha ao baixar o edital oficial.")) }
                    ?: (errors - tenderId)
            }
        }

    override fun observeOfficialEditalError(tenderId: Long): Flow<String?> =
        fetchErrors.map { it[tenderId] }.distinctUntilChanged()

    private suspend fun runImportJob(tenderId: Long, block: suspend () -> Result<EditalImportResult>): Result<EditalImportResult> {
        val job = synchronized(importJobs) {
            importJobs[tenderId] ?: scope.async(Dispatchers.IO) {
                try {
                    block().also { r -> if (r.isSuccess) fetchErrors.update { it - tenderId } }
                } finally {
                    synchronized(importJobs) { importJobs.remove(tenderId) }
                    importProgress.update { it - tenderId }
                }
            }.also { importJobs[tenderId] = it }
        }
        return job.await()
    }

    /**
     * Lista TODOS os arquivos da contratação no PNCP e monta a base de documentos (edital, TR, anexos, ETP...) usada pelo
     * "Pergunte ao edital" e pela análise — ver [buildDocumentBase].
     */
    private suspend fun doFetchOfficialEdital(tenderId: Long): Result<EditalImportResult> {
        val entity = tenderDao.getById(tenderId)
            ?: return Result.failure(IllegalArgumentException("Licitação não encontrada."))
        val tender = entity.toDomain()
        val documents = try {
            access.requireCompany(tender.companyId, Permission.ANALISAR)
            val control = tender.pncpControlNumber
                ?: throw IllegalStateException("Esta licitação não tem número de controle PNCP: importe o PDF do edital manualmente.")
            publishProgress(tenderId, EditalImportProgress.Stage.BAIXANDO)
            val source = (registry.get(Portal.PNCP) as? OfficialDocumentSource)
                ?: registry.all().firstNotNullOfOrNull { it as? OfficialDocumentSource }
                ?: throw IllegalStateException("A consulta de documentos do PNCP não está disponível.")
            val listed = try {
                source.officialEditalDocuments(control)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw EditalDownloadException(e.message?.takeIf(String::isNotBlank) ?: "Falha ao consultar os arquivos da contratação no PNCP.", e)
            }
            if (listed.isEmpty()) {
                throw IllegalStateException("O PNCP não tem arquivos publicados para esta contratação (edital, termo de referência ou anexos).")
            }
            listed
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            runCatching {
                audit.record(
                    AuditAction.GERACAO_DOCUMENTO, result = AuditResult.FALHA, portal = tender.portal, tenderNumber = tender.number,
                    reason = error.message, details = "Falha ao localizar o edital oficial no PNCP",
                )
            }
            return Result.failure(error)
        }
        return buildDocumentBase(tender, documents)
    }

    /**
     * Baixa os documentos (principal primeiro, guardado como o PDF do edital; os demais em arquivo temporário), extrai o
     * texto página a página (OCR local quando escaneado, com limite de páginas) e grava UM texto com os marcadores
     * `=== DOCUMENTO: <tipo — título> (página N) ===` na ordem Edital → TR → Anexos → Minuta → ETP → outros.
     * Limites: [EditalDocumentBase.MAX_DOCUMENTS] documentos, [MAX_BASE_BYTES] baixados, [EditalDocumentBase.MAX_PAGES_PER_DOC]
     * páginas por documento, [MAX_OCR_PAGES_TOTAL] páginas de OCR; [DOC_SPACING_MS] entre downloads (não sobrecarrega o PNCP).
     * Falha num documento não derruba os demais: ele só fica de fora (citado na auditoria).
     */
    private suspend fun buildDocumentBase(tender: Tender, documents: List<OfficialDocument>): Result<EditalImportResult> {
        val tenderId = tender.id
        return try {
            access.requireCompany(tender.companyId, Permission.ANALISAR)
            val mainPdf = editalStore.pdfFile(tender.companyId, tenderId)
            val temp = File(editalStore.directory(tender.companyId), "$tenderId.doc.pdf")
            val sources = mutableListOf<EditalSourceDocument>()
            val skipped = mutableListOf<String>()
            var downloadedBytes = 0L
            var ocrPages = 0
            var mainPages: Int? = null
            var mainOk = false
            val list = documents.take(EditalDocumentBase.MAX_DOCUMENTS)
            for ((index, doc) in list.withIndex()) {
                val isMain = index == 0
                val kind = EditalDocumentBase.classify(doc.title, doc.typeName, doc.typeId)
                    .let { if (isMain && doc.role == OfficialDocument.Role.EDITAL && it == EditalDocKind.OUTRO) EditalDocKind.EDITAL else it }
                if (downloadedBytes >= MAX_BASE_BYTES) {
                    skipped += "${doc.title} (limite total de download)"
                    continue
                }
                if (index > 0) delay(DOC_SPACING_MS)
                val file = if (isMain) mainPdf else temp
                try {
                    publishProgress(tenderId, EditalImportProgress.Stage.BAIXANDO, index + 1, list.size)
                    editalDownloader.download(doc.url, file)
                    downloadedBytes += file.length()
                    publishProgress(tenderId, EditalImportProgress.Stage.EXTRAINDO, index + 1, list.size)
                    val extraction = pdfExtractor.extract(file, maxPages = EditalDocumentBase.MAX_PAGES_PER_DOC)
                    var pages = extraction.pageTexts.map(EditalTextPreparer::normalize)
                    var ocr = false
                    if (EditalOcrSupport.needsOcr(extraction.scanned, pages.sumOf { EditalOcrSupport.meaningfulChars(it) })) {
                        val allowance = minOf(OCR_PAGES_PER_DOC, MAX_OCR_PAGES_TOTAL - ocrPages)
                        pages = emptyList()
                        if (allowance <= 0 || (!isMain && kind.priority > EditalDocKind.ANEXO.priority)) {
                            skipped += "${doc.title} (escaneado; OCR não executado para economizar tempo)"
                        } else {
                            val result = pdfOcrEngine.recognize(file, maxPages = allowance) { done, total ->
                                publishProgress(tenderId, EditalImportProgress.Stage.OCR, done, total)
                            }
                            ocrPages += result.pagesProcessed
                            if (result.usable) {
                                pages = result.pageTexts.map(EditalTextPreparer::normalize)
                                ocr = true
                            } else {
                                skipped += "${doc.title} (OCR sem texto legível)"
                            }
                        }
                    }
                    if (isMain) {
                        mainPages = extraction.totalPages
                        mainOk = true
                    }
                    if (pages.isNotEmpty()) sources += EditalSourceDocument(doc.title, kind, pages, extraction.totalPages, ocr)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    skipped += "${doc.title} (${e.message?.takeIf(String::isNotBlank) ?: "falha"})"
                } finally {
                    if (!isMain) temp.delete()
                }
            }
            val base = EditalDocumentBase.build(sources, maxChars = minOf(EditalDocumentBase.DEFAULT_MAX_CHARS, PdfTextExtractor.HARD_CHAR_LIMIT))
            val allSkipped = (skipped + base.skipped).distinct()
            if (base.text.isBlank()) {
                throw PdfExtractionException(
                    "Nenhum documento publicado no PNCP tinha texto legível" +
                        (if (allSkipped.isNotEmpty()) " (${allSkipped.joinToString("; ")})" else "") +
                        ". Importe o PDF do edital ou cole o texto na tela da licitação.",
                )
            }
            val anyOcr = base.documents.any { it.ocr }
            val textFile = editalStore.writeText(tender.companyId, tenderId, base.text)
            tenderDao.updateEdital(
                id = tenderId,
                pdfPath = mainPdf.takeIf { mainOk && it.exists() }?.absolutePath ?: tender.editalPdfPath,
                textPath = textFile.absolutePath, chars = base.text.length,
                pages = mainPages ?: tender.editalPages, scanned = anyOcr, registered = true, now = System.currentTimeMillis(),
            )
            audit.record(
                AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
                newValue = "${base.documents.size} documento(s) · ${base.text.length} caractere(s)",
                details = "Base de documentos do PNCP montada: " +
                    base.documents.joinToString("; ") { "${it.displayName} (${it.pagesIncluded} pág.${if (it.ocr) ", OCR" else ""}${if (it.truncated) ", cortado" else ""})" } +
                    (if (allSkipped.isNotEmpty()) "; fora da base: ${allSkipped.joinToString("; ")}" else ""),
            )
            Result.success(
                EditalImportResult(
                    chars = base.text.length, pages = mainPages, scanned = anyOcr,
                    storedPath = mainPdf.takeIf { mainOk }?.absolutePath ?: tender.editalPdfPath ?: textFile.absolutePath, ocr = anyOcr,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            runCatching {
                audit.record(
                    AuditAction.GERACAO_DOCUMENTO, result = AuditResult.FALHA, portal = tender.portal, tenderNumber = tender.number,
                    reason = error.message, details = "Falha ao montar a base de documentos do PNCP",
                )
            }
            Result.failure(
                when (error) {
                    is PdfExtractionException, is IOException, is IllegalArgumentException, is IllegalStateException -> error
                    else -> IllegalStateException(error.message ?: "Não foi possível baixar os documentos.", error)
                },
            )
        }
    }

    private fun publishProgress(tenderId: Long, stage: EditalImportProgress.Stage, page: Int = 0, total: Int = 0) {
        importProgress.update { it + (tenderId to EditalImportProgress(tenderId, stage, page, total)) }
    }

    private suspend fun doAttachEdital(
        tenderId: Long,
        source: EditalSource,
        /** Anexos oficiais (Termo de Referência etc.) cujo texto é concatenado ao do edital (só [EditalSource.Remote]). */
        annexes: List<OfficialDocument> = emptyList(),
    ): Result<EditalImportResult> {
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
                    processStoredPdf(tender, pdf, now, origin = "PDF do edital importado", annexes = emptyList())
                }
                is EditalSource.Remote -> {
                    publishProgress(tenderId, EditalImportProgress.Stage.BAIXANDO)
                    val pdf = editalDownloader.download(source.url, editalStore.pdfFile(tender.companyId, tenderId))
                    val fromPncp = runCatching { java.net.URI(source.url).host.orEmpty().lowercase().let { it == "pncp.gov.br" || it.endsWith(".pncp.gov.br") } }
                        .getOrDefault(false)
                    processStoredPdf(
                        tender, pdf, now,
                        origin = if (fromPncp) "Edital baixado do PNCP" else "Edital baixado do portal oficial",
                        annexes = annexes,
                    )
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
     * Extração de texto do PDF já gravado em `filesDir/editais/{companyId}/{tenderId}.pdf` (importado ou baixado):
     * texto direto quando há camada de texto; senão OCR local automático. [annexes] (download oficial) têm o texto
     * concatenado com "--- Anexo: <título> ---", respeitando o limite total de páginas do extrator.
     */
    private suspend fun processStoredPdf(
        tender: Tender,
        pdf: File,
        now: Long,
        origin: String,
        annexes: List<OfficialDocument>,
    ): EditalImportResult {
        val tenderId = tender.id
        publishProgress(tenderId, EditalImportProgress.Stage.EXTRAINDO)
        val extraction = pdfExtractor.extract(pdf)
        val extracted = EditalTextPreparer.normalize(extraction.text)
        if (!EditalOcrSupport.needsOcr(extraction.scanned, EditalOcrSupport.meaningfulChars(extracted))) {
            val annexText = collectAnnexText(tender, annexes, PdfTextExtractor.MAX_PAGES - extraction.pagesRead)
            val full = (extracted + annexText.text).take(PdfTextExtractor.HARD_CHAR_LIMIT)
            val textPath = editalStore.writeText(tender.companyId, tenderId, full).absolutePath
            tenderDao.updateEdital(
                id = tenderId, pdfPath = pdf.absolutePath, textPath = textPath, chars = full.length,
                pages = extraction.totalPages, scanned = false, registered = true, now = now,
            )
            audit.record(
                AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
                newValue = "${extraction.totalPages} página(s) · ${full.length} caractere(s)",
                details = "$origin e texto extraído" +
                    (if (extraction.truncated) " (lidas ${extraction.pagesRead} de ${extraction.totalPages} páginas)" else "") +
                    annexText.summary,
            )
            return EditalImportResult(chars = full.length, pages = extraction.totalPages, scanned = false, storedPath = pdf.absolutePath)
        }
        // Sem camada de texto: guarda o PDF como escaneado e tenta o OCR local em seguida.
        editalStore.textFile(tender.companyId, tenderId).delete()
        tenderDao.updateEdital(
            id = tenderId, pdfPath = pdf.absolutePath, textPath = null, chars = 0,
            pages = extraction.totalPages, scanned = true, registered = true, now = now,
        )
        audit.record(
            AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
            result = AuditResult.PENDENTE, newValue = "${extraction.totalPages} página(s) · 0 caractere(s)",
            details = "$origin sem camada de texto (escaneado); iniciando OCR local",
        )
        val ocr = runOcr(tender.copy(editalPdfPath = pdf.absolutePath, editalPages = extraction.totalPages), automatic = true)
        if (annexes.isEmpty()) return ocr
        val annexText = collectAnnexText(tender, annexes, PdfTextExtractor.MAX_PAGES - (ocr.pages ?: extraction.totalPages))
        if (annexText.text.isEmpty()) return ocr
        val base = editalStore.readText(editalStore.textFile(tender.companyId, tenderId).absolutePath).orEmpty()
        val full = (base + annexText.text).take(PdfTextExtractor.HARD_CHAR_LIMIT)
        val file = editalStore.writeText(tender.companyId, tenderId, full)
        tenderDao.updateEdital(
            id = tenderId, pdfPath = pdf.absolutePath, textPath = file.absolutePath, chars = full.length,
            pages = ocr.pages, scanned = true, registered = true, now = System.currentTimeMillis(),
        )
        audit.record(
            AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number, origin = AuditOrigin.SISTEMA,
            newValue = "${full.length} caractere(s)", details = "Texto dos anexos oficiais concatenado ao edital (OCR)" + annexText.summary,
        )
        return ocr.copy(chars = full.length)
    }

    private class AnnexText(val text: String, val summary: String)

    /**
     * Baixa e extrai o texto dos anexos oficiais (somente PDFs com camada de texto; sem OCR) até [pageBudget] páginas.
     * Falha em um anexo não derruba o edital: o anexo é apenas ignorado e citado no resumo da auditoria.
     */
    private suspend fun collectAnnexText(tender: Tender, annexes: List<OfficialDocument>, pageBudget: Int): AnnexText {
        if (annexes.isEmpty()) return AnnexText("", "")
        var budget = pageBudget
        val text = StringBuilder()
        val included = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val temp = File(editalStore.directory(tender.companyId), "${tender.id}.anexo.pdf")
        for (annex in annexes) {
            if (budget <= 0) {
                skipped += "${annex.title} (limite de páginas)"
                continue
            }
            try {
                publishProgress(tender.id, EditalImportProgress.Stage.BAIXANDO)
                editalDownloader.download(annex.url, temp)
                publishProgress(tender.id, EditalImportProgress.Stage.EXTRAINDO)
                val extraction = pdfExtractor.extract(temp, maxPages = budget)
                val annexBody = EditalTextPreparer.normalize(extraction.text)
                if (extraction.scanned || annexBody.isBlank()) {
                    skipped += "${annex.title} (sem camada de texto)"
                } else {
                    text.append("\n\n--- Anexo: ").append(annex.title).append(" ---\n\n").append(annexBody)
                    included += annex.title
                    budget -= extraction.pagesRead
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skipped += "${annex.title} (${e.message ?: "falha"})"
            } finally {
                temp.delete()
            }
        }
        val summary = buildString {
            if (included.isNotEmpty()) append("; anexos: ").append(included.joinToString(", "))
            if (skipped.isNotEmpty()) append("; anexos ignorados: ").append(skipped.joinToString(", "))
        }
        return AnnexText(text.toString(), summary)
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

        // Credenciais/preferência da empresa DA LICITAÇÃO, mesmo sem sessão aberta (análise retomada na abertura do app).
        val provider = withAiCompany(tender.companyId) { gateway.current() }
        val heuristic = provider.type == AiProviderType.MOCK
        val outcome = try {
            Result.success(withAiCompany(tender.companyId) { provider.analyzeTender(request) })
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
        // ~50 mil tokens: cabe com folga nos modelos atuais (GPT, Claude, Gemini) e cobre editais completos com TR.
        const val MAX_PROMPT_CHARS = 200_000
        const val MIN_PASTED_CHARS = 200
        /** Teto do que é baixado por licitação ao montar a base de documentos. */
        const val MAX_BASE_BYTES = 120L * 1024 * 1024
        /** OCR (lento) por documento escaneado e no total da base. */
        const val OCR_PAGES_PER_DOC = 40
        const val MAX_OCR_PAGES_TOTAL = 80
        /** Espaçamento entre downloads de arquivos do PNCP. */
        const val DOC_SPACING_MS = 400L
    }
}
