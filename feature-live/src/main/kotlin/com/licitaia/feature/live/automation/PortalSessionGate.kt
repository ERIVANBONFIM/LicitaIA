package com.licitaia.feature.live.automation

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.feature.live.web.CertAutoLogin
import com.licitaia.feature.live.web.PortalWebPolicy
import com.licitaia.feature.live.web.PortalWebViewHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Resultado da última verificação REAL da sessão do Comprasnet (robô/busca chegaram à área logada ou caíram no login). */
data class PortalLoginCheck(val companyId: Long, val logged: Boolean, val at: Long, val reason: String? = null)

/** Selo "Logado/Deslogado" do Robô (puro): a última verificação real vence o status salvo quando é mais nova. */
object PortalLoginBadge {
    fun logged(status: PortalConnectionStatus?, lastLoginAt: Long?, check: PortalLoginCheck?): Boolean {
        if (check != null && check.at >= (lastLoginAt ?: 0L)) return check.logged
        return status == PortalConnectionStatus.CONECTADO
    }

    /** URL final de uma leitura que caiu fora da área logada (login, página pública, acesso negado). */
    fun isLoggedOutUrl(portal: Portal, url: String?): Boolean {
        val u = url ?: return false
        return PortalWebPolicy.isLoginPage(portal, u) || PortalWebPolicy.isPublicLanding(portal, u) || u.contains("acessoNegado", ignoreCase = true)
    }
}

/**
 * Leva a aba RETIDA do Comprasnet (a mesma sessão que o usuário usa) até onde o robô precisa, respeitando as regras de
 * entrada do portal ([PortalWebPolicy]):
 * - a aba precisa estar numa janela VISÍVEL (app aberto): sem isso o WebView não carrega → "abra o app";
 * - aba vazia → entrada oficial `loginPortal.asp?perfil=1` (nunca intro.htm/cnetmobile numa aba sem estado);
 * - deslogado → login automático com certificado SÓ se o usuário já escolheu um certificado para a empresa
 *   (senão: "Entre no Comprasnet em Portais");
 * - "Compras eletrônicas" SÓ pelo link do menu "Licitação e Dispensa (novo)" ([PortalWebViewHolder.openElectronicPurchases]);
 *   NUNCA `loadUrl` no cnetmobile.
 * Também guarda a última verificação real da sessão ([loginCheck]) e o "dono" da aba (robô × busca, um por vez).
 */
