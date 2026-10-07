package com.licitaia.domain.model

import kotlinx.coroutines.flow.Flow

/**
 * Licitação arquivada ("Arquivar"): oportunidade da Busca/Radar e/ou licitação de interesse. A marca é a mesma da
 * versão 14 (`opportunity_flags.discardedAt`, por empresa e oportunidade) — sem coluna nova no banco. Arquivar uma
 * licitação de interesse NÃO apaga nada (análise, propostas, perguntas): ela só sai das listas, avisos e robôs.
 */
data class ArchivedEntry(
    val opportunityId: String,
    val archivedAt: Long,
    /** Licitação de interesse arquivada (null = só oportunidade da busca). */
    val tender: Tender?,
    /** Oportunidade ainda no cache local (null = já saiu do cache; só o id é conhecido). */
    val opportunity: Opportunity?,
) {
    val number: String get() = tender?.number ?: opportunity?.number ?: opportunityId.substringAfter(':')
    val agency: String get() = tender?.agency ?: opportunity?.agency.orEmpty()
    val objectDescription: String get() = tender?.objectDescription ?: opportunity?.objectDescription.orEmpty()
    val portal: Portal? get() = tender?.portal ?: opportunity?.portal
    val uasg: String? get() = tender?.uasg ?: opportunity?.let { UasgCode.of(it) }

    /** Busca local da tela "Licitações arquivadas" (número, órgão, objeto, UASG; sem acentos). */
    fun matches(query: String): Boolean {
        val q = com.licitaia.domain.scoring.TextMatch.normalize(query)
        if (q.isBlank()) return true
        val hay = com.licitaia.domain.scoring.TextMatch.normalize(listOf(number, agency, objectDescription, uasg.orEmpty(), opportunityId).joinToString(" "))
        return q.split(' ').filter { it.isNotBlank() }.all { hay.contains(it) }
    }
}

object ArchiveRules {
    /** Licitações de interesse visíveis nas listas (arquivadas ficam só na tela "Licitações arquivadas"). */
    fun visibleTenders(tenders: List<Tender>, archivedIds: Set<String>): List<Tender> = tenders.filter { it.opportunityId !in archivedIds }

    /** Ordem da tela: arquivadas mais recentes primeiro; licitações de interesse antes das só-oportunidades no empate. */
    fun sort(entries: List<ArchivedEntry>): List<ArchivedEntry> =
        entries.sortedWith(compareByDescending<ArchivedEntry> { it.archivedAt }.thenBy { if (it.tender != null) 0 else 1 })
}

interface ArchiveRepository {
    /** Ids de oportunidade arquivados pela empresa (inclui as de licitações de interesse). */
    fun observeArchivedIds(companyId: Long): Flow<Set<String>>

    /** Itens da tela "Licitações arquivadas". */
    fun observeArchived(companyId: Long): Flow<List<ArchivedEntry>>

    suspend fun archive(companyId: Long, opportunityId: String)

    suspend fun unarchive(companyId: Long, opportunityId: String)
}
