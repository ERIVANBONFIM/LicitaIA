package com.licitaia.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.OfficialFilesSource
import com.licitaia.domain.model.OfficialFile
import com.licitaia.domain.model.OfficialLinks
import com.licitaia.domain.model.OfficialLinksRepository
import com.licitaia.domain.model.PncpControlNumbers
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arquivos oficiais (PNCP `/arquivos`) e `linkSistemaOrigem` por oportunidade, guardados no DataStore (JSON por
 * oportunidade; sem mudar o banco). "Atualizar" consulta de novo.
 */
@Singleton
class OfficialLinksRepositoryImpl @Inject constructor(
    private val registry: ConnectorRegistry,
    private val prefs: DataStore<Preferences>,
) : OfficialLinksRepository {

    private fun key(opportunityId: String) = stringPreferencesKey("official_links:$opportunityId")

    private fun source(): OfficialFilesSource? =
        (runCatching { registry.get(Portal.PNCP) }.getOrNull() as? OfficialFilesSource)
            ?: registry.all().firstNotNullOfOrNull { it as? OfficialFilesSource }

    override fun observe(opportunityId: String): Flow<OfficialLinks?> =
        prefs.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { OfficialLinksStore.decode(it[key(opportunityId)]) }
            .distinctUntilChanged()

    override suspend fun refresh(opportunityId: String): Result<OfficialLinks> = withContext(Dispatchers.IO) {
        try {
            val control = PncpControlNumbers.fromOpportunityId(opportunityId)
                ?: throw IllegalStateException("Esta licitação não tem número de controle PNCP: os arquivos oficiais não podem ser listados.")
            val src = source() ?: throw IllegalStateException("A consulta de arquivos do PNCP não está disponível.")
            val files = src.officialFiles(control)
            val origin = runCatching { src.originUrl(control) }.getOrNull()
            val links = OfficialLinks(origin, PortalLinks.pncpPageUrl(opportunityId), files, System.currentTimeMillis())
            prefs.edit { it[key(opportunityId)] = OfficialLinksStore.encode(links) }
            Result.success(links)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun originUrl(opportunityId: String): String? {
        val cached = runCatching { observe(opportunityId).first() }.getOrNull()
        cached?.originUrl?.let { return it }
        val control = PncpControlNumbers.fromOpportunityId(opportunityId) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                source()?.originUrl(control)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** JSON do cache de links oficiais. Puro e testável. */
internal object OfficialLinksStore {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    data class FileRow(val t: String, val k: String? = null, val u: String, val p: Long? = null, val s: Long? = null, val q: Int? = null)

    @Serializable
    data class Row(val o: String? = null, val n: String? = null, val f: List<FileRow> = emptyList(), val at: Long = 0L)

    fun encode(l: OfficialLinks): String = json.encodeToString(
        Row.serializer(),
        Row(l.originUrl, l.pncpUrl, l.files.map { FileRow(it.title, it.typeName, it.url, it.publishedAt, it.sizeBytes, it.sequence) }, l.fetchedAt),
    )

    fun decode(text: String?): OfficialLinks? {
        if (text.isNullOrBlank()) return null
        val r = runCatching { json.decodeFromString(Row.serializer(), text) }.getOrNull() ?: return null
        return OfficialLinks(r.o, r.n, r.f.map { OfficialFile(it.t, it.k, it.u, it.p, it.s, it.q) }, r.at)
    }
}
