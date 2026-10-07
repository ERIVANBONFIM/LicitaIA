package com.licitaia.core.data.db

import com.licitaia.domain.model.AppNotification
import com.licitaia.domain.model.AuthProvider
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.AuditEvent
import com.licitaia.domain.model.BidAuthorization
import com.licitaia.domain.model.BidEvent
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.ChecklistItem
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.model.ExtractedEdital
import com.licitaia.domain.model.FitScore
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalSession
import com.licitaia.domain.model.PriceRange
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.ProposalItem
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.Recommendation
import com.licitaia.domain.model.RiskLevel
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.UserProfile

private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback

// ---------------------------------------------------------------- empresa / usuário

fun CompanyEntity.toDomain() = Company(id, name, tradeName, cnpj, segment, uf, city, preferredAi, demo)
fun Company.toEntity() = CompanyEntity(id, name, tradeName, cnpj, segment, uf, city, preferredAi, demo)

fun UserEntity.toDomain() = UserProfile(
    id, name, email, role, companyIds,
    provider = AuthProvider.entries.firstOrNull { it.name == provider } ?: AuthProvider.LOCAL,
    demo = demo,
)

// ---------------------------------------------------------------- radar / oportunidade

fun RadarEntity.toDomain() = Radar(
    id, companyId, name, segment, keywords, forbiddenKeywords,
    portals.mapNotNull { p -> Portal.entries.firstOrNull { it.name == p } }, allPortals, ufs, region, agency,
    modality, minValue, maxValue, startDate, endDate, minScore, cnae, preferredObject, requireLocalSupport,
    active, createdAt, showNoDispute,
)

fun Radar.toEntity() = RadarEntity(
    id, companyId, name, segment, keywords, forbiddenKeywords, portals.map { it.name }, allPortals, ufs, region,
    agency, modality, minValue, maxValue, startDate, endDate, minScore, cnae, preferredObject, requireLocalSupport,
    active, createdAt, showNoDispute,
)

fun OpportunityEntity.toDomain() = Opportunity(
    id, portal, number, agency, objectDescription, modality, segment, uf, city, estimatedValue, publishedAt,
    proposalDeadline, sessionAt, requiresLocalSupport, keywords, editalUrl, platformName, noDispute,
)

fun Opportunity.toEntity(now: Long) = OpportunityEntity(
    id, portal, number, agency, objectDescription, modality, segment, uf, city, estimatedValue, publishedAt,
    proposalDeadline, sessionAt, requiresLocalSupport, keywords, editalUrl, now, platformName, noDispute,
)

// ---------------------------------------------------------------- licitação / análise

fun TenderEntity.toDomain() = Tender(
    id, companyId, opportunityId, portal, number, agency, objectDescription, modality, segment, uf, city,
    estimatedValue, proposalDeadline, sessionAt, status, editalRegistered, createdAt, updatedAt,
    editalPdfPath, editalTextPath, editalChars, editalPages, editalScanned,
)

fun Tender.toEntity() = TenderEntity(
    id, companyId, opportunityId, portal, number, agency, objectDescription, modality, segment, uf, city,
    estimatedValue, proposalDeadline, sessionAt, status, editalRegistered, createdAt, updatedAt,
    editalPdfPath, editalTextPath, editalChars, editalPages, editalScanned,
)

fun Opportunity.toTender(companyId: Long, now: Long, editalRegistered: Boolean) = Tender(
    companyId = companyId, opportunityId = id, portal = portal, number = number, agency = agency,
    objectDescription = objectDescription, modality = modality, segment = segment, uf = uf, city = city,
    estimatedValue = estimatedValue, proposalDeadline = proposalDeadline, sessionAt = sessionAt,
    editalRegistered = editalRegistered, createdAt = now, updatedAt = now,
)