@Singleton
class PortalSessionGate @Inject constructor(
    private val holder: PortalWebViewHolder,
    private val auth: AuthRepository,
) {
    private val portal = Portal.COMPRAS_GOV

    sealed interface Result {
        data object Workspace : Result
        data object Electronic : Result
        data class NotLogged(val reason: String) : Result
        data class Failed(val reason: String) : Result
        /** O WebView não está numa janela visível (app fechado / tela apagada): não carrega. */
        data object AppNotVisible : Result
    }

    private val _loginCheck = MutableStateFlow<PortalLoginCheck?>(null)
    /** Última verificação real da sessão (robô/busca). */
    val loginCheck: StateFlow<PortalLoginCheck?> = _loginCheck.asStateFlow()

    fun reportLogin(companyId: Long, logged: Boolean, reason: String? = null) {
        _loginCheck.value = PortalLoginCheck(companyId, logged, System.currentTimeMillis(), reason)
    }

    private val tabOwner = AtomicReference<String?>(null)

    /** Reserva a aba retida para [owner] ("robô de proposta", "busca"...). null = ok; senão quem está usando. */
    fun claimTab(owner: String): String? = if (tabOwner.compareAndSet(null, owner)) null else tabOwner.get()

    fun releaseTab(owner: String) { tabOwner.compareAndSet(owner, null) }

    suspend fun entry(companyId: Long): PortalWebViewHolder.Entry = withContext(Dispatchers.Main) { holder.obtain(companyId, portal) }

    fun driver(companyId: Long): WebViewPageDriver = WebViewPageDriver({ holder.peek(companyId, portal)?.webView })

    suspend fun currentUrl(companyId: Long): String? = withContext(Dispatchers.Main) { runCatching { holder.peek(companyId, portal)?.webView?.url }.getOrNull() }

    /** Estaciona/retoma a aba retida e espera ela estar numa janela visível (até [waitMs]). */
    suspend fun ensureLive(companyId: Long, waitMs: Long = 6_000): Boolean {
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            if (withContext(Dispatchers.Main) { holder.prepareForAutomation(companyId, portal) }) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(500)
        }
    }

    /** A aba retida está na área logada (Comprasnet seguro/intro.htm ou SPA cnetmobile)? Sem navegar. */
    suspend fun isLoggedNow(companyId: Long): Boolean {
        val url = currentUrl(companyId) ?: return false
        if (!PortalWebPolicy.isLoggedArea(portal, url)) return false
        if (PortalWebPolicy.needsEntryFirst(portal, url)) {
            val state = PortalPageClassifier.classify(portal, driver(companyId).probe())
            return state == PageState.OK
        }
        return true
    }

    /** Garante a área logada (área de trabalho ou SPA). Pode abrir a entrada oficial e tentar o login com certificado. */
    suspend fun ensureLoggedArea(companyId: Long, allowAutoLogin: Boolean = true): Result {
        if (!ensureLive(companyId)) return Result.AppNotVisible
        val entry = entry(companyId)
        var url = currentUrl(companyId)
        if (url != null && PortalWebPolicy.isLoggedArea(portal, url)) {
            if (!PortalWebPolicy.needsEntryFirst(portal, url)) return Result.Workspace.also { reportLogin(companyId, true) }
            val state = PortalPageClassifier.classify(portal, driver(companyId).probe())
            if (state == PageState.OK) return Result.Electronic.also { reportLogin(companyId, true) }
        }
        // Aba vazia / fora da área logada / "Não autorizado": entrada oficial (ou área de trabalho, se já esteve logada).
        val target = PortalWebPolicy.reentryUrl(portal, entry.entryGate.reachedLoggedArea)
        withContext(Dispatchers.Main) { runCatching { entry.webView.loadUrl(PortalWebPolicy.safeLoadUrl(portal, target, entry.entryGate.reachedLoggedArea)) } }
        url = waitForUrl(companyId, 45_000) { u -> PortalWebPolicy.isLoggedArea(portal, u) || PortalWebPolicy.isLoginPage(portal, u) || PortalWebPolicy.isPublicLanding(portal, u) }
        if (url == null && !withContext(Dispatchers.Main) { holder.peek(companyId, portal)?.let(holder::isLive) == true }) return Result.AppNotVisible
        if (url != null && PortalWebPolicy.isLoggedArea(portal, url) && !PortalWebPolicy.needsEntryFirst(portal, url)) {
            reportLogin(companyId, true)
            return Result.Workspace
        }
        if (PortalLoginBadge.isLoggedOutUrl(portal, url)) reportLogin(companyId, false, "a leitura caiu no login do portal")
        if (!allowAutoLogin) return Result.NotLogged("Entre no Comprasnet em Portais para continuar.")
        val cnpj = auth.session.value?.activeCompany?.cnpj?.filter(Char::isDigit).orEmpty()
        if (cnpj.length != 14 || !holder.hasRememberedCertificate(companyId, portal)) {
            return Result.NotLogged("Entre no Comprasnet em Portais (o login automático exige um certificado já escolhido para a empresa).")
        }
        return when (val outcome = holder.autoRelogin(companyId, portal, cnpj)) {
            CertAutoLogin.Outcome.Success, CertAutoLogin.Outcome.NotNeeded -> Result.Workspace.also { reportLogin(companyId, true) }
            is CertAutoLogin.Outcome.Stopped -> Result.NotLogged("Login automático parou (${outcome.reason.name.lowercase()}): entre no Comprasnet em Portais.")
        }
    }

    /** Abre "Compras eletrônicas" pelo menu do portal (aba já na área de trabalho) e espera a SPA montar. */
    suspend fun ensureElectronic(companyId: Long, allowAutoLogin: Boolean = true): Result {
        when (val r = ensureLoggedArea(companyId, allowAutoLogin)) {
            Result.Electronic -> return r
            Result.Workspace -> Unit
            else -> return r
        }
        val entry = entry(companyId)
        var url = currentUrl(companyId)
        if (!PortalWebPolicy.canGoToElectronicPurchases(portal, url)) {
            // Numa página do Comprasnet seguro sem o menu: volta à área de trabalho (a aba já esteve logada).
            withContext(Dispatchers.Main) { runCatching { entry.webView.loadUrl(PortalWebPolicy.reentryUrl(portal, true)) } }
            url = waitForUrl(companyId, 30_000) { PortalWebPolicy.canGoToElectronicPurchases(portal, it) }
                ?: return Result.Failed("Não foi possível voltar à área de trabalho do Comprasnet.")
        }
        // Menu "Compras" → "Licitação e Dispensa (novo)" (/assinadas/dispensa_eletronica.asp → "Você está sendo
        // redirecionado ao módulo de Dispensas e Licitações Eletrônicas" → SPA com o token). O frame do menu monta
        // depois do onPageFinished: algumas tentativas.
        var clicked = false
        repeat(6) {
            if (clicked) return@repeat
            clicked = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont -> holder.openElectronicPurchases(entry) { ok -> if (cont.isActive) cont.resume(ok) } }
            }
            if (!clicked) delay(1_500)
        }
        if (!clicked) return Result.Failed("Link “Licitação e Dispensa (novo)” não encontrado no menu do Comprasnet.")
        waitForUrl(companyId, 40_000) { PortalWebPolicy.needsEntryFirst(portal, it) }
            ?: return Result.Failed("Compras eletrônicas não abriu (tempo esgotado).")
        // A SPA pode mostrar "Não autorizado" depois da primeira renderização.
        delay(2_500)
        val state = PortalPageClassifier.classify(portal, driver(companyId).probe())
        return if (state == PageState.OK) {
            reportLogin(companyId, true)
            Result.Electronic
        } else {
            if (state == PageState.LOGGED_OUT || state == PageState.UNAUTHORIZED) reportLogin(companyId, false, state.label)
            Result.NotLogged("Compras eletrônicas: ${state.label}.")
        }
    }

    private suspend fun waitForUrl(companyId: Long, timeoutMs: Long, ok: (String) -> Boolean): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val u = currentUrl(companyId)
            val loading = withContext(Dispatchers.Main) { runCatching { holder.peek(companyId, portal)?.webView?.progress ?: 100 }.getOrDefault(100) }
            if (u != null && loading >= 100 && ok(u)) return u
            delay(700)
        }
        return null
    }

    companion object {
        /** Aviso padrão quando o app não está aberto na tela (o WebView do portal não carrega sem janela). */
        const val OPEN_APP_MESSAGE = "Abra o LicitaIA (com a tela ligada): sem o app aberto o portal não carrega e o robô não consegue operar."
    }
}
