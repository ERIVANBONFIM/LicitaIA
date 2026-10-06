package com.licitaia.core.ai

import com.licitaia.ai.api.AiProviderException
import com.licitaia.core.security.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** Sessão guardada no cofre (um único JSON cifrado, para trocar access/refresh/expiração juntos). */
@Serializable
internal data class StoredChatGptSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long,
    val scope: String = "",
    val idToken: String? = null,
    val clientId: String,
    val subject: String,
    val email: String? = null,
    val name: String? = null,
    val planType: String? = null,
)

/** Registro do app na conta (sem tokens de acesso): client_id emitido + dicas para entrar de novo. */
@Serializable
internal data class StoredChatGptRegistration(
    val clientId: String,
    val subject: String? = null,
    val email: String? = null,
    val idToken: String? = null,
)

/** O que a tela pode saber da conexão (nunca tokens). */
data class ChatGptAccountInfo(
    val email: String?,
    val name: String?,
    val planType: String?,
    /** `chatgpt.tokens.use.direct` concedido: o plano do ChatGPT pode ser usado pelo app. */
    val planShared: Boolean,
)

/**
 * Credenciais "Entrar com ChatGPT" por escopo de configuração (empresa ou padrão do aparelho), no [SecretStore]
 * (Android Keystore). Renova com margem de 60 s, serializando renovações por escopo; substitui
 * access/refresh/expiração de uma vez. Puro JVM para testes.
 */
class ChatGptSessionStore(
    private val secrets: SecretStore,
    internal val client: ChatGptOAuthClient,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val hostLock = Mutex()

    private fun lock(scope: Long) = locks.getOrPut(scope) { Mutex() }

    /** `ext_agent_host_id` desta instalação (gerado uma vez e persistido). */
    suspend fun hostId(): String = hostLock.withLock {
        secrets.get(AiSecretKeys.CHATGPT_HOST_ID)?.takeIf { it.startsWith("urn:uuid:") }
            ?: ChatGptOAuthClient.newHostId().also { secrets.put(AiSecretKeys.CHATGPT_HOST_ID, it) }
    }

    suspend fun hasSession(scope: Long): Boolean = secrets.contains(AiSecretKeys.chatGptSession(scope))

    internal suspend fun session(scope: Long): StoredChatGptSession? =
        secrets.get(AiSecretKeys.chatGptSession(scope))?.let { runCatching { json.decodeFromString<StoredChatGptSession>(it) }.getOrNull() }

    internal suspend fun registration(scope: Long): StoredChatGptRegistration? =
        secrets.get(AiSecretKeys.chatGptRegistration(scope))?.let { runCatching { json.decodeFromString<StoredChatGptRegistration>(it) }.getOrNull() }

    suspend fun accountInfo(scope: Long): ChatGptAccountInfo? = session(scope)?.let {
        ChatGptAccountInfo(it.email, it.name, it.planType, ChatGptOAuthClient.DIRECT_SCOPE in it.scope.split(' '))
    }

    /** Parâmetros da próxima autorização: client_id emitido antes (se houver), e-mail e id_token como dicas. */
    suspend fun authRequest(scope: Long): ChatGptAuthRequest {
        val session = session(scope)
        val registration = registration(scope)
        return ChatGptAuthRequest(
            hostId = hostId(),
            clientId = session?.clientId ?: registration?.clientId,
            loginHint = session?.email ?: registration?.email,
            idTokenHint = session?.idToken ?: registration?.idToken,
        )
    }

    suspend fun save(scope: Long, result: ChatGptLoginResult) = lock(scope).withLock {
        val stored = StoredChatGptSession(
            accessToken = result.tokens.accessToken,
            refreshToken = result.tokens.refreshToken,
            expiresAtMs = result.tokens.expiresAtMs,
            scope = result.grantedScopes.joinToString(" "),
            idToken = result.tokens.idToken,
            clientId = result.clientId,
            subject = result.identity.subject,
            email = result.identity.email,
            name = result.identity.name,
            planType = result.identity.planType,
        )
        secrets.put(AiSecretKeys.chatGptSession(scope), json.encodeToString(StoredChatGptSession.serializer(), stored))
        secrets.put(
            AiSecretKeys.chatGptRegistration(scope),
            json.encodeToString(
                StoredChatGptRegistration.serializer(),
                StoredChatGptRegistration(result.clientId, result.identity.subject, result.identity.email, result.tokens.idToken),
            ),
        )
    }

    /** Access token pronto para uso; renova se faltar menos de 60 s para vencer. */
    internal suspend fun accessToken(scope: Long): String {
        val current = session(scope) ?: throw AiProviderException(NOT_CONNECTED)
        if (!expiring(current)) return current.accessToken
        return lock(scope).withLock {
            val again = session(scope) ?: throw AiProviderException(NOT_CONNECTED)
            if (!expiring(again)) again.accessToken else refreshLocked(scope, again)
        }
    }

    /** Depois de um 401: renova uma vez (se outra chamada já renovou, usa o token novo). */
    internal suspend fun refreshAfterRejection(scope: Long, rejected: String): String = lock(scope).withLock {
        val current = session(scope) ?: throw AiProviderException(NOT_CONNECTED)
        if (current.accessToken != rejected && !expiring(current)) current.accessToken else refreshLocked(scope, current)
    }

    private fun expiring(s: StoredChatGptSession) = clock() + REFRESH_MARGIN_MS >= s.expiresAtMs

    private suspend fun refreshLocked(scope: Long, current: StoredChatGptSession): String {
        val refreshToken = current.refreshToken ?: run {
            secrets.remove(AiSecretKeys.chatGptSession(scope))
            throw AiProviderException(EXPIRED)
        }
        val tokens = try {
            client.refresh(current.clientId, refreshToken, current.idToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatGptAuthException) {
            // Só tokens comprovadamente inutilizáveis são apagados; falha de rede mantém a sessão.
            if (e.requiresSignIn) secrets.remove(AiSecretKeys.chatGptSession(scope))
            throw AiProviderException(if (e.requiresSignIn) EXPIRED else e.message ?: EXPIRED, e)
        } catch (e: Exception) {
            throw AiProviderException("Não foi possível renovar a sessão do ChatGPT. Verifique a internet e tente de novo.", e)
        }
        val updated = current.copy(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: refreshToken,
            expiresAtMs = tokens.expiresAtMs,
            scope = tokens.scope.ifBlank { current.scope },
            idToken = tokens.idToken ?: current.idToken,
        )
        secrets.put(AiSecretKeys.chatGptSession(scope), json.encodeToString(StoredChatGptSession.serializer(), updated))
        return updated.accessToken
    }

    /** "Desconectar": revoga o refresh token (melhor esforço) e apaga sessão e registro do escopo. */
    suspend fun signOut(scope: Long) = lock(scope).withLock {
        val current = session(scope)
        secrets.remove(AiSecretKeys.chatGptSession(scope))
        secrets.remove(AiSecretKeys.chatGptRegistration(scope))
        secrets.remove(AiSecretKeys.oauthExpiry(com.licitaia.domain.model.AiProviderType.OPENAI, scope))
        val refresh = current?.refreshToken ?: return@withLock
        runCatching { client.revoke(current.clientId, refresh) }
        Unit
    }

    companion object {
        const val REFRESH_MARGIN_MS = 60_000L
        const val NOT_CONNECTED = "Conta ChatGPT não conectada. Toque em \"Entrar com ChatGPT\" em Configurações > IA ou use uma chave de API."
        const val EXPIRED = "A sessão do ChatGPT venceu. Toque em \"Entrar com ChatGPT\" de novo em Configurações > IA."
    }
}
