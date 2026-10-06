package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer

/**
 * Regras puras (sem Android) do navegador interno de portais:
 * allowlist de hosts por portal, URL inicial, identificação de página de login e
 * heurística de "sessão aberta" — tudo sem ler, guardar ou injetar credenciais.
 *
 * A heurística usa a URL (host/caminho), o fato de existirem cookies para o domínio e, no máximo,
 * um booleano calculado DENTRO da página por [contentProbeScript] ("o texto visível contém os
 * marcadores de sessão encerrada deste portal?"). Nenhum texto, campo de formulário, valor de input
 * ou cookie é lido pelo app.
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
        /**
         * Marcadores de "sessão encerrada" no texto visível (SPA que mostra o aviso na própria área logada).
         * Cada regra é uma lista de grupos: TODOS os grupos precisam bater e, em cada grupo, QUALQUER termo.
         * Termos já normalizados ([normalizeText]: minúsculos, sem acentos, espaços simples).
         */
        val expiredContentRules: List<List<List<String>>> = emptyList(),
    )

    private val commonMarkers = listOf("login", "signin", "sign-in", "logon", "/sso", "/auth", "autenticacao", "autenticação")

    /**
     * Avisos explícitos de sessão encerrada, comuns a todos os portais com login. Não inclui "faça login"/"entrar",
     * que aparecem em qualquer página pública.
     */
    private val genericExpiredRules = listOf(
        listOf(listOf("sua sessao expirou", "sessao expirada", "sessao encerrada por inatividade")),
    )

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
            // cnetmobile (SPA) mostra, sem mudar de URL: "Não autorizado — Sua sessão pode ter expirado ou suas
            // permissões não permitem o acesso ao recurso solicitado… Efetuar Login".
            expiredContentRules = listOf(
                listOf(listOf("nao autorizado"), listOf("sessao pode ter expirado", "efetuar login")),
            ) + genericExpiredRules,
        ),
        Rules(
            portal = Portal.BLL,
            startUrl = "https://bllcompras.com/home/login",
            allowedDomains = listOf("bll.org.br", "bllcompras.com"),
            loginPathMarkers = commonMarkers + listOf("/acesso"),
            expiredContentRules = genericExpiredRules,
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
            expiredContentRules = genericExpiredRules,
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
            expiredContentRules = genericExpiredRules,
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
     * @param contentExpired resultado de [contentProbeScript] na página atual (o texto visível contém o aviso
     *        de sessão encerrada do portal). false quando não avaliado.
     * @param online há rede validada agora (NET_CAPABILITY_VALIDATED). Sem rede nada é concluído sobre a sessão.
     * @param loadFailed o carregamento desta página falhou (onReceivedError do main frame, HTTP ≥ 500, timeout).
     *        Página de erro, redirecionamento interrompido ou SPA sem conseguir falar com o servidor NÃO são
     *        sessão encerrada: a sessão continua salva e é reavaliada quando a página carregar de verdade.
     */
    fun evaluate(
        portal: Portal,
        url: String,
        hasCookies: Boolean,
        previousWasLoginPage: Boolean?,
        currentStatus: PortalConnectionStatus,
        contentExpired: Boolean = false,
        online: Boolean = true,
        loadFailed: Boolean = false,
    ): Signal {
        if (!isAllowed(portal, url)) return Signal.BLOCKED
        if (!rules(portal).requiresLogin) return Signal.NONE
        if (!canConclude(online, loadFailed)) return Signal.NONE
        val login = isLoginPage(portal, url)
        return when {
            // O portal devolveu a página de login depois de estarmos numa página normal → sessão caiu.
            // (Na primeira carga ou dentro do fluxo de SSO não há o que concluir.)
            login && currentStatus == PortalConnectionStatus.CONECTADO && previousWasLoginPage == false -> Signal.EXPIRED
            // Dentro do fluxo de login ("Acesse sua Conta", seleção de empresa...): nunca alerta, nem por conteúdo.
            login -> Signal.NONE
            // Área logada exibindo o aviso de sessão encerrada (SPA: mesmo host/URL da área logada).
            contentExpired && currentStatus == PortalConnectionStatus.CONECTADO -> Signal.EXPIRED
            // Já DESCONECTADO/EXPIRADA: o aviso não gera novo alerta — e não conta como login.
            contentExpired -> Signal.NONE
            // Área logada só é reconhecida ao vir da página de login com cookies: evita falso positivo
            // ao abrir a home pública (que também grava cookies de analytics).
            hasCookies && previousWasLoginPage == true && currentStatus != PortalConnectionStatus.CONECTADO -> Signal.CONNECTED
            else -> Signal.NONE
        }
    }

    /** Só se conclui algo sobre a sessão (conectada/expirada) com rede validada e página carregada sem erro. */
    fun canConclude(online: Boolean, loadFailed: Boolean): Boolean = online && !loadFailed

    /** HTTP do documento principal que indica falha do servidor/rede (não é resposta de sessão). */
    fun isServerFailure(httpStatus: Int): Boolean = httpStatus >= 500 || httpStatus == 408 || httpStatus == 429

    /**
     * Keep-alive: a sessão só é dada como encerrada após [EXPIRED_CONFIRMATIONS] probes seguidos indicando
     * EXPIRED, todos com rede validada antes e depois do carregamento. Qualquer probe inconclusivo/ok zera a contagem.
     */
    const val EXPIRED_CONFIRMATIONS = 2

    fun keepAliveConfirmsExpired(consecutiveExpired: Int): Boolean = consecutiveExpired >= EXPIRED_CONFIRMATIONS

    // ------------------------------------------------------------ conteúdo (marcadores)

    private val diacritics = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")

    /** Minúsculas, sem acentos, espaços colapsados — mesma normalização feita pelo [contentProbeScript]. */
    fun normalizeText(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(diacritics, "").lowercase().replace(whitespace, " ")

    /** Versão pura (testável) da checagem feita na página: o texto visível contém os marcadores do portal? */
    fun contentIndicatesExpired(portal: Portal, visibleText: String): Boolean {
        val r = rules(portal)
        if (!r.requiresLogin || r.expiredContentRules.isEmpty()) return false
        val t = normalizeText(visibleText)
        return r.expiredContentRules.any { rule -> rule.all { group -> group.any { term -> t.contains(term) } } }
    }

    /** true se o portal tem marcadores de conteúdo (só então o script é injetado). */
    fun hasContentMarkers(portal: Portal): Boolean = rules(portal).let { it.requiresLogin && it.expiredContentRules.isNotEmpty() }

    /**
     * Script para `evaluateJavascript`: calcula NA PÁGINA se o texto visível contém os marcadores e devolve
     * apenas `true`/`false` (mesma lógica de [contentIndicatesExpired]). Não lê formulários, inputs nem
     * cookies, não altera a página e nenhum texto sai dela.
     */
    fun contentProbeScript(portal: Portal): String {
        val rulesJs = rules(portal).expiredContentRules.joinToString(",", "[", "]") { rule ->
            rule.joinToString(",", "[", "]") { group -> group.joinToString(",", "[", "]") { term -> "'" + jsEscape(term) + "'" } }
        }
        return "(function(){try{var b=document.body;if(!b)return false;" +
            "var t=String(b.innerText||b.textContent||'').normalize('NFD').replace(/[\\u0300-\\u036f]/g,'').toLowerCase().replace(/\\s+/g,' ');" +
            "var R=" + rulesJs + ";" +
            "for(var i=0;i<R.length;i++){var ok=true;for(var j=0;j<R[i].length;j++){var g=R[i][j],any=false;" +
            "for(var k=0;k<g.length;k++){if(t.indexOf(g[k])>=0){any=true;break;}}if(!any){ok=false;break;}}if(ok)return true;}" +
            "return false;}catch(e){return false;}})()"
    }

    /** Resultado do `evaluateJavascript` do [contentProbeScript] ("true"/"false"/"null"). */
    fun parseProbeResult(raw: String?): Boolean = raw?.trim()?.trim('"') == "true"

    private fun jsEscape(s: String): String = s.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }

    // ------------------------------------------------------------ última página ("voltar para onde estava")

    /** Parâmetros nunca guardados (tokens, códigos de SSO, ids de sessão, senhas). */
    private val sensitiveParamNames = setOf(
        "code", "state", "nonce", "ticket", "sid", "session", "sessionid", "jsessionid", "phpsessid", "aspsessionid",
        "session_state", "authorization_id", "auth", "authorization", "key", "apikey", "api_key", "sig", "signature",
        "pwd", "pass", "otp", "assertion", "samlresponse", "samlrequest",
    )
    private val sensitiveParamFragments = listOf("token", "senha", "password", "secret", "credential")

    fun isSensitiveParam(name: String): Boolean {
        val n = name.lowercase().trim()
        return n in sensitiveParamNames || sensitiveParamFragments.any { n.contains(it) }
    }

    /**
     * URL que pode ser guardada como "onde o usuário estava": HTTPS, na allowlist, fora do fluxo de login,
     * no host da `homeUrl` (quando o portal tem uma), sem parâmetros/fragmento sensíveis nem `;jsessionid=`.
     * Mantém caminho + parâmetros não sensíveis (no cnetmobile, a URL inteira quando não há token). null = não guardar.
     */
    fun sanitizeResumeUrl(portal: Portal, url: String): String? {
        val r = rules(portal)
        if (!r.requiresLogin || !isAllowed(portal, url) || isLoginPage(portal, url)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val h = uri.host?.lowercase()?.trimEnd('.') ?: return null
        r.homeUrl?.let { home -> if (host(home) != h) return null }
        val path = (uri.rawPath ?: "").replace(Regex(";[^/]*"), "").ifEmpty { "/" }
        val query = uri.rawQuery?.split('&')
            ?.filter { it.isNotEmpty() && !isSensitiveParam(decode(it.substringBefore('='))) }
            ?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }
        val fragment = uri.rawFragment?.takeIf { frag ->
            frag.isNotEmpty() && frag.split('&', '?', '/').none { seg -> seg.contains('=') && isSensitiveParam(decode(seg.substringBefore('='))) }
        }
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        val result = buildString {
            append("https://").append(h).append(port).append(path)
            query?.let { append('?').append(it) }
            fragment?.let { append('#').append(it) }
        }
        return result.takeIf { it.length <= MAX_URL_LENGTH }
    }

    private const val MAX_URL_LENGTH = 2048

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /**
     * URL ao abrir o portal: com sessão CONECTADA, a última página da área logada (revalidada) ou a `homeUrl`;
     * caso contrário, a página oficial de login.
     */
    fun openUrl(portal: Portal, status: PortalConnectionStatus, lastUrl: String?): String {
        if (status != PortalConnectionStatus.CONECTADO) return startUrl(portal)
        return lastUrl?.let { sanitizeResumeUrl(portal, it) } ?: rules(portal).homeUrl ?: startUrl(portal)
    }

    /** URL recarregada pelo "Manter sessão ativa": última página da área logada ou `homeUrl`; null = nada a recarregar. */
    fun keepAliveUrl(portal: Portal, lastUrl: String?): String? =
        lastUrl?.let { sanitizeResumeUrl(portal, it) } ?: rules(portal).homeUrl

    /**
     * URLs base para as quais os cookies do portal devem ser expirados em "Sair do portal":
     * cada domínio da allowlist e seu "www.".
     */
    fun cookieUrls(portal: Portal): List<String> = rules(portal).allowedDomains.flatMap { d ->
        listOf("https://$d/", "https://www.$d/")
    }.distinct()
}
