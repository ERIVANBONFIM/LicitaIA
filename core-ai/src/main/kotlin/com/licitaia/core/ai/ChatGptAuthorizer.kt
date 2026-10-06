package com.licitaia.core.ai

import android.content.Context
import android.content.Intent
import com.licitaia.core.security.SecretStore
import com.licitaia.domain.model.AiProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Estado do fluxo "Entrar com ChatGPT" (nunca contém tokens). */
sealed interface ChatGptLoginState {
    data object Idle : ChatGptLoginState
    /** Preparando (descoberta + servidor de retorno): "Abrindo o navegador…". */
    data class Starting(val companyId: Long) : ChatGptLoginState
    /** Servidor de retorno no ar; a UI deve abrir [authorizationUrl] numa Custom Tab. */
    data class OpenBrowser(val companyId: Long, val authorizationUrl: String, val deadlineMs: Long) : ChatGptLoginState
    /** Navegador aberto, esperando a autorização (até [deadlineMs]). */
    data class Waiting(val companyId: Long, val deadlineMs: Long) : ChatGptLoginState
    /** Conectado: a UI registra a conta e consome o resultado com [ChatGptAuthorizer.acknowledge]. */
    data class Connected(
        val companyId: Long,
        val account: ChatGptAccountInfo,
        val models: List<String>,
    ) : ChatGptLoginState
    data class Failed(val companyId: Long, val message: String, val cancelled: Boolean = false) : ChatGptLoginState
}

/**
 * Orquestra o "Entrar com ChatGPT" no Android: servidor de retorno em 127.0.0.1 num escopo de aplicação
 * (singleton, sobrevive à tela), Foreground Service curto ([ChatGptLoginService]) enquanto a Custom Tab está
 * aberta, tempo limite de 5 min, cancelamento e retorno do app ao primeiro plano no fim.
 */
