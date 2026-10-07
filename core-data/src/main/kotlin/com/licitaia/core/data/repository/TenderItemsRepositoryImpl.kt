package com.licitaia.core.data.repository

import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.domain.repository.TenderItems
import com.licitaia.domain.repository.TenderItemsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aba "Itens": itens oficiais da licitação com cache EM MEMÓRIA por sessão do app (chave licitação × empresa), para não
 * refazer a consulta ao PNCP/Compras.gov.br a cada abertura. "Atualizar" ([refresh] = true) consulta de novo.
 * Falha de rede não entra no cache.
 */
@Singleton
class TenderItemsRepositoryImpl @Inject constructor(
    private val tenderDao: TenderDao,
    private val loader: OfficialItemsLoader,
    private val access: RepositoryAccess,
) : TenderItemsRepository {

    private data class Key(val tenderId: Long, val companyId: Long)

    private val cache = ConcurrentHashMap<Key, TenderItems>()

    override suspend fun officialItems(tenderId: Long, refresh: Boolean): Result<TenderItems> = withContext(Dispatchers.IO) {
        try {
            val tender = tenderDao.getById(tenderId)?.toDomain() ?: error("Licitação não encontrada.")
            access.requireCompany(tender.companyId)
            val key = Key(tenderId, tender.companyId)
            if (!refresh) cache[key]?.let { return@withContext Result.success(it.copy(fromCache = true)) }
            val lookup = loader.load(tender)
            val now = System.currentTimeMillis()
            val result = when (lookup.failure) {
                null -> TenderItems(lookup.items.sortedBy { it.number }, lookup.source, now)
                OfficialItemsLookup.Failure.NO_CONTROL -> TenderItems(
                    emptyList(), null, now,
                    "Esta licitação não tem número de controle PNCP (cadastro manual ou portal fora do PNCP): os itens oficiais " +
                        "não podem ser consultados. Confira os itens no edital.",
                )
                OfficialItemsLookup.Failure.NO_SOURCES -> TenderItems(emptyList(), null, now, "Consulta de itens oficiais indisponível neste aparelho.")
                OfficialItemsLookup.Failure.EMPTY -> TenderItems(
                    emptyList(), null, now, "O PNCP e o Compras.gov.br não têm itens publicados para esta contratação.",
                )
                OfficialItemsLookup.Failure.ERROR -> error(
                    "Não foi possível consultar os itens oficiais (${lookup.error}). Verifique a conexão e toque em Atualizar.",
                )
            }
            // Órgão/unidade compradora para "Órgão e local" no detalhe do item (falha → dados do cadastro).
            val withBuyer = result.copy(buyer = loader.loadBuyer(tender))
            access.requireCompany(tender.companyId)
            cache[key] = withBuyer
            Result.success(withBuyer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Descarta o cache (ex.: testes). */
    internal fun clearCache() = cache.clear()
}