fun ExtractedEdital.toDto() = ExtractedEditalDto(
    objectDescription, agency, portal, number, modality, estimatedValue, proposalDeadline, openingAt, sessionAt,
    installationDeadline, sla, technicalRequirements, requiredDocuments.map { it.name }, guarantees, penalties,
    attestationRequirements, certificationRequirements,
)

fun ExtractedEditalDto.toDomain() = ExtractedEdital(
    objectDescription, agency, portal, number, modality, estimatedValue, proposalDeadline, openingAt, sessionAt,
    installationDeadline, sla, technicalRequirements,
    requiredDocuments.mapNotNull { d -> DocumentType.entries.firstOrNull { it.name == d } },
    guarantees, penalties, attestationRequirements, certificationRequirements,
)

fun FitScore.toDto() = FitScoreDto(
    overall, technical, documentary, financial, estimatedMarginPct, operationalRisk.name, documentaryRisk.name,
    contractualRisk.name, deadlineFit, historicalCompetitors, geographicFit, investmentNeeded,
)

fun FitScoreDto.toDomain() = FitScore(
    overall, technical, documentary, financial, estimatedMarginPct,
    enumOr(operationalRisk, RiskLevel.MEDIO), enumOr(documentaryRisk, RiskLevel.MEDIO),
    enumOr(contractualRisk, RiskLevel.MEDIO), deadlineFit, historicalCompetitors, geographicFit, investmentNeeded,
)

fun TenderAnalysis.toEntity() = TenderAnalysisEntity(
    tenderId = tenderId,
    providerName = providerName,
    generatedAt = generatedAt,
    recommendation = recommendation,
    overallScore = fit.overall,
    payload = TenderAnalysisDto(
        summary = summary,
        extracted = extracted.toDto(),
        fit = fit.toDto(),
        recommendation = recommendation.name,
        justification = justification,
        criticalPoints = criticalPoints,
        priceRange = PriceRangeDto(priceRange.min, priceRange.suggested, priceRange.max),
        checklist = checklist.map { ChecklistItemDto(it.title, it.done, it.critical, it.relatedDocument?.name) },
        aiFields = aiFields.toList(),
    ),
)

fun TenderAnalysisEntity.toDomain() = TenderAnalysis(
    tenderId = tenderId,
    providerName = providerName,
    generatedAt = generatedAt,
    summary = payload.summary,
    extracted = payload.extracted.toDomain(),
    fit = payload.fit.toDomain(),
    recommendation = enumOr(payload.recommendation, Recommendation.AVALIAR),
    justification = payload.justification,
    criticalPoints = payload.criticalPoints,
    priceRange = PriceRange(payload.priceRange.min, payload.priceRange.suggested, payload.priceRange.max),
    checklist = payload.checklist.map {
        ChecklistItem(it.title, it.done, it.critical, it.relatedDocument?.let { d -> DocumentType.entries.firstOrNull { e -> e.name == d } })
    },
    aiFields = payload.aiFields.toSet(),
)

// ---------------------------------------------------------------- documentos / propostas

fun DocumentEntity.toDomain() =
    CompanyDocument(id, companyId, type, title, issuer, issuedAt, expiresAt, attachmentUri, tags, notes, createdAt)

fun CompanyDocument.toEntity() =
    DocumentEntity(id, companyId, type, title, issuer, issuedAt, expiresAt, attachmentUri, tags, notes, createdAt)

fun ProposalEntity.toDomain() = Proposal(
    id, tenderId, companyId, version, items.map { ProposalItem(it.description, it.unit, it.quantity, it.unitPrice) },
    deliveryDays, validityDays, notes, status, pdfPath, createdBy, createdAt, approvedBy, approvedAt, rejectionReason,
)

fun Proposal.toEntity() = ProposalEntity(
    id, tenderId, companyId, version, items.map { ProposalItemDto(it.description, it.unit, it.quantity, it.unitPrice) },
    deliveryDays, validityDays, notes, status, pdfPath, createdBy, createdAt, approvedBy, approvedAt, rejectionReason,
)

