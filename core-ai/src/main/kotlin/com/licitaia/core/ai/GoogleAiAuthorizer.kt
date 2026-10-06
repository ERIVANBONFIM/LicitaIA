package com.licitaia.core.ai

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.licitaia.ai.api.AiProviderException
import com.licitaia.core.security.SecretStore
import com.licitaia.domain.model.AiProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Resultado de uma etapa do fluxo "Entrar com conta Google" para a IA. */
sealed interface GoogleAiAuthResult {
    /** Autorização concedida; o token já está no cofre. [account] é o e-mail (só exibição) ou null se não obtido. */
    data class Granted(val account: String?) : GoogleAiAuthResult
    /** O Google precisa mostrar a tela de consentimento/seleção de conta: a UI lança este intent. */
    data class NeedsResolution(val pendingIntent: PendingIntent) : GoogleAiAuthResult
    data object Cancelled : GoogleAiAuthResult
    data class Failure(val message: String) : GoogleAiAuthResult
}

/**
 * Autorização OAuth 2.0 da conta Google do aparelho para a API Gemini
 * (doc oficial: https://ai.google.dev/gemini-api/docs/oauth), via Google Identity Services
 * (`Identity.getAuthorizationClient`). Escopo `cloud-platform` + `email` (só para exibir a conta).
 *
 * O token de acesso dura ~1 h e é guardado **cifrado no Keystore** com o instante de expiração
 * ([OAuthTokenCache]). Na chamada, se expirado, pede-se um novo token sem UI (consentimento já dado);
 * se o Google exigir interação, a chamada falha com uma mensagem pedindo para entrar novamente.
 * Nenhum token é logado, auditado ou devolvido à UI.
 */
@Singleton
class GoogleAiAuthorizer @Inject constructor(
    @ApplicationContext private val context: Context,
    secrets: SecretStore,
    client: OkHttpClient,
    private val json: Json,
) {
    private val http = client
    private val cache = OAuthTokenCache(
        secrets = secrets,
        tokenKey = AiSecretKeys.oauthToken(AiProviderType.GEMINI),
        expiryKey = AiSecretKeys.oauthExpiry(AiProviderType.GEMINI),
    )

    /** Há consentimento registrado (token no cofre, mesmo que expirado — a renovação é silenciosa). */
    suspend fun hasAuthorization(): Boolean = cache.exists()

    /**
     * Passo 1 (UI): tenta autorizar sem interação. Se o Google precisar mostrar a tela de contas/consentimento,
     * devolve [GoogleAiAuthResult.NeedsResolution] para a UI lançar o `PendingIntent`.
     */
    suspend fun begin(): GoogleAiAuthResult = try {
        val result = authorizeTask().await()
        val intent = result.pendingIntent
        when {
            !result.hasResolution() -> storeAndDescribe(result)
            intent != null -> GoogleAiAuthResult.NeedsResolution(intent)
            else -> GoogleAiAuthResult.Failure("O Google não devolveu a tela de autorização.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        apiFailure(e)
    } catch (e: Exception) {
        GoogleAiAuthResult.Failure("Não foi possível iniciar a autorização Google.")
    }

    /** Passo 2 (UI): conclui a partir do resultado da Activity lançada em [begin]. */
    suspend fun complete(resultCode: Int, data: Intent?): GoogleAiAuthResult {
        if (resultCode != Activity.RESULT_OK || data == null) return GoogleAiAuthResult.Cancelled
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            if (result.hasResolution()) {
                GoogleAiAuthResult.Failure("A autorização não foi concluída. Tente novamente.")
            } else {
                storeAndDescribe(result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            apiFailure(e)
        } catch (e: Exception) {
            GoogleAiAuthResult.Failure("Não foi possível concluir a autorização Google.")
        }
    }

    /**
     * Token para a chamada à API. Usa o do cofre se ainda válido; senão renova sem UI.
     * Lança [AiProviderException] se o Google exigir nova interação do usuário.
     */
    internal suspend fun accessToken(): String {
        cache.valid()?.let { return it }
        if (!cache.exists()) {
            throw AiProviderException("Conta Google não autorizada. Toque em 'Entrar com conta Google' em Configurações > IA.")
        }
        return refreshSilently()
    }

    /** Descarta o token atual (ex.: servidor respondeu 401) e tenta obter outro sem UI. */
    internal suspend fun refreshSilently(): String {
        cache.invalidate()
        val result = try {
            authorizeTask().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiProviderException(EXPIRED_MESSAGE, e)
        }
        val token = result.accessToken?.takeIf { !result.hasResolution() && it.isNotBlank() }
            ?: throw AiProviderException(EXPIRED_MESSAGE)
        cache.store(token)
        return token
    }

    /**
     * Revoga o consentimento (endpoint oficial `https://oauth2.googleapis.com/revoke`, melhor esforço) e
     * apaga o token do cofre. A versão atual do Play Services não expõe revogação programática do
     * AuthorizationClient; sem rede a revogação remota é ignorada e o usuário pode removê-la em
     * https://myaccount.google.com/permissions.
     */
    suspend fun revoke() {
        val token = cache.valid()
        cache.clear()
        if (token == null) return
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(REVOKE_URL)
                    .post(FormBody.Builder().add("token", token).build())
                    .build()
                http.newCall(request).execute().use { }
            }
        }
    }

    // ---------------------------------------------------------------------------------- internos

    private fun authorizeTask(): Task<AuthorizationResult> {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(CLOUD_PLATFORM_SCOPE), Scope(Scopes.EMAIL)))
            .build()
        return Identity.getAuthorizationClient(context).authorize(request)
    }

    private suspend fun storeAndDescribe(result: AuthorizationResult): GoogleAiAuthResult {
        val token = result.accessToken?.takeIf { it.isNotBlank() }
            ?: return GoogleAiAuthResult.Failure("O Google não devolveu um token de acesso.")
        if (result.grantedScopes.none { it == CLOUD_PLATFORM_SCOPE }) {
            return GoogleAiAuthResult.Failure("A permissão para a API do Google Cloud (Gemini) não foi concedida.")
        }
        cache.store(token)
        val account = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: fetchEmail(token)
        return GoogleAiAuthResult.Granted(account?.trim()?.lowercase())
    }

    /** E-mail via endpoint OpenID oficial; só exibição. Falha silenciosa → conta sem e-mail. */
    private suspend fun fetchEmail(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(USERINFO_URL).header("Authorization", "Bearer $token").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                (json.parseToJsonElement(body) as? JsonObject)?.str("email")
            }
        }.getOrNull()
    }

    private fun apiFailure(e: ApiException): GoogleAiAuthResult = when (e.statusCode) {
        CommonStatusCodes.CANCELED -> GoogleAiAuthResult.Cancelled
        CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT ->
            GoogleAiAuthResult.Failure("Sem conexão com o Google. Verifique a internet e tente novamente.")
        CommonStatusCodes.API_NOT_CONNECTED, CommonStatusCodes.SERVICE_DISABLED, CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED ->
            GoogleAiAuthResult.Failure("O Google Play Services não está disponível ou atualizado neste aparelho.")
        CommonStatusCodes.DEVELOPER_ERROR ->
            GoogleAiAuthResult.Failure("Cliente OAuth Android não configurado para este pacote/assinatura no Google Cloud (erro 10).")
        else -> GoogleAiAuthResult.Failure("O Google recusou a autorização (código ${e.statusCode}).")
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

    companion object {
        const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        const val EXPIRED_MESSAGE = "Autorização Google expirada — toque em 'Entrar com conta Google' novamente."
        private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
    }
}
