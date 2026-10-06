package com.licitaia.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.DocumentRepository
import com.licitaia.domain.repository.NotificationRepository
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.repository.ProposalRepository
import com.licitaia.domain.repository.TenderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val loading: Boolean = true,
    val userName: String = "",
    val roleLabel: String = "",
    val companyName: String = "",
    val companyCnpj: String = "",
    val liveCount: Int = 0,
    val robotsActive: Int = 0,
    val unreadAlerts: Int = 0,
    val radarMatches: Int = 0,
    val interests: Int = 0,
    val documentsExpiring: Int = 0,
    val pendingApprovals: Int = 0,
    val pendingBidAuthorizations: Int = 0,
    val sessions: List<LiveSession> = emptyList(),
    val captchaSessions: List<LiveSession> = emptyList(),
    val upcoming: List<Tender> = emptyList(),
) {
    val pendingAuthorizations: Int get() = pendingApprovals + pendingBidAuthorizations
}

private data class Counts(val unread: Int, val radar: Int, val docs: Int, val approvals: Int)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    auth: AuthRepository,
    live: LiveSessionManager,
    notifications: NotificationRepository,
    opportunities: OpportunityRepository,
    tenders: TenderRepository,
    documents: DocumentRepository,
    proposals: ProposalRepository,
) : ViewModel() {

    val state: StateFlow<DashboardUiState> = auth.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(DashboardUiState(loading = false))
        } else {
            val companyId = session.activeCompany.id
            val counts = combine(
                notifications.observeUnreadCount(companyId).safe(0),
                opportunities.observeRadarMatchCount(companyId).safe(0),
                documents.observeExpiringCount(companyId).safe(0),
                proposals.observePendingApprovals(companyId).safe(0),
            ) { unread, radar, docs, approvals -> Counts(unread, radar, docs, approvals) }

            combine(
                live.sessions,
                tenders.observeTenders(companyId).catch { emit(emptyList()) },
                counts,
            ) { allSessions, tenderList, c ->
                val now = System.currentTimeMillis()
                val sessions = allSessions.filter { it.companyId == companyId && it.status != LiveStatus.ENCERRADA }
                DashboardUiState(
                    loading = false,
                    userName = session.user.name,
                    roleLabel = session.user.role.label,
                    companyName = session.activeCompany.tradeName.ifBlank { session.activeCompany.name },
                    companyCnpj = session.activeCompany.cnpj,
                    liveCount = sessions.size,
                    robotsActive = sessions.count { it.robotRunning },
                    unreadAlerts = c.unread,
                    radarMatches = c.radar,
                    interests = tenderList.count { it.status != TenderStatus.DESCARTADA },
                    documentsExpiring = c.docs,
                    pendingApprovals = c.approvals,
                    pendingBidAuthorizations = sessions.count { it.pendingAuthorization != null },
                    sessions = sessions,
                    captchaSessions = sessions.filter { it.captchaPending },
                    upcoming = tenderList
                        .filter { it.status !in CLOSED && it.proposalDeadline >= now }
                        .sortedBy { it.proposalDeadline }
                        .take(4),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    private fun Flow<Int>.safe(default: Int): Flow<Int> = onStart { emit(default) }.catch { emit(default) }

    private companion object {
        val CLOSED = setOf(TenderStatus.DESCARTADA, TenderStatus.PERDIDA, TenderStatus.VENCIDA)
    }
}
