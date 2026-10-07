package com.licitaia.core.data.repository

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.OfficialItemsSource
import com.licitaia.connector.api.PortalConnector
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.pncpControlNumber
import com.licitaia.domain.proposal.OfficialBuyer
import com.licitaia.domain.proposal.OfficialTenderItem
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado da consulta dos itens oficiais; [failure] explica a lista vazia. */
class OfficialItemsLookup(
    val items: List<OfficialTenderItem> = emptyList(),
    val source: String? = null,
    val failure: Failure? = null,
    /** Mensagem técnica da última fonte que falhou (só com [Failure.ERROR]). */
    val error: String? = null,
) {
    enum class Failure {
        /** Licitação sem número de controle PNCP (cadastro manual ou fonte sem PNCP). */
        NO_CONTROL,
        /** Nenhum conector implementa [OfficialItemsSource]. */
        NO_SOURCES,
        /** Todas as fontes falharam (rede, HTTP...). */
        ERROR,
        /** As fontes responderam, mas sem itens publicados. */
        EMPTY,
    }
}

/**
 * Itens OFICIAIS da contratação: PNCP primeiro, depois dados abertos do Compras.gov.br. Compartilhado pela proposta
 * (montagem com os itens do edital) e pela aba "Itens". Nunca lança (exceto cancelamento).
 */
@Singleton
class OfficialItemsLoader @Inject constructor(private val registry: ConnectorRegistry) {

    suspend fun load(tender: Tender): OfficialItemsLookup {
        val control = tender.pncpControlNumber ?: return OfficialItemsLookup(failure = OfficialItemsLookup.Failure.NO_CONTROL)
        val sources = registry.all().filterIsInstance<OfficialItemsSource>()
            .sortedBy { if ((it as? PortalConnector)?.portal == Portal.PNCP) 0 else 1 }
        if (sources.isEmpty()) return OfficialItemsLookup(failure = OfficialItemsLookup.Failure.NO_SOURCES)
        var lastError: String? = null
        for (source in sources) {
            try {
                val items = source.officialItems(control)
                if (items.isNotEmpty()) return OfficialItemsLookup(items, (source as? PortalConnector)?.portal?.displayName ?: "fonte oficial")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message?.takeIf(String::isNotBlank) ?: e.javaClass.simpleName
            }
        }
        return if (lastError != null) {
            OfficialItemsLookup(failure = OfficialItemsLookup.Failure.ERROR, error = lastError)
        } else {
            OfficialItemsLookup(failure = OfficialItemsLookup.Failure.EMPTY)
        }
    }

    /**
     * Órgão e unidade compradora (PNCP primeiro). Sem número de controle ou se nenhuma fonte responder, monta com os
     * dados do cadastro da licitação (órgão, cidade/UF; CNPJ do número de controle) marcado como não oficial.
     */
    suspend fun loadBuyer(tender: Tender): OfficialBuyer {
        val control = tender.pncpControlNumber
        if (control != null) {
            val sources = registry.all().filterIsInstance<OfficialItemsSource>()
                .sortedBy { if ((it as? PortalConnector)?.portal == Portal.PNCP) 0 else 1 }
            for (source in sources) {
                try {
                    source.officialBuyer(control)?.let { return it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // tenta a próxima fonte; no fim, cadastro da licitação
                }
            }
        }
        return OfficialBuyer(
            agencyName = tender.agency.takeIf { it.isNotBlank() },
            agencyCnpj = control?.substringBefore('-')?.takeIf { it.length == 14 && it.all(Char::isDigit) },
            unitCode = null, unitName = null,
            city = tender.city.takeIf { it.isNotBlank() }, uf = tender.uf.takeIf { it.isNotBlank() },
            fromOfficialSource = false,
        )
    }
}
