package com.licitaia.core.ai

import com.licitaia.ai.api.AiProviderException
import com.licitaia.core.security.SecretStore
import com.licitaia.domain.model.AiAuthMode
import com.licitaia.domain.model.AiProviderType
import javax.inject.Inject
import javax.inject.Singleton

/** Modelo, endpoint e modo de autenticação configurados para um provedor. */
data class AiEndpoint(
    val model: String,
    val baseUrl: String,
    val authMode: AiAuthMode = AiAuthMode.API_KEY,
    /** Projeto Google Cloud para `x-goog-user-project` (só OAuth/Gemini). */
    val cloudProject: String? = null,
)

/**
 * Fonte das configurações de IA. Implementada em core-data (Room/DataStore) para que
 * core-ai não dependa da camada de dados.
 */
interface AiSettingsSource {
    /** Provedor global ativo. */
    suspend fun activeProvider(): AiProviderType
    /** Provedor preferido da empresa ativa; null = usar o global. */
    suspend fun companyPreferredProvider(): AiProviderType?
    suspend fun endpoint(type: AiProviderType): AiEndpoint
}

object AiSecretKeys {
    /** Nome da entrada do cofre que guarda a chave de API do provedor. */
    fun apiKey(type: AiProviderType): String = "ai.api_key.${type.name}"
    /** Token de acesso OAuth (curta duração) do provedor. */
    fun oauthToken(type: AiProviderType): String = "ai.oauth.access_token.${type.name}"
    /** Instante (epoch ms) em que o token OAuth expira. */
    fun oauthExpiry(type: AiProviderType): String = "ai.oauth.expires_at.${type.name}"
}

/**
 * Credencial resolvida no momento da chamada. Em [AiAuthMode.OAUTH] a [apiKey] é vazia e o
 * provedor obtém o token de acesso via [GoogleAiAuthorizer].
 */
internal data class ResolvedAi(
    val model: String,
    val baseUrl: String,
    val apiKey: String,
    val authMode: AiAuthMode = AiAuthMode.API_KEY,
    val cloudProject: String? = null,
)

/** Junta endpoint + credencial (do cofre) no momento da chamada. Nada disso é logado nem persistido fora do cofre. */
@Singleton
class AiCredentials @Inject constructor(
    private val settings: AiSettingsSource,
    private val secrets: SecretStore,
    private val googleAuth: GoogleAiAuthorizer,
) {
    suspend fun isConfigured(type: AiProviderType): Boolean {
        if (type == AiProviderType.MOCK) return true
        val endpoint = settings.endpoint(type)
        val hasCredential = when (endpoint.authMode) {
            AiAuthMode.OAUTH -> type.supportsOAuth && googleAuth.hasAuthorization()
            AiAuthMode.API_KEY -> !secrets.get(AiSecretKeys.apiKey(type)).isNullOrBlank()
        }
        if (!hasCredential) return false
        if (type == AiProviderType.CUSTOM) {
            return endpoint.baseUrl.isNotBlank() && endpoint.model.isNotBlank()
        }
        return true
    }

    internal suspend fun resolve(type: AiProviderType): ResolvedAi {
        val endpoint = settings.endpoint(type)
        val oauth = endpoint.authMode == AiAuthMode.OAUTH
        if (oauth && !type.supportsOAuth) {
            throw AiProviderException("${type.label} não oferece login com conta. Use uma chave de API em Configurações > IA.")
        }
        val key = if (oauth) "" else secrets.get(AiSecretKeys.apiKey(type))?.trim().orEmpty()
        if (!oauth && key.isEmpty()) {
            throw AiProviderException("Chave de API do provedor ${type.label} não configurada. Cadastre-a em Configurações > IA.")
        }
        if (oauth && !googleAuth.hasAuthorization()) {
            throw AiProviderException("Conta Google não autorizada para ${type.label}. Toque em 'Entrar com conta Google' em Configurações > IA.")
        }
        val model = endpoint.model.trim().ifEmpty { type.defaultModel }
        val baseUrl = endpoint.baseUrl.trim().ifEmpty { type.defaultBaseUrl }
        if (model.isEmpty()) throw AiProviderException("Informe o modelo do provedor ${type.label} em Configurações > IA.")
        if (baseUrl.isEmpty()) throw AiProviderException("Informe a URL base do provedor ${type.label} em Configurações > IA.")
        if (!baseUrl.startsWith("https://", ignoreCase = true)) {
            throw AiProviderException("A URL do provedor ${type.label} deve usar HTTPS.")
        }
        return ResolvedAi(
            model = model,
            baseUrl = baseUrl.trimEnd('/'),
            apiKey = key,
            authMode = endpoint.authMode,
            cloudProject = endpoint.cloudProject?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
