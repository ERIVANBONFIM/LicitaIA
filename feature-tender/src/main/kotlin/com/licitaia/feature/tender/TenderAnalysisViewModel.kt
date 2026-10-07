package com.licitaia.feature.tender

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AiConfigRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.DocumentRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RequiredDocumentRow(val type: DocumentType, val status: DocumentStatus, val document: CompanyDocument?)

data class TenderAnalysisState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val tender: Tender? = null,
    val analysis: TenderAnalysis? = null,
    val documents: List<RequiredDocumentRow> = emptyList(),
    val analyzing: Boolean = false,
    val error: String? = null,
    val role: UserRole? = null,
    /** Provedor de IA que o app usa agora (MOCK = nenhum provedor real disponível). */
    val activeAi: AiProviderType = AiProviderType.MOCK,
) {
    val canAnalyze: Boolean get() = role?.let { Rbac.can(it, Permission.ANALISAR) } ?: false
}

private data class LocalFlags(val analyzing: Boolean = false, val error: String? = null)

/** Compartilhado por "Análise do edital" e "Vale a pena participar?". */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TenderAnalysisViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    auth: AuthRepository,
    private val tenders: TenderRepository,
    documents: DocumentRepository,
    aiConfig: AiConfigRepository,
) : ViewModel() {

    private val tenderId: Long = savedStateHandle.longArg("tenderId") ?: -1L

    /** Aba inicial pedida pela rota (`?tab=analise|perguntas|itens`); null = Análise. */
    val initialTab: String? = savedStateHandle.get<String>("tab")
    private val flags = MutableStateFlow(LocalFlags())

    private val remote = auth.session.flatMapLatest { session ->
        if (session == null || tenderId <= 0) {
            flowOf(TenderAnalysisState(loading = false, notFound = true))
        } else {
            combine(
                tenders.observeTender(tenderId),
                tenders.observeAnalysis(tenderId).catch { emit(null) },
                documents.observeDocuments(session.activeCompany.id).catch { emit(emptyList()) },
            ) { tender, analysis, docs ->
                if (tender == null || tender.companyId != session.activeCompany.id) {
                    TenderAnalysisState(loading = false, notFound = true)
                } else {
                    TenderAnalysisState(
                        loading = false, tender = tender, analysis = analysis, role = session.user.role,
                        documents = analysis?.extracted?.requiredDocuments.orEmpty().distinct().map { type -> matchDocument(type, docs) },
                    )
                }
            }.catch { emit(TenderAnalysisState(loading = false, notFound = true)) }
        }
    }

    private val activeAi = aiConfig.observeEffective().catch { emit(AiProviderType.MOCK) }

    val state: StateFlow<TenderAnalysisState> = combine(remote, flags, activeAi) { s, f, ai -> s.copy(analyzing = f.analyzing, error = f.error, activeAi = ai) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TenderAnalysisState())

    init {
        // Análise antiga heurística (feita quando não havia provedor) + provedor real disponível agora: reanalisa
        // automaticamente UMA vez por licitação no processo (sem loop: falha ou nova heurística não repete).
        viewModelScope.launch {
            val target = state.first { s -> s.notFound || (!s.loading && s.analysis != null && s.activeAi != AiProviderType.MOCK) }
            val analysis = target.analysis ?: return@launch
            if (analysis.heuristicOnly && target.canAnalyze && !target.analyzing && autoReanalyzed.add(tenderId)) analyze()
        }
        // A análise é disparada em segundo plano pelo "Tenho Interesse"; se não chegar em alguns
        // segundos (ex.: app fechado no meio), disparamos aqui mesmo.
        // Licitações cadastradas manualmente NÃO são analisadas sozinhas: o usuário importa o edital e decide.
        viewModelScope.launch {
            val first = state.first { !it.loading }
            if (first.notFound || first.analysis != null || first.tender?.isManual == true) return@launch
            delay(AUTO_ANALYZE_DELAY_MS)
            val current = state.value
            if (current.analysis == null && !current.analyzing && current.tender != null && !current.tender.isManual) analyze()
        }
    }

    fun analyze() {
        if (flags.value.analyzing || tenderId <= 0) return
        flags.update { it.copy(analyzing = true, error = null) }
        viewModelScope.launch {
            val result = try {
                tenders.analyze(tenderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            flags.update {
                it.copy(
                    analyzing = false,
                    error = result.exceptionOrNull()?.let { e -> e.message?.takeIf(String::isNotBlank) ?: "A IA não conseguiu concluir a análise." },
                )
            }
        }
    }

    private fun matchDocument(type: DocumentType, docs: List<CompanyDocument>): RequiredDocumentRow {
        val now = System.currentTimeMillis()
        val best = docs.filter { it.type == type }.minByOrNull { rank(it.status(now)) }
        return RequiredDocumentRow(type, best?.status(now) ?: DocumentStatus.AUSENTE, best)
    }

    private fun rank(status: DocumentStatus) = when (status) {
        DocumentStatus.VALIDO -> 0
        DocumentStatus.VENCE_EM_BREVE -> 1
        DocumentStatus.VENCIDO -> 2
        DocumentStatus.AUSENTE -> 3
    }

    private companion object {
        const val AUTO_ANALYZE_DELAY_MS = 8_000L
        /** Licitações já reanalisadas automaticamente neste processo (evita gastar cota em loop). */
        val autoReanalyzed: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())
    }
}
