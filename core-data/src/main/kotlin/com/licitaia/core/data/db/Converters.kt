package com.licitaia.core.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// DTOs @Serializable internos: o domínio não é anotado.

/** Itens da proposta em JSON; campos novos são opcionais (registros antigos continuam válidos, sem migração). */
@Serializable
data class ProposalItemDto(
    val description: String,
    val unit: String,
    val quantity: Double,
    val unitPrice: Double,
    val itemNumber: Int? = null,
    val brand: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val estimatedUnitPrice: Double? = null,
    val confidentialBudget: Boolean = false,
)

@Serializable
data class BidAuthorizationDto(
    val id: String,
    val sessionId: String,
    val proposedValue: Double,
    val reason: String,
    val requestedAt: Long,
)

@Serializable
data class ExtractedEditalDto(
    val objectDescription: String,
    val agency: String,
    val portal: String,
    val number: String,
    val modality: String,
    val estimatedValue: Double,
    val proposalDeadline: Long,
    val openingAt: Long,
    val sessionAt: Long,
    val installationDeadline: String,
    val sla: String,
    val technicalRequirements: List<String>,
    val requiredDocuments: List<String>,
    val guarantees: List<String>,
    val penalties: List<String>,
    val attestationRequirements: List<String>,
    val certificationRequirements: List<String>,
)

@Serializable
data class FitScoreDto(
    val overall: Int,
    val technical: Int,
    val documentary: Int,
    val financial: Int,
    val estimatedMarginPct: Double,
    val operationalRisk: String,
    val documentaryRisk: String,
    val contractualRisk: String,
    val deadlineFit: Int,
    val historicalCompetitors: Int,
    val geographicFit: Int,
    val investmentNeeded: Double,
)

@Serializable
data class PriceRangeDto(val min: Double, val suggested: Double, val max: Double)

@Serializable
data class ChecklistItemDto(
    val title: String,
    val done: Boolean,
    val critical: Boolean = false,
    val relatedDocument: String? = null,
)

@Serializable
data class TenderAnalysisDto(
    val summary: String,
    val extracted: ExtractedEditalDto,
    val fit: FitScoreDto,
    val recommendation: String,
    val justification: String,
    val criticalPoints: List<String>,
    val priceRange: PriceRangeDto,
    val checklist: List<ChecklistItemDto>,
    /** Campos preenchidos pelo modelo de IA; ausente em análises antigas (= heurística). */
    val aiFields: List<String> = emptyList(),
)

class RoomConverters {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @TypeConverter
    fun stringListToJson(value: List<String>?): String? = value?.let(json::encodeToString)

    @TypeConverter
    fun jsonToStringList(value: String?): List<String>? =
        value?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }

    @TypeConverter
    fun longListToJson(value: List<Long>?): String? = value?.let(json::encodeToString)

    @TypeConverter
    fun jsonToLongList(value: String?): List<Long>? =
        value?.let { runCatching { json.decodeFromString<List<Long>>(it) }.getOrDefault(emptyList()) }

    @TypeConverter
    fun proposalItemsToJson(value: List<ProposalItemDto>?): String? = value?.let(json::encodeToString)

    @TypeConverter
    fun jsonToProposalItems(value: String?): List<ProposalItemDto>? =
        value?.let { runCatching { json.decodeFromString<List<ProposalItemDto>>(it) }.getOrDefault(emptyList()) }

    @TypeConverter
    fun authorizationToJson(value: BidAuthorizationDto?): String? = value?.let(json::encodeToString)

    @TypeConverter
    fun jsonToAuthorization(value: String?): BidAuthorizationDto? =
        value?.let { runCatching { json.decodeFromString<BidAuthorizationDto>(it) }.getOrNull() }

    @TypeConverter
    fun analysisToJson(value: TenderAnalysisDto): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToAnalysis(value: String): TenderAnalysisDto = json.decodeFromString(value)
}
