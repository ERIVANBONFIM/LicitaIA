package com.licitaia.feature.tender

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.competition.CompetitionResultsSync
import com.licitaia.domain.competition.MarketSnapshot
import com.licitaia.domain.competition.TenderCompetitorsView
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
    private val competition: CompetitionResultsSync,
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

    private val repoAnalyzing = tenders.observeAnalyzing(tenderId).catch { emit(false) }

    val state: StateFlow<TenderAnalysisState> = combine(remote, flags, activeAi, repoAnalyzing) { s, f, ai, busy ->
        s.copy(analyzing = f.analyzing || busy, error = f.error, activeAi = ai)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TenderAnalysisState())

    // Nenhuma análise é disparada sozinha nesta tela (nem reanálise de análises heurísticas): só por pedido do usuário
    // ("Analisar com IA", "Reanalisar") ou pelo "Analisar" do card da busca (que já inicia a análise no repositório).

    /** Concorrentes desta licitação a partir dos resultados públicos do PNCP (base da Concorrência). */
    val competitors: StateFlow<TenderCompetitorsView?> = auth.session.flatMapLatest { session ->
        if (session == null || tenderId <= 0) flowOf(null)
        else combine(tenders.observeTender(tenderId), competition.observeMarket(session.activeCompany.id).catch { emit(MarketSnapshot()) }) { tender, market ->
            tender?.let { TenderCompetitorsSupport.view(it, market.results, session.activeCompany.cnpj, session.activeCompany.segment) }
        }.catch { emit(null) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun analyze() {
        if (flags.value.analyzing || tenderId <= 0) return
        flags.update { it.copy(analyzing = true, error = null) }
        viewModelScope.launch {
            val result = try {
                tenders.analyzeWithOfficialEdital(state.value.tender, tenderId)
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

}