@Singleton
class ChatGptAuthorizer @Inject constructor(
    @ApplicationContext private val context: Context,
    secrets: SecretStore,
    client: OkHttpClient,
    json: Json,
) {
    internal val store = ChatGptSessionStore(secrets, ChatGptOAuthClient(client, json), json)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ChatGptLoginState>(ChatGptLoginState.Idle)
    val state: StateFlow<ChatGptLoginState> = _state.asStateFlow()

    @Volatile private var attempt: ChatGptLoginAttempt? = null
    @Volatile private var job: Job? = null
    /** Motivo do cancelamento pedido (null = "Entrada cancelada."). */
    @Volatile private var cancelReason: String? = null

    suspend fun hasAuthorization(companyId: Long): Boolean = store.hasSession(companyId)

    suspend fun accountInfo(companyId: Long): ChatGptAccountInfo? = store.accountInfo(companyId)

    /**
     * Inicia a entrada para o escopo [companyId]: descobre os endpoints, abre o servidor de retorno, liga o
     * Foreground Service e publica [ChatGptLoginState.OpenBrowser]. Chame com o app em primeiro plano.
     */
    fun begin(companyId: Long) {
        if (job?.isActive == true) return
        cancelReason = null
        _state.value = ChatGptLoginState.Starting(companyId)
        job = appScope.launch {
            var started: ChatGptLoginAttempt? = null
            try {
                val request = store.authRequest(companyId)
                started = store.client.beginLogin(request, returnLink = returnLink())
                attempt = started
                val deadline = System.currentTimeMillis() + TIMEOUT_MS
                ChatGptLoginService.start(context) { cancel() }
                _state.value = ChatGptLoginState.OpenBrowser(companyId, started.authorizationUrl, deadline)
                val result = withTimeout(TIMEOUT_MS) { started.await() }
                store.save(companyId, result)
                val models = runCatching { store.client.listModels(result.tokens.accessToken) }.getOrDefault(emptyList())
                _state.value = ChatGptLoginState.Connected(
                    companyId,
                    store.accountInfo(companyId) ?: ChatGptAccountInfo(result.identity.email, result.identity.name, result.identity.planType, true),
                    models,
                )
            } catch (e: TimeoutCancellationException) {
                _state.value = ChatGptLoginState.Failed(companyId, "A entrada demorou mais de 5 minutos. Tente de novo.")
            } catch (e: CancellationException) {
                _state.value = cancelledState(companyId)
            } catch (e: ChatGptAuthException) {
                _state.value = if (e.code == "cancelled") cancelledState(companyId)
                else ChatGptLoginState.Failed(companyId, e.message ?: "Não foi possível entrar com o ChatGPT.")
            } catch (e: Exception) {
                _state.value = ChatGptLoginState.Failed(companyId, "Não foi possível entrar com o ChatGPT. Verifique a internet e tente de novo.")
            } finally {
                started?.cancel()
                attempt = null
                ChatGptLoginService.stop(context)
                if (started != null) bringAppToFront()
            }
        }
    }

    /**
     * Abre a autorização numa Chrome Custom Tab a partir da Activity [activityContext] (a aba fica na pilha do
     * app, e o retorno a fecha com CLEAR_TOP). Sem navegador compatível, tenta o navegador padrão; se nada
     * abrir, cancela a tentativa. Passa o estado a "aguardando autorização".
     */
    fun launchBrowser(activityContext: Context) {
        val s = _state.value as? ChatGptLoginState.OpenBrowser ?: return
        val uri = android.net.Uri.parse(s.authorizationUrl)
        val opened = runCatching {
            androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(androidx.browser.customtabs.CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(activityContext, uri)
        }.recoverCatching {
            activityContext.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.isSuccess
        if (opened) {
            _state.compareAndSet(s, ChatGptLoginState.Waiting(s.companyId, s.deadlineMs))
        } else {
            cancel("Nenhum navegador disponível para entrar com o ChatGPT.")
        }
    }

    /** Botão "Cancelar" (tela ou notificação), ou falha ao abrir o navegador. */
    fun cancel(reason: String? = null) {
        cancelReason = reason
        attempt?.cancel()
        job?.cancel()
    }

    private fun cancelledState(companyId: Long): ChatGptLoginState.Failed {
        val reason = cancelReason
        return ChatGptLoginState.Failed(companyId, reason ?: "Entrada cancelada.", cancelled = reason == null)
    }

    /** A UI consumiu o resultado final (conectado/erro). */
    fun acknowledge() {
        val s = _state.value
        if (s is ChatGptLoginState.Connected || s is ChatGptLoginState.Failed) _state.value = ChatGptLoginState.Idle
    }

    /** Token para a chamada à API (renovado com margem de 60 s). */
    internal suspend fun accessToken(companyId: Long): String = store.accessToken(companyId)

    /** Depois de um 401: renova uma vez e devolve o token novo. */
    internal suspend fun refreshAfterRejection(companyId: Long, rejected: String): String = store.refreshAfterRejection(companyId, rejected)

    /** "Desconectar": revoga o refresh token (melhor esforço) e apaga tudo do escopo. */
    suspend fun revoke(companyId: Long) = store.signOut(companyId)

    /** Lista os modelos da conta conectada (vazio se falhar). */
    suspend fun listModels(companyId: Long): List<String> =
        runCatching { store.client.listModels(store.accessToken(companyId)) }.getOrDefault(emptyList())

    /**
     * Link "Voltar ao LicitaIA" da página de retorno: intent URL do Chrome para o lançador do próprio pacote
     * (não é esquema próprio; só funciona com toque do usuário).
     */
    private fun returnLink(): String =
        "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=${context.packageName};end"

    /** A Custom Tab está na pilha do app: CLEAR_TOP|SINGLE_TOP a fecha e entrega o intent à MainActivity. */
    private fun bringAppToFront() {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            context.startActivity(launch)
        }
    }

    companion object {
        const val TIMEOUT_MS = 5L * 60 * 1000
        /** Provedor que usa este fluxo. */
        val PROVIDER = AiProviderType.OPENAI
    }
}
