package com.licitaia.feature.tender

import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.pncpControlNumber
import com.licitaia.domain.repository.TenderRepository
import kotlinx.coroutines.CancellationException

/**
 * "Analisar com IA" (pedido explícito): sem texto do edital e com número de controle PNCP, baixa primeiro o edital
 * oficial para a IA ler o documento real; falha no download não impede a análise (metadados, rotulada na tela).
 */
internal suspend fun TenderRepository.analyzeWithOfficialEdital(tender: Tender?, tenderId: Long): Result<TenderAnalysis> {
    if (tender != null && !tender.hasEditalText && tender.editalPdfPath == null && tender.pncpControlNumber != null) {
        try {
            fetchOfficialEdital(tenderId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // o motivo fica no card do edital (observeOfficialEditalError)
        }
    }
    return analyze(tenderId)
}
