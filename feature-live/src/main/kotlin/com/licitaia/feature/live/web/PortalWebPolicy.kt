package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import java.net.URI

/**
 * Regras puras (sem Android) do navegador interno de portais:
 * allowlist de hosts por portal, URL inicial, identificação de página de login e
 * heurística de "sessão aberta" — tudo sem ler, guardar ou injetar credenciais.
 *
 * A heurística NÃO inspeciona conteúdo da página nem cookies individuais: usa só a URL
 * (host/caminho) e o fato de existirem cookies para o domínio.
 */
object PortalWebPolicy {

    /** Regras de um portal. Hosts são comparados por igualdade ou sufixo ".host". */
    data class Rules(
        val portal: Portal,
        /** Página oficial de login (ou pública, para portal sem login). */
        val startUrl: String,
        /** Domínios permitidos (o próprio e todos os subdomínios). */
        val allowedDomains: List<String>,
        /** Hosts inteiramente dedicados a autenticação (qualquer caminho = página de login). */
        val loginHosts: List<String> = emptyList(),
        /** Trechos de caminho (minúsculos) que identificam página de login/SSO. */
        val loginPathMarkers: List<String> = emptyList(),
        /** false = portal público (PNCP): nunca marca sessão. */
        val requiresLogin: Boolean = true,
        /**
         * Área de trabalho para onde o app leva o usuário UMA vez, logo após detectar o login
         * (ex.: Compras.gov.br → "Compras eletrônicas" do fornecedor). null = fica onde o portal deixar.
         */
        val homeUrl: String? = null,
    )

    private val commonMarkers = listOf("login", "signin", "sign-in", "logon", "/sso", "/auth", "autenticacao", "autenticação")

    private val rules: Map<Portal, Rules> = listOf(
        Rules(
            portal = Portal.PNCP,
            startUrl = "https://pncp.gov.br/app/editais",
            allowedDomains = listOf("pncp.gov.br"),
            requiresLogin = false,
        ),
        Rules(
            portal = Portal.COMPRAS_GOV,
            // Entrada oficial do fornecedor no Comprasnet: redireciona ao SSO gov.br (sso.acesso.gov.br, client_id=comprasnet.gov.br),
            // volta em /seguro/landing_sso.asp → seleção de empresa (/seguro/loginFornecedorSelecEmpresa.asp) → área logada.
            startUrl = "https://www.comprasnet.gov.br/seguro/loginPortal.asp?perfil=1",
            homeUrl = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra=",
            allowedDomains = listOf("gov.br"), // cobre www.gov.br, acesso.gov.br, sso.acesso.gov.br, comprasnet.gov.br, cnetmobile.estaleiro.serpro.gov.br
            loginHosts = listOf("acesso.gov.br", "sso.acesso.gov.br"),
            // "acesso" só como marcador de host (acesso.gov.br): no gov.br o caminho "acesso-a-informacao" é página pública.
            // landing_sso e a seleção de empresa fazem parte do fluxo de login (não são a área logada).
            loginPathMarkers = commonMarkers + listOf("loginportal", "/seguro/login", "landing_sso", "selecempresa"),
        ),
        Rules(
            portal = Portal.BLL,
            startUrl = "https://bllcompras.com/home/login",
            allowedDomains = listOf("bll.org.br", "bllcompras.com"),
            loginPathMarkers = commonMarkers + listOf("/acesso"),
        ),
        Rules(
            portal = Portal.LICITANET,
            // Confirmado (2026-10): o botão "Entrar" da home licitanet.com.br leva a https://portal.licitanet.com.br/login
            // (HTTP 200, "LICITANET | Entrar"). A página pode encaminhar ao SSO https://licita-sso.licitanet.com.br/login (HTTP 200).
            // A área do fornecedor fica em portal.licitanet.com.br.
            startUrl = "https://portal.licitanet.com.br/login",
            allowedDomains = listOf("licitanet.com.br"), // cobre portal.licitanet.com.br e licita-sso.licitanet.com.br
            loginHosts = listOf("licita-sso.licitanet.com.br"),
            loginPathMarkers = commonMarkers + listOf("/acesso"),
        ),
        Rules(
            portal = Portal.PORTAL_COMPRAS_PUBLICAS,
            // Confirmado (2026-10): "Fazer Login" da home → https://operacao.portaldecompraspublicas.com.br/18/loginext/
            // que redireciona ao Keycloak https://iam.secure.portaldecompraspublicas.com.br/realms/Portal/protocol/openid-connect/auth
            // (HTTP 200, "Entrar em Portal") e volta em /18/loginext/oAuth/ (ainda fluxo de login) → área logada em operacao.*.
            startUrl = "https://operacao.portaldecompraspublicas.com.br/18/loginext/",
            allowedDomains = listOf("portaldecompraspublicas.com.br"), // cobre www., operacao. e iam.secure.
            loginHosts = listOf("iam.secure.portaldecompraspublicas.com.br"),
            loginPathMarkers = commonMarkers + listOf("/acesso", "loginext"),
        ),
    ).associateBy { it.portal }

