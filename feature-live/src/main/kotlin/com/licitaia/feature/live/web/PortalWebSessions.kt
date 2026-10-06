package com.licitaia.feature.live.web

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.licitaia.domain.model.Portal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cookies e perfis do navegador interno de portais.
 *
 * Isolamento por empresa: quando o WebView do aparelho suporta `MULTI_PROFILE` (androidx.webkit),
 * cada empresa usa o perfil `licitaia-<companyId>` (cookies, storage e cache separados).
 * Fallback documentado: sem suporte, todas as empresas compartilham o perfil padrão do WebView
 * do app (ainda isolado dos navegadores do aparelho) — a tela mostra um aviso discreto.
 *
 * Nunca lê valores de cookies para outro fim que não expirá-los em "Sair do portal".
 *
 * Persistência da sessão:
 * - [flush] é chamado após cada navegação concluída (tela e probe do keep-alive) e ao sair da tela, para que
 *   cookies renovados pelo portal cheguem ao disco mesmo se a rede ou o app caírem em seguida.
 * - Nenhum código do app chama `removeSessionCookies`/`removeAllCookies`; cookies só são expirados em
 *   "Sair do portal" ([clearPortalCookies]), e apenas os do portal escolhido.
 * - Cookies de SESSÃO (sem Expires/Max-Age) e tokens de SPA em `sessionStorage` vivem no processo do WebView.
 *   Se o portal depende deles (ex.: SSO gov.br/cnetmobile), a sessão sobrevive enquanto o processo estiver vivo —
 *   é para isso que o "Manter sessão ativa" usa um serviço em primeiro plano. Se o Android encerrar o app, o
 *   portal pode exigir novo login; o app NÃO tenta persistir/recriar cookies manualmente (seria guardar
 *   credencial de sessão fora do WebView).
 * - Falta de internet NUNCA encerra a sessão: sem rede o app não conclui nada sobre o status
 *   ([PortalWebPolicy.canConclude]).
 */
object PortalWebSessions {

    fun profileName(companyId: Long) = "licitaia-$companyId"

    /** true se o WebView instalado suporta perfis múltiplos. */
    fun supportsProfiles(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /**
     * Associa o WebView ao perfil da empresa. Deve ser chamado antes de qualquer carregamento.
     * @return true se o perfil isolado foi aplicado; false = fallback para o perfil compartilhado.
     */
    fun attachProfile(webView: WebView, companyId: Long): Boolean {
        if (!supportsProfiles()) return false
        return runCatching {
            val name = profileName(companyId)
            ProfileStore.getInstance().getOrCreateProfile(name)
            WebViewCompat.setProfile(webView, name)
            true
        }.getOrDefault(false)
    }

    /** CookieManager do perfil da empresa (ou o padrão no fallback). */
    fun cookieManager(companyId: Long): CookieManager {
        if (supportsProfiles()) {
            runCatching { ProfileStore.getInstance().getProfile(profileName(companyId))?.cookieManager }
                .getOrNull()?.let { return it }
        }
        return CookieManager.getInstance()
    }

    /** Configuração padrão de cookies persistentes para o WebView do portal. */
    fun configure(webView: WebView, companyId: Long) {
        val cm = cookieManager(companyId)
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
    }

    /** Grava os cookies em disco (chamar em onPause / ao sair da tela). */
    fun flush(companyId: Long) {
        runCatching { cookieManager(companyId).flush() }
    }

    /** true se existe algum cookie para a URL (não lê o conteúdo). */
    fun hasCookies(companyId: Long, url: String): Boolean =
        runCatching { !cookieManager(companyId).getCookie(url).isNullOrBlank() }.getOrDefault(false)

    /**
     * "Sair do portal": expira todos os cookies dos domínios do portal (e de seus subdomínios conhecidos
     * pela URL atual) no perfil da empresa. Não toca em cookies de outros portais.
     * @param extraUrls URLs visitadas nesta sessão (ex.: URL atual) para alcançar subdomínios não listados.
     */
    suspend fun clearPortalCookies(companyId: Long, portal: Portal, extraUrls: List<String> = emptyList()) {
        withContext(Dispatchers.Main.immediate) {
            val cm = cookieManager(companyId)
            val urls = (PortalWebPolicy.cookieUrls(portal) + extraUrls.filter { PortalWebPolicy.isAllowed(portal, it) }).distinct()
            for (url in urls) {
                val host = PortalWebPolicy.host(url) ?: continue
                val names = runCatching { cm.getCookie(url) }.getOrNull()
                    ?.split(';')
                    ?.mapNotNull { it.substringBefore('=', "").trim().takeIf { n -> n.isNotEmpty() } }
                    ?.distinct()
                    .orEmpty()
                if (names.isEmpty()) continue
                // Cookies podem ter sido gravados no host exato ou em qualquer domínio pai (ex.: ".gov.br").
                val domains = parentDomains(host)
                for (name in names) {
                    for (domain in domains) {
                        runCatching { cm.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=$domain") }
                        runCatching { cm.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=$domain; Secure") }
                    }
                    runCatching { cm.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/") }
                }
            }
            // Não usamos removeAllCookies/removeSessionCookies: apagariam sessões de outros portais do mesmo perfil.
            runCatching { cm.flush() }
        }
    }

    /** host, e cada domínio pai com pelo menos dois rótulos (gov.br, acesso.gov.br...). */
    internal fun parentDomains(host: String): List<String> {
        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return listOf(host)
        return (0..parts.size - 2).map { i -> parts.drop(i).joinToString(".") }
    }
}
