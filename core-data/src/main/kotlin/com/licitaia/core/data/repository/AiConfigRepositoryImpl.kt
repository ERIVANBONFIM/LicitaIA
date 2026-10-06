package com.licitaia.core.data.repository

import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.api.AiProviderException
import com.licitaia.core.ai.AiCredentials
import com.licitaia.core.ai.AiEndpoint
import com.licitaia.core.ai.AiSecretKeys
import com.licitaia.core.ai.AiSettingsSource
import com.licitaia.core.ai.GoogleAiAuthorizer
import com.licitaia.core.data.db.AiConfigDao
import com.licitaia.core.data.db.AiConfigEntity
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.core.data.settings.SettingsRepositoryImpl
import com.licitaia.core.security.SecretStore
import com.licitaia.domain.model.AiAuthMode
import com.licitaia.domain.model.AiConfig
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.repository.AiConfigRepository
import com.licitaia.domain.repository.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiConfigRepositoryImpl @Inject constructor(
    private val aiConfigDao: AiConfigDao,
    private val settings: SettingsRepositoryImpl,
    private val secretStore: SecretStore,
    private val gateway: AiGateway,
    private val credentials: AiCredentials,
    private val googleAuth: GoogleAiAuthorizer,
    private val audit: AuditRepository,
    private val holder: SessionHolder,
    private val access: RepositoryAccess,
) : AiConfigRepository {

    /** Incrementado a cada alteração de chave para reemitir [observeConfigs]. */
    private val keyVersion = MutableStateFlow(0)

    override fun observeConfigs(): Flow<List<AiConfig>> =
        combine(aiConfigDao.observeAll(), keyVersion) { rows, _ ->
            AiProviderType.entries.map { type ->
                val row = rows.firstOrNull { it.provider == type }
                val oauthMode = row?.authMode == AiAuthMode.OAUTH && type.supportsOAuth
                // Conta só é "autorizada" se o token ainda estiver no cofre (pode ter sido invalidado pelo Keystore).
                val authorized = oauthMode && row?.oauthAccount != null && secretStore.contains(AiSecretKeys.oauthToken(type))
                AiConfig(
                    provider = type,
                    model = row?.model?.takeUnless { it.isBlank() || it in LEGACY_DEFAULT_MODELS } ?: type.defaultModel,
                    baseUrl = row?.baseUrl ?: type.defaultBaseUrl,
                    hasApiKey = type != AiProviderType.MOCK && secretStore.contains(AiSecretKeys.apiKey(type)),
                    authMode = if (oauthMode) AiAuthMode.OAUTH else AiAuthMode.API_KEY,
                    oauthAccount = if (authorized) row?.oauthAccount else null,
                    cloudProject = row?.cloudProject?.takeIf { it.isNotBlank() },
                )
            }
        }.flowOn(Dispatchers.IO)

    /** Linha atual ou padrão do provedor, para alterações parciais sem perder modelo/URL. */
    private suspend fun rowOrDefault(provider: AiProviderType): AiConfigEntity =
        aiConfigDao.get(provider) ?: AiConfigEntity(provider = provider, model = provider.defaultModel, baseUrl = provider.defaultBaseUrl)

    private fun requireConfigureIa() {
        access.requireCompany(requireNotNull(holder.current).activeCompany.id, com.licitaia.domain.security.Permission.CONFIGURAR_IA)
    }

    override suspend fun setAuthMode(provider: AiProviderType, mode: AiAuthMode) {
        requireConfigureIa()
        require(mode == AiAuthMode.API_KEY || provider.supportsOAuth) { "${provider.label} não oferece login com conta para apps de terceiros." }
        withContext(Dispatchers.IO) {
            val row = rowOrDefault(provider)
            if (row.authMode == mode) return@withContext
            aiConfigDao.upsert(row.copy(authMode = mode))
            audit.record(
                AuditAction.CONFIGURACAO, previousValue = row.authMode.label, newValue = mode.label,
                details = "Modo de autenticação de ${provider.label} alterado",
            )
        }
    }

    override suspend fun saveOAuth(provider: AiProviderType, account: String, cloudProject: String?) {
        requireConfigureIa()
        require(provider.supportsOAuth) { "${provider.label} não oferece login com conta para apps de terceiros." }
        withContext(Dispatchers.IO) {
            val row = rowOrDefault(provider)
            aiConfigDao.upsert(
                row.copy(
                    authMode = AiAuthMode.OAUTH,
                    oauthAccount = account.trim().lowercase().ifEmpty { "conta Google" },
                    cloudProject = cloudProject?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
            keyVersion.value++
            // Só o e-mail e o projeto vão para a auditoria — nunca o token.
            audit.record(
                AuditAction.CONFIGURACAO, newValue = "${provider.label} · ${account.trim().lowercase()}",
                details = "Conta Google autorizada para a IA" + (cloudProject?.trim()?.takeIf { it.isNotEmpty() }?.let { " (projeto $it)" } ?: ""),
            )
        }
    }

    override suspend fun clearOAuth(provider: AiProviderType) {
        requireConfigureIa()
        withContext(Dispatchers.IO) {
            val row = aiConfigDao.get(provider)
            val previous = row?.oauthAccount
            googleAuth.revoke()
            if (row != null) aiConfigDao.upsert(row.copy(authMode = AiAuthMode.API_KEY, oauthAccount = null))
            keyVersion.value++
            audit.record(
                AuditAction.CONFIGURACAO, previousValue = previous, newValue = provider.label,
                details = "Conta Google desconectada da IA (token removido do cofre)",
            )
        }
    }

    override fun observeActive(): Flow<AiProviderType> = settings.settings.map { it.activeAiProvider }.distinctUntilChanged()

    override suspend fun setActive(provider: AiProviderType) {
        access.requireCompany(requireNotNull(holder.current).activeCompany.id, com.licitaia.domain.security.Permission.CONFIGURAR_IA)
        require(provider != AiProviderType.MOCK) { "IA simulada indisponível nesta entrega." }
        val previous = settings.current().activeAiProvider
        settings.update { it.copy(activeAiProvider = provider) }
        if (previous != provider) {
            audit.record(AuditAction.CONFIGURACAO, previousValue = previous.label, newValue = provider.label, details = "Provedor de IA ativo alterado")
        }
    }

    override suspend fun saveConfig(provider: AiProviderType, model: String, baseUrl: String, apiKey: String?, cloudProject: String?) {
        requireConfigureIa()
        withContext(Dispatchers.IO) {
            val row = rowOrDefault(provider)
            aiConfigDao.upsert(
                row.copy(
                    model = model.trim().ifEmpty { provider.defaultModel },
                    baseUrl = baseUrl.trim().ifEmpty { provider.defaultBaseUrl },
                    cloudProject = if (cloudProject == null) row.cloudProject else cloudProject.trim().takeIf { it.isNotEmpty() },
                ),
            )
            val keyChanged = !apiKey.isNullOrBlank()
            if (keyChanged) {
                secretStore.put(AiSecretKeys.apiKey(provider), apiKey!!.trim())
                keyVersion.value++
            }
            audit.record(
                AuditAction.CONFIGURACAO, newValue = "${provider.label} · ${model.trim().ifEmpty { provider.defaultModel }}",
                details = "Configuração do provedor salva" + if (keyChanged) " (chave atualizada no cofre)" else "",
            )
        }
    }

    override suspend fun clearApiKey(provider: AiProviderType) {
        requireConfigureIa()
        secretStore.remove(AiSecretKeys.apiKey(provider))
        keyVersion.value++
        audit.record(AuditAction.CONFIGURACAO, newValue = provider.label, details = "Chave de API removida do cofre")
    }

    override suspend fun testConnection(provider: AiProviderType): Result<String> = withContext(Dispatchers.IO) {
        requireConfigureIa()
        if (provider == AiProviderType.MOCK) {
            return@withContext Result.failure(IllegalStateException("Escolha um provedor real e configure sua chave."))
        }
        val row = aiConfigDao.get(provider)
        if (!credentials.isConfigured(provider)) {
            val message = if (row?.authMode == AiAuthMode.OAUTH && provider.supportsOAuth) {
                "Entre com sua conta Google antes de testar."
            } else {
                "Configure a chave de API${if (provider == AiProviderType.CUSTOM) ", a URL e o modelo" else ""} antes de testar."
            }
            return@withContext Result.failure(AiProviderException(message))
        }
        val model = row?.model?.takeUnless { it.isBlank() || it in LEGACY_DEFAULT_MODELS } ?: provider.defaultModel
        val started = System.currentTimeMillis()
        runCatching {
            gateway.provider(provider).summarize("Teste de conexão do LicitaIA. Responda apenas: OK.", 1)
        }.fold(
            onSuccess = {
                val ms = System.currentTimeMillis() - started
                audit.record(AuditAction.CONFIGURACAO, newValue = provider.label, details = "Teste de conexão OK (${ms} ms)")
                val via = if (row?.authMode == AiAuthMode.OAUTH && provider.supportsOAuth) " via conta Google" else ""
                Result.success("Conexão OK com ${provider.label} (modelo $model)$via em ${ms} ms.")
            },
            onFailure = { error ->
                audit.record(AuditAction.CONFIGURACAO, result = AuditResult.FALHA, newValue = provider.label, reason = error.message, details = "Teste de conexão falhou")
                Result.failure(error)
            },
        )
    }
}

/** Ponte entre core-ai e as configurações persistidas (DataStore + Room + sessão). */
@Singleton
class AiSettingsSourceImpl @Inject constructor(
    private val settings: SettingsRepositoryImpl,
    private val aiConfigDao: AiConfigDao,
    private val holder: SessionHolder,
) : AiSettingsSource {
    override suspend fun activeProvider(): AiProviderType = settings.current().activeAiProvider

    override suspend fun companyPreferredProvider(): AiProviderType? = holder.current?.activeCompany?.preferredAi

    override suspend fun endpoint(type: AiProviderType): AiEndpoint {
        val row = aiConfigDao.get(type)
        return AiEndpoint(
            model = row?.model?.takeUnless { it.isBlank() || it in LEGACY_DEFAULT_MODELS } ?: type.defaultModel,
            baseUrl = row?.baseUrl?.ifBlank { type.defaultBaseUrl } ?: type.defaultBaseUrl,
            authMode = if (type.supportsOAuth && row?.authMode == AiAuthMode.OAUTH) AiAuthMode.OAUTH else AiAuthMode.API_KEY,
            cloudProject = row?.cloudProject?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

/** Modelos padrão antigos (descontinuados pelos provedores): exibidos como o padrão atual até o usuário editar. */
private val LEGACY_DEFAULT_MODELS = setOf("gpt-4o-mini", "gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash")