// ---------------------------------------------------------------- portais / sessões ao vivo

fun PortalSessionEntity.toDomain() = PortalSession(id, companyId, portal, username, status, lastLoginAt, sessionKey)
fun PortalSession.toEntity() = PortalSessionEntity(id, companyId, portal, username, status, lastLoginAt, sessionKey)

fun BidRule.toColumns() = BidRuleColumns(
    mode, strategy, initialPrice, floorPrice, costPrice, reductionValue, minMarginPct, lossLimit,
    minIntervalSeconds, authorizationThresholdPct, simulation,
)

fun BidRuleColumns.toDomain() = BidRule(
    mode, strategy, initialPrice, floorPrice, costPrice, reductionValue, minMarginPct, lossLimit,
    minIntervalSeconds, authorizationThresholdPct, simulation,
)

fun LiveSession.toEntity() = LiveSessionEntity(
    id, companyId, tenderId, portal, tenderNumber, agency, itemLabel, objectDescription, status, robotStatus,
    position, competitors, ourLastBid, bestBid, rule.toColumns(), remainingSeconds, captchaPending, captchaSince,
    pendingAuthorization?.let { BidAuthorizationDto(it.id, it.sessionId, it.proposedValue, it.reason, it.requestedAt) },
    unreadMessages, lastError, startedAt, updatedAt,
)

fun LiveSessionEntity.toDomain() = LiveSession(
    id, companyId, tenderId, portal, tenderNumber, agency, itemLabel, objectDescription, status, robotStatus,
    position, competitors, ourLastBid, bestBid, rule.toDomain(), remainingSeconds, captchaPending, captchaSince,
    pendingAuthorization?.let { BidAuthorization(it.id, it.sessionId, it.proposedValue, it.reason, it.requestedAt) },
    unreadMessages, lastError, startedAt, updatedAt,
)

fun BidEventEntity.toDomain() = BidEvent(id, sessionId, timestamp, type, value, actor, description)
fun BidEvent.toEntity() = BidEventEntity(id, sessionId, timestamp, type, value, actor, description)

// ---------------------------------------------------------------- mensagens / notificações / auditoria

fun MessageEntity.toDomain() = AuctioneerMessage(
    id, companyId, sessionId, portal, tenderNumber, sender, body, receivedAt, read, urgent, responseDeadline,
    aiSummary, suggestedReply, replyDraft, replyStatus, repliedAt,
)

fun AuctioneerMessage.toEntity() = MessageEntity(
    id, companyId, sessionId, portal, tenderNumber, sender, body, receivedAt, read, urgent, responseDeadline,
    aiSummary, suggestedReply, replyDraft, replyStatus, repliedAt,
)

fun NotificationEntity.toDomain() =
    AppNotification(id, companyId, category, title, body, createdAt, read, critical, route, sessionId)

fun AppNotification.toEntity() =
    NotificationEntity(id, companyId, category, title, body, createdAt, read, critical, route, sessionId)

fun AuditEventEntity.toDomain() = AuditEvent(
    id, timestamp, user, companyId, companyName, portal, tenderNumber, item, action, previousValue, newValue,
    reason, origin, result, details, prevHash, hash,
)

fun AuditEvent.toEntity() = AuditEventEntity(
    id, timestamp, user, companyId, companyName, portal, tenderNumber, item, action, previousValue, newValue,
    reason, origin, result, details, prevHash, hash,
)

fun CompetitionEntity.toDomain() = CompetitionRecord(
    id, companyId, portal, tenderNumber, agency, segment, objectSummary, date, competitors, estimatedValue,
    closingValue, ourFinalBid, won, ourMarginPct, bidsCount, behavior,
)

fun CompetitionRecord.toEntity() = CompetitionEntity(
    id, companyId, portal, tenderNumber, agency, segment, objectSummary, date, competitors, estimatedValue,
    closingValue, ourFinalBid, won, ourMarginPct, bidsCount, behavior,
)
