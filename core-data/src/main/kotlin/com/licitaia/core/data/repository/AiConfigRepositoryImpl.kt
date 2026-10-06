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
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.repository.AiConfigRepository
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.security.Permission
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

/**
 * Configuração de IA por (provedor, empresa). Leitura: linha da empresa ativa se existir, senão o padrão
 * do aparelho (companyId 0); a credencial do cofre é lida no mesmo escopo da linha encontrada.
 * Escrita: na empresa ativa por padrão, ou no padrão do aparelho com `deviceDefault = true`.
 * A demonstração nunca configura IA real (usa só a heurística local).
 */
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

    override fun observeConfigs(deviceDefault: Boolean): Flow<List<AiConfig>> =
        combine(aiConfigDao.observeAll(), keyVersion, holder.state) { rows, _, session ->
            val companyId = if (deviceDefault) AiSecretKeys.DEVICE_SCOPE else session?.activeCompany?.id ?: AiSecretKeys.DEVICE_SCOPE
            AiProviderType.entries.map { type ->
                val own = rows.firstOrNull { it.provider == type && it.companyId == companyId && companyId > 0 }
                val row = own ?: rows.firstOrNull { it.provider == type && it.companyId == AiSecretKeys.DEVICE_SCOPE }
                val scope = row?.companyId ?: AiSecretKeys.DEVICE_SCOPE
                val oauthMode = row?.authMode == AiAuthMode.OAUTH && type.supportsOAuth
                // Conta só é "autorizada" se o token ainda estiver no cofre (pode ter sido invalidado pelo Keystore).
                val authorized = oauthMode && row?.oauthAccount != null && secretStore.contains(AiSecretKeys.oauthToken(type, scope))
                AiConfig(
                    provider = type,
                    model = row?.model?.takeUnless { it.isBlank() || it in LEGACY_DEFAULT_MODELS } ?: type.defaultModel,
                    baseUrl = row?.baseUrl ?: type.defaultBaseUrl,
                    hasApiKey = type != AiProviderType.MOCK && secretStore.contains(AiSecretKeys.apiKey(type, scope)),
                    authMode = if (oauthMode) AiAuthMode.OAUTH else AiAuthMode.API_KEY,
                    oauthAccount = if (authorized) row?.oauthAccount else null,
                    cloudProject = row?.cloudProject?.takeIf { it.isNotBlank() },
                    companyScoped = own != null,
                )
            }
        }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------ escopo

    /** Escopo das alterações: padrão do aparelho (0) ou a empresa ativa. */
    private fun scopeOf(session: AuthSession, deviceDefault: Boolean): Long =
        if (deviceDefault) AiSecretKeys.DEVICE_SCOPE else session.activeCompany.id

    /**
     * Linha do escopo, ou um ponto de partida para alterações parciais: ao criar a configuração própria da
     * empresa, parte-se do padrão do aparelho (modelo/URL/modo), para não perder o que já estava configurado.
     * A credencial NÃO é copiada: cada escopo tem a sua no cofre.
     */
    private suspend fun rowOrDefault(provider: AiProviderType, scope: Long): AiConfigEntity =
        aiConfigDao.get(provider, scope)
            ?: aiConfigDao.get(provider, AiSecretKeys.DEVICE_SCOPE)?.copy(companyId = scope, oauthAccount = null)
            ?: AiConfigEntity(provider = provider, companyId = scope, model = provider.defaultModel, baseUrl = provider.defaultBaseUrl)

    private fun requireConfigureIa(): AuthSession {
        val session = requireNotNull(holder.current) { "Entre na sua conta." }
        check(!session.user.demo) { "A demonstração usa apenas a análise heurística local; a IA real não pode ser configurada." }
        access.requireCompany(session.activeCompany.id, Permission.CONFIGURAR_IA)
        return session
    }

    private fun scopeLabel(scope: Long): String = if (scope == AiSecretKeys.DEVICE_SCOPE) "padrão do aparelho" else "esta empresa"

    // ------------------------------------------------------------------ alterações

    override suspend fun setAuthMode(provider: AiProviderType, mode: AiAuthMode, deviceDefault: Boolean) {
        val scope = scopeOf(requireConfigureIa(), deviceDefault)
        require(mode == AiAuthMode.API_KEY || provider.supportsOAuth) { "${provider.label} não oferece login com conta para apps de terceiros." }
        withContext(Dispatchers.IO) {
            val row = rowOrDefault(provider, scope)
            val existed = aiConfigDao.get(provider, scope) != null
            if (existed && row.authMode == mode) return@withContext
            aiConfigDao.upsert(row.copy(authMode = mode))
            keyVersion.value++
            audit.record(
                AuditAction.CONFIGURACAO, previousValue = row.authMode.label, newValue = mode.label,
                details = "Modo de autenticação de ${provider.label} alterado (${scopeLabel(scope)})",
            )
        }
    }

    override suspend fun saveOAuth(provider: AiProviderType, account: String, cloudProject: String?, deviceDefault: Boolean) {
        val scope = scopeOf(requireConfigureIa(), deviceDefault)
        require(provider.supportsOAuth) { "${provider.label} não oferece login com conta para apps de terceiros." }
        withContext(Dispatchers.IO) {
            val row = rowOrDefault(provider, scope)
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
                details = "Conta Google autorizada para a IA (${scopeLabel(scope)})" + (cloudProject?.trim()?.takeIf { it.isNotEmpty() }?.let { " (projeto $it)" } ?: ""),
            )
        }
    }

    override suspend fun clearOAuth(provider: AiProviderType, deviceDefault: Boolean) {
        val scope = scopeOf(requireConfigureIa(), deviceDefault)
        withContext(Dispatchers.IO) {
            val row = aiConfigDao.get(provider, scope)
            val previous = row?.oauthAccount
            googleAuth.revoke(scope)
            if (row != null) aiConfigDao.upsert(row.copy(authMode = AiAuthMode.API_KEY, oauthAccount = null))
            keyVersion.value++
            audit.record(
                AuditAction.CONFIGURACAO, previousValue = previous, newValue = provider.label,
                details = "Conta Google desconectada da IA (token removido do cofre; ${scopeLabel(scope)})",
            )
        }
    }

    override fun observeActive(): Flow<AiProviderType> = settings.settings.map { it.activeAiProvider }.distinctUntilChanged()

    override suspend fun setActive(provider: AiProviderType) {
        requireConfigureIa()
        require(provider != AiProviderType.MOCK) { "IA simulada indisponível nesta entrega." }
        val previous = settings.current().activeAiProvider
        settings.update { it.copy(activeAiProvider = provider) }
        if (previous != provider) {
            audit.record(AuditAction.CONFIGURACAO, previousValue = previous.label, newValue = provider.label, details = "Provedor de IA ativo alterado")
        }
    }

    override suspend fun saveConfig(provider: AiProviderType, model: String, baseUrl: String, apiKey: String?, cloudProject: String?, deviceDefault: Boolean) {
        val scope = scopeOf(requireConfigureIa(), deviceDefault)
        withContext(Dispatchers.IO) {
            val row = rowOrDefault(provider, scope)
            aiConfigDao.upsert(
                row.copy(
                    model = model.trim().ifEmpty { provider.defaultModel },
                    baseUrl = baseUrl.trim().ifEmpty { provider.defaultBaseUrl },
                    cloudProject = if (cloudProject == null) row.cloudProject else cloudProject.trim().takeIf { it.isNotEmpty() },
                ),
            )
            val keyChanged = !apiKey.isNullOrBlank()
            if (keyChanged) secretStore.put(AiSecretKeys.apiKey(provider, scope), apiKey!!.trim())
            keyVersion.value++
            audit.record(
                AuditAction.CONFIGURACAO, newValue = "${provider.label} · ${model.trim().ifEmpty { provider.defaultModel }}",
                details = "Configuração do provedor salva (${scopeLabel(scope)})" + if (keyChanged) " (chave atualizada no cofre)" else "",
            )
        }
    }

    override suspend fun clearApiKey(provider: AiProviderType, deviceDefault: Boolean) {
        val scope = scopeOf(requireConfigureIa(), deviceDefault)
        secretStore.remove(AiSecretKeys.apiKey(provider, scope))
        keyVersion.value++
        audit.record(AuditAction.CONFIGURACAO, newValue = provider.label, details = "Chave de API removida do cofre (${scopeLabel(scope)})")
    }

    override suspend fun useDeviceDefault(provider: AiProviderType) {
        val session = requireConfigureIa()
        val scope = session.activeCompany.id
        withContext(Dispatchers.IO) {
            val row = aiConfigDao.get(provider, scope)
            if (row?.oauthAccount != null) googleAuth.revoke(scope)
            secretStore.remove(AiSecretKeys.apiKey(provider, scope))
            secretStore.remove(AiSecretKeys.oauthToken(provider, scope))
            secretStore.remove(AiSecretKeys.oauthExpiry(provider, scope))
            aiConfigDao.delete(provider, scope)
            keyVersion.value++
            audit.record(
                AuditAction.CONFIGURACAO, previousValue = row?.let { "${provider.label} · ${it.model}" }, newValue = provider.label,
                details = "Configuração própria da empresa removida; passa a usar o padrão do aparelho",
            )
        }
    }

    override suspend fun testConnection(provider: AiProviderType): Result<String> = withContext(Dispatchers.IO) {
        val session = requireConfigureIa()
        if (provider == AiProviderType.MOCK) {
            return@withContext Result.failure(IllegalStateException("Escolha um provedor real e configure sua chave."))
        }
        val row = aiConfigDao.resolve(provider, session.activeCompany.id)
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

    /** Demonstração: sempre heurística local (MOCK) — nunca gasta nem expõe uma chave real. */
    override suspend fun companyPreferredProvider(): AiProviderType? {
        val session = holder.current ?: return null
        if (session.user.demo || session.activeCompany.demo) return AiProviderType.MOCK
        return session.activeCompany.preferredAi
    }

    /** Configuração da empresa ativa, com fallback para o padrão do aparelho; o escopo encontrado vai em [AiEndpoint.companyId]. */
    override suspend fun endpoint(type: AiProviderType): AiEndpoint {
        val companyId = holder.current?.activeCompany?.id ?: AiSecretKeys.DEVICE_SCOPE
        val row = aiConfigDao.resolve(type, companyId)
        return AiEndpoint(
            model = row?.model?.takeUnless { it.isBlank() || it in LEGACY_DEFAULT_MODELS } ?: type.defaultModel,
            baseUrl = row?.baseUrl?.ifBlank { type.defaultBaseUrl } ?: type.defaultBaseUrl,
            authMode = if (type.supportsOAuth && row?.authMode == AiAuthMode.OAUTH) AiAuthMode.OAUTH else AiAuthMode.API_KEY,
            cloudProject = row?.cloudProject?.trim()?.takeIf { it.isNotEmpty() },
            companyId = row?.companyId ?: AiSecretKeys.DEVICE_SCOPE,
        )
    }
}

/** Modelos padrão antigos (descontinuados pelos provedores): exibidos como o padrão atual até o usuário editar. */
private val LEGACY_DEFAULT_MODELS = setOf("gpt-4o-mini", "gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash")