    fun rules(portal: Portal): Rules = rules.getValue(portal)

    fun startUrl(portal: Portal): String = rules(portal).startUrl

    /**
     * URL para onde levar o usuário logo após o login ser detectado em [url], ou null se não há
     * destino configurado ou se já está no host de destino.
     */
    fun postLoginRedirect(portal: Portal, url: String): String? {
        val home = rules(portal).homeUrl ?: return null
        val homeHost = host(home) ?: return null
        return if (host(url) == homeHost) null else home
    }

    /** Host (minúsculo) da URL ou null se inválida. */
    fun host(url: String): String? = runCatching { URI(url).host?.lowercase()?.trimEnd('.') }.getOrNull()?.takeIf { it.isNotBlank() }

    fun isHttps(url: String): Boolean = runCatching { URI(url).scheme?.equals("https", ignoreCase = true) == true }.getOrDefault(false)

    private fun hostMatches(host: String, domain: String): Boolean {
        val d = domain.lowercase()
        return host == d || host.endsWith(".$d")
    }

    /** true se a URL é HTTPS e o host pertence à allowlist do portal. */
    fun isAllowed(portal: Portal, url: String): Boolean {
        if (!isHttps(url)) return false
        val h = host(url) ?: return false
        return rules(portal).allowedDomains.any { hostMatches(h, it) }
    }

    /** true se a URL é uma página de login/SSO do portal (host dedicado ou caminho com marcador). */
    fun isLoginPage(portal: Portal, url: String): Boolean {
        val r = rules(portal)
        if (!r.requiresLogin) return false
        val h = host(url) ?: return false
        if (r.loginHosts.any { hostMatches(h, it) }) return true
        val path = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("").lowercase()
        return r.loginPathMarkers.any { marker -> path.contains(marker) }
    }

    /** Resultado da avaliação de uma navegação concluída. */
    enum class Signal {
        /** Fora da allowlist: bloquear e oferecer navegador externo. */
        BLOCKED,
        /** Login detectado (saiu de página de login para área do portal com cookies). */
        CONNECTED,
        /** Estava CONECTADO e o portal devolveu a página de login. */
        EXPIRED,
        /** Nada a registrar. */
        NONE,
    }

    /**
     * Heurística de sessão. Pura e idempotente.
     * @param hasCookies CookieManager.getCookie(url) não vazio para esta URL.
     * @param previousWasLoginPage a URL anterior (nesta aba) era página de login/SSO do portal;
     *        null = primeira navegação da aba (a URL inicial costuma ser a própria página de login).
     * @param currentStatus status persistido no app.
     */
    fun evaluate(
        portal: Portal,
        url: String,
        hasCookies: Boolean,
        previousWasLoginPage: Boolean?,
        currentStatus: PortalConnectionStatus,
    ): Signal {
        if (!isAllowed(portal, url)) return Signal.BLOCKED
        if (!rules(portal).requiresLogin) return Signal.NONE
        val login = isLoginPage(portal, url)
        return when {
            // O portal devolveu a página de login depois de estarmos numa página normal → sessão caiu.
            // (Na primeira carga ou dentro do fluxo de SSO não há o que concluir.)
            login && currentStatus == PortalConnectionStatus.CONECTADO && previousWasLoginPage == false -> Signal.EXPIRED
            login -> Signal.NONE
            // Área logada só é reconhecida ao vir da página de login com cookies: evita falso positivo
            // ao abrir a home pública (que também grava cookies de analytics).
            hasCookies && previousWasLoginPage == true && currentStatus != PortalConnectionStatus.CONECTADO -> Signal.CONNECTED
            else -> Signal.NONE
        }
    }

    /**
     * URLs base para as quais os cookies do portal devem ser expirados em "Sair do portal":
     * cada domínio da allowlist e seu "www.".
     */
    fun cookieUrls(portal: Portal): List<String> = rules(portal).allowedDomains.flatMap { d ->
        listOf("https://$d/", "https://www.$d/")
    }.distinct()
}
