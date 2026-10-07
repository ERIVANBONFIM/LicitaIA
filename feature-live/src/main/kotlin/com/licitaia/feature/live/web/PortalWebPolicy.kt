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
         * Área de trabalho logada que pode ser aberta por `loadUrl` (ex.: Comprasnet `intro.htm`) — mas SÓ quando o WebView
         * já esteve na área logada nesta vida (ex.: voltar após "Não autorizado"). Sem sessão, `intro.htm` é um frameset
         * que redireciona a aba para o www.gov.br público: por isso um portal com área de trabalho sempre abre a aba sem
         * estado pela entrada oficial ([startUrl]). null = sem área de trabalho fixa. Nunca é um host de [entryOnlyHosts].
         */
        val workspaceUrl: String? = null,
        /**
         * Hosts da página pública institucional para onde o portal manda quem está SEM sessão (Compras.gov.br:
         * www.gov.br/compras, com o botão "Entrar"). Chegar nela vindo do portal = não logado.
         */
        val publicLandingHosts: List<String> = emptyList(),
        /**
         * Texto (normalizado, em ordem de preferência) do link do menu do próprio portal que abre a SPA de compras
         * eletrônicas com o token (Comprasnet: "Compras → Licitação e Dispensa (novo)"). Vazio = sem atalho.
         */
        val electronicLinkTerms: List<String> = emptyList(),
        /** Termos que desclassificam um link candidato (ex.: itens "(legado)"). */
        val electronicLinkExclusions: List<String> = emptyList(),
        /**
         * Marcadores de "sessão encerrada" no texto visível (SPA que mostra o aviso na própria área logada).
         * Cada regra é uma lista de grupos: TODOS os grupos precisam bater e, em cada grupo, QUALQUER termo.
         * Termos já normalizados ([normalizeText]: minúsculos, sem acentos, espaços simples).
         */
        val expiredContentRules: List<List<List<String>>> = emptyList(),
        /**
         * Área logada explícita: prefixos "host/caminho" (minúsculos). Vazio = qualquer página da allowlist fora do
         * login conta como área logada. Com prefixos, SÓ elas (e nunca as páginas de login) indicam sessão aberta.
         */
        val loggedAreaPrefixes: List<String> = emptyList(),
        /**
         * Hosts de passagem entre o login e a área logada (ex.: www.comprasnet.gov.br/intro.htm): uma página pública
         * deles logo depois do login não "zera" o vínculo com o login. Só vale com [loggedAreaPrefixes].
         */
        val loginTransitHosts: List<String> = emptyList(),
        /**
         * Hosts que o app NUNCA abre por `loadUrl` (nem abertura, nem última URL, nem keep-alive, nem reentrada): a SPA
         * só recebe o token quando aberta pelo LINK do menu do próprio portal. Ex.: cnetmobile do Compras.gov.br.
         */
        val entryOnlyHosts: List<String> = emptyList(),
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
            // "Área de Trabalho do Fornecedor Brasileiro" (logada; menu Dados Cadastrais | Compras | SICAF | Contratos | Sair).
            workspaceUrl = "https://www.comprasnet.gov.br/intro.htm",
            // Menu "Compras" → "Licitação e Dispensa (novo)": abre o cnetmobile já com o token.
            electronicLinkTerms = listOf("licitacao e dispensa (novo)", "licitacao e dispensa"),
            electronicLinkExclusions = listOf("legado"),
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
            // Área logada: SPA do fornecedor (cnetmobile), a área de trabalho (intro.htm) e o Comprasnet seguro (exceto
            // login/seleção de empresa). www.gov.br (portal institucional, com botão "Entrar") nunca vale como sessão aberta.
            loggedAreaPrefixes = listOf(
                "cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/",
                "www.comprasnet.gov.br/intro.htm",
                "comprasnet.gov.br/intro.htm",
                "www.comprasnet.gov.br/seguro/",
                "comprasnet.gov.br/seguro/",
            ),
            loginTransitHosts = listOf("www.comprasnet.gov.br", "comprasnet.gov.br"),
            // Abrir o cnetmobile por URL (loadUrl) dá "Não autorizado": o token só vem pelo link do menu do Comprasnet.
            entryOnlyHosts = listOf("cnetmobile.estaleiro.serpro.gov.br"),
            // Sem sessão, intro.htm/cnetmobile mandam a aba para www.gov.br/compras/pt-br ("Portal de Compras", botão "Entrar").
            publicLandingHosts = listOf("www.gov.br", "gov.br"),
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

    /** O app pode abrir esta URL por `loadUrl`? Nunca para hosts de [Rules.entryOnlyHosts] (ex.: cnetmobile). */
    fun canLoadDirectly(portal: Portal, url: String): Boolean = !needsEntryFirst(portal, url)

    /**
     * Toda navegação decidida pelo app passa por aqui: se o destino é um host que só funciona pelo link do portal
     * (cnetmobile) — ou é a área de trabalho e o WebView ainda NÃO esteve na área logada nesta vida —, troca por
     * [reentryUrl] (área de trabalho só com [reachedLoggedArea]; senão a entrada oficial).
     */
    fun safeLoadUrl(portal: Portal, url: String, reachedLoggedArea: Boolean = false): String {
        val ok = canLoadDirectly(portal, url) && (reachedLoggedArea || !isWorkspaceUrl(portal, url))
        return if (ok) url else reentryUrl(portal, reachedLoggedArea)
    }

    /**
     * Para onde o app volta (keep-alive, "Não autorizado", reentrada): a área de trabalho só se o WebView já esteve na
     * área logada nesta vida; senão (WebView sem estado) SEMPRE a entrada oficial.
     */
    fun reentryUrl(portal: Portal, reachedLoggedArea: Boolean): String =
        rules(portal).workspaceUrl?.takeIf { reachedLoggedArea } ?: startUrl(portal)

    /** Área de trabalho logada que pode ser aberta por URL (null = o portal não tem). */
    fun workspaceUrl(portal: Portal): String? = rules(portal).workspaceUrl

    /** A URL é a área de trabalho do portal (com ou sem "www.", ignorando query/fragmento). */
    fun isWorkspaceUrl(portal: Portal, url: String): Boolean {
        val ws = rules(portal).workspaceUrl ?: return false
        fun key(u: String): String? {
            val h = host(u)?.removePrefix("www.") ?: return null
            val path = runCatching { URI(u).rawPath.orEmpty() }.getOrDefault("").lowercase().trimEnd('/')
            return "$h$path"
        }
        return key(url) != null && key(url) == key(ws)
    }

    /**
     * WebView sem estado (primeira navegação desta vida) abre SEMPRE a entrada oficial: portais com área de trabalho
     * que só funciona com a sessão da aba (Compras.gov.br).
     */
    fun requiresEntryOnFreshTab(portal: Portal): Boolean = rules(portal).let { it.requiresLogin && it.workspaceUrl != null }

    /** Página pública institucional (www.gov.br/compras…) para onde o portal manda quem não tem sessão. Nunca login. */
    fun isPublicLanding(portal: Portal, url: String): Boolean {
        val r = rules(portal)
        if (!r.requiresLogin || r.publicLandingHosts.isEmpty() || !isAllowed(portal, url)) return false
        val h = host(url) ?: return false
        return r.publicLandingHosts.any { it == h } && !isLoginPage(portal, url) && !isLoggedArea(portal, url)
    }

    /** Página do próprio portal (Comprasnet ou cnetmobile): chegar ao www.gov.br depois dela = sem sessão. */
    fun isPortalHostPage(portal: Portal, url: String): Boolean {
        val r = rules(portal)
        val h = host(url) ?: return false
        return isAllowed(portal, url) && (r.loginTransitHosts.any { it == h } || r.entryOnlyHosts.any { it == h })
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

    /**
     * true se a URL é página da área logada do portal: na allowlist, fora do login e — quando o portal declara
     * [Rules.loggedAreaPrefixes] — dentro de um desses prefixos. Portal público (PNCP) nunca tem área logada.
     */
    fun isLoggedArea(portal: Portal, url: String): Boolean {
        val r = rules(portal)
        if (!r.requiresLogin || !isAllowed(portal, url) || isLoginPage(portal, url)) return false
        if (r.loggedAreaPrefixes.isEmpty()) return true
        val h = host(url) ?: return false
        val path = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("").lowercase().ifEmpty { "/" }
        val target = "$h$path"
        return r.loggedAreaPrefixes.any { prefix -> target.startsWith(prefix) || "$target/" == prefix }
    }

    /**
     * Rastro da aba: a página [url] conta como "vinda do login" para a PRÓXIMA avaliação? Sim se é página de login;
     * ou se a anterior já vinha do login e esta é uma página pública de um host de passagem
     * ([Rules.loginTransitHosts], ex.: Comprasnet intro) — nunca www.gov.br.
     */
    fun carriesLoginFlag(portal: Portal, url: String, previous: Boolean?): Boolean {
        if (isLoginPage(portal, url)) return true
        if (previous != true) return false
        val r = rules(portal)
        if (r.loggedAreaPrefixes.isEmpty() || !isAllowed(portal, url) || isLoggedArea(portal, url)) return false
        val h = host(url) ?: return false
        return r.loginTransitHosts.any { it == h }
    }

    /**
     * Keep-alive: a página recarregada/aberta era da área logada e terminou numa página pública do portal (nem área
     * logada, nem login) — ex.: cnetmobile devolvendo para www.gov.br/compras. Só para portais com área logada declarada.
     */
    fun leftLoggedArea(portal: Portal, finalUrl: String): Boolean {
        val r = rules(portal)
        if (!r.requiresLogin || r.loggedAreaPrefixes.isEmpty() || !isAllowed(portal, finalUrl)) return false
        return !isLoggedArea(portal, finalUrl) && !isLoginPage(portal, finalUrl)
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
            // Área logada só é reconhecida ao vir da página de login com cookies E numa URL da área logada declarada
            // (Compras.gov.br: cnetmobile/.../seguro ou comprasnet/seguro): evita falso positivo ao voltar do SSO para
            // a home pública (www.gov.br/compras, que também grava cookies de analytics e mostra "Entrar").
            hasCookies && previousWasLoginPage == true && currentStatus != PortalConnectionStatus.CONECTADO &&
                isLoggedArea(portal, url) -> Signal.CONNECTED
            else -> Signal.NONE
        }
    }

    // ------------------------------------------------------------ "Verificando sessão…" (aba sem estado)

    /** Selo da sessão enquanto a aba sem estado confirma o status persistido. */
    enum class SessionCheck {
        /** Nada pendente: o selo mostra o status persistido. */
        NONE,
        /** Status persistido CONECTADO, WebView vazio: aguardando a primeira página conclusiva. */
        VERIFYING,
        /** A verificação concluiu que não há sessão (login / www.gov.br público): "Faça login no portal". */
        LOGIN_REQUIRED,
    }

    /** Ao abrir a tela: status CONECTADO + WebView sem estado num portal que reabre pela entrada → "Verificando sessão…". */
    fun initialSessionCheck(portal: Portal, status: PortalConnectionStatus, freshTab: Boolean): SessionCheck =
        if (freshTab && status == PortalConnectionStatus.CONECTADO && requiresEntryOnFreshTab(portal)) SessionCheck.VERIFYING
        else SessionCheck.NONE

    /**
     * Primeira página conclusiva durante [SessionCheck.VERIFYING]: área logada (sem aviso) → aberta ([SessionCheck.NONE]);
     * EXPIRED ou www.gov.br público → [SessionCheck.LOGIN_REQUIRED]. Login/SSO intermediário: continua verificando (quem
     * conclui "login necessário" é a entrada que assentou no login — [EntryGate.onPage] → Expire).
     */
    fun resolveSessionCheck(portal: Portal, current: SessionCheck, url: String, signal: Signal, contentExpired: Boolean): SessionCheck {
        if (current != SessionCheck.VERIFYING) return current
        return when {
            signal == Signal.CONNECTED -> SessionCheck.NONE
            signal == Signal.EXPIRED || contentExpired -> SessionCheck.LOGIN_REQUIRED
            isPublicLanding(portal, url) -> SessionCheck.LOGIN_REQUIRED
            isLoggedArea(portal, url) -> SessionCheck.NONE
            else -> SessionCheck.VERIFYING
        }
    }

    /** Texto do selo de sessão na tela do portal. */
    fun sessionBadgeLabel(status: PortalConnectionStatus, check: SessionCheck): String = when {
        check == SessionCheck.VERIFYING && status == PortalConnectionStatus.CONECTADO -> "Verificando sessão…"
        check == SessionCheck.LOGIN_REQUIRED -> "Faça login no portal"
        else -> when (status) {
            PortalConnectionStatus.CONECTADO -> "Sessão aberta"
            PortalConnectionStatus.SESSAO_EXPIRADA -> "Sessão expirada"
            PortalConnectionStatus.MFA_PENDENTE -> "MFA pendente"
            PortalConnectionStatus.DESCONECTADO -> "Faça login no portal"
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
     * URL que pode ser guardada como "onde o usuário estava": HTTPS, na allowlist, na área logada, fora dos hosts que
     * só abrem pelo link do portal (cnetmobile NUNCA é guardado: reabri-lo por URL dá "Não autorizado"), sem
     * parâmetros/fragmento sensíveis nem `;jsessionid=`. null = não guardar.
     */
    fun sanitizeResumeUrl(portal: Portal, url: String): String? {
        val r = rules(portal)
        if (!r.requiresLogin || !isLoggedArea(portal, url) || !canLoadDirectly(portal, url)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val h = uri.host?.lowercase()?.trimEnd('.') ?: return null
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
     * URL ao abrir o portal (aba vazia = WebView sem estado). Portal com área de trabalho (Compras.gov.br): SEMPRE a
     * entrada oficial — nunca intro.htm nem a última URL, mesmo com status CONECTADO (a entrada leva à área logada se a
     * sessão gov.br ainda vale). Demais portais: com sessão CONECTADA, a última página da área logada; senão, o login.
     */
    fun openUrl(portal: Portal, status: PortalConnectionStatus, lastUrl: String?): String {
        if (status != PortalConnectionStatus.CONECTADO || requiresEntryOnFreshTab(portal)) return startUrl(portal)
        val url = lastUrl?.let { sanitizeResumeUrl(portal, it) } ?: rules(portal).workspaceUrl ?: startUrl(portal)
        return safeLoadUrl(portal, url)
    }

    /**
     * URL que o "Manter sessão ativa" abre quando a aba está vazia/fora da área logada: área de trabalho (se o portal
     * tem uma) ou a última página da área logada; null = nada a abrir. Nunca o cnetmobile.
     */
    fun keepAliveUrl(portal: Portal, lastUrl: String?): String? =
        rules(portal).workspaceUrl ?: lastUrl?.let { sanitizeResumeUrl(portal, it) }

    /**
     * URLs base para as quais os cookies do portal devem ser expirados em "Sair do portal":
     * cada domínio da allowlist e seu "www.".
     */
    fun cookieUrls(portal: Portal): List<String> = rules(portal).allowedDomains.flatMap { d ->
        listOf("https://$d/", "https://www.$d/")
    }.distinct()

    // ------------------------------------------------------------ keep-alive no WebView retido

    /** O que o "Manter sessão ativa" faz com o WebView retido da empresa/portal neste ciclo. */
    sealed interface KeepAliveAction {
        /** Usuário está vendo a página (tela em primeiro plano): não mexe — ele mesmo mantém a sessão viva. */
        data object Skip : KeepAliveAction
        /** Já está na área logada: `reload()` na MESMA aba (sessionStorage/memória da SPA sobrevivem). */
        data object Reload : KeepAliveAction
        /**
         * Aba vazia (processo recriado) ou fora da área logada: abre a entrada oficial (WebView sem estado), a área de
         * trabalho ([Rules.workspaceUrl], só se o WebView já esteve na área logada) ou a última página da área logada
         * (portais sem área de trabalho) — nunca um host de [Rules.entryOnlyHosts].
         * [viaEntry] = abriu a área de trabalho/entrada (não a página onde o usuário estava).
         */
        data class Load(val url: String, val viaEntry: Boolean = false) : KeepAliveAction
    }

    /**
     * Decide o "toque" do keep-alive. Nunca clica nem preenche nada: só `reload()` da página atual (se for área logada)
     * ou abre a área de trabalho (só se o WebView já esteve na área logada nesta vida) / a entrada oficial (aba sem
     * estado). Nunca navega para o cnetmobile.
     * @param currentUrl URL atual do WebView retido (null = WebView novo, ex.: processo foi morto).
     * @param visible a tela do portal está exibindo este WebView em primeiro plano agora.
     * @param fallbackUrl [keepAliveUrl] (área de trabalho ou última página da área logada).
     * @param reachedLoggedArea o WebView já esteve na área logada nesta vida ([EntryGate.reachedLoggedArea]).
     */
    fun keepAliveAction(
        portal: Portal,
        currentUrl: String?,
        visible: Boolean,
        fallbackUrl: String?,
        reachedLoggedArea: Boolean = false,
    ): KeepAliveAction {
        if (visible) return KeepAliveAction.Skip
        val url = currentUrl?.takeIf { it.isNotBlank() && it != "about:blank" }
        // Só recarrega a aba se ela está na ÁREA LOGADA (reload da MESMA página: não navega para lugar nenhum).
        if (url != null && isLoggedArea(portal, url)) return KeepAliveAction.Reload
        val candidate = fallbackUrl?.takeIf { isAllowed(portal, it) } ?: return KeepAliveAction.Skip
        val target = safeLoadUrl(portal, candidate, reachedLoggedArea).takeIf { isAllowed(portal, it) } ?: return KeepAliveAction.Skip
        val workspace = rules(portal).workspaceUrl
        return KeepAliveAction.Load(target, viaEntry = target == workspace || target == startUrl(portal))
    }

    // ------------------------------------------------------------ "Compras eletrônicas" (link do menu do portal)

    /** O portal tem o atalho "Compras eletrônicas" (link do próprio menu que abre a SPA com o token). */
    fun hasElectronicLink(portal: Portal): Boolean = rules(portal).let { it.requiresLogin && it.electronicLinkTerms.isNotEmpty() }

    /**
     * A aba está na área logada de um host de passagem (Comprasnet: intro.htm ou /seguro/ fora do login), onde o menu
     * "Compras → Licitação e Dispensa (novo)" existe. Só então o botão "Compras eletrônicas" aparece / o script roda.
     */
    fun canGoToElectronicPurchases(portal: Portal, url: String?): Boolean {
        if (url == null || !hasElectronicLink(portal) || !isLoggedArea(portal, url)) return false
        val h = host(url) ?: return false
        return rules(portal).loginTransitHosts.any { it == h }
    }

    /**
     * Versão pura (testável) da escolha feita na página por [electronicLinkScript]: entre os textos dos elementos
     * clicáveis, o índice do link "Licitação e Dispensa (novo)" (termos em ordem de preferência; ignora "(legado)";
     * entre empates, o texto mais curto). null = não encontrado.
     */
    fun pickElectronicLink(portal: Portal, texts: List<String>): Int? {
        val r = rules(portal)
        val norm = texts.map { normalizeText(it).trim() }
        for (term in r.electronicLinkTerms) {
            var best: Int? = null
            norm.forEachIndexed { i, t ->
                if (!t.contains(term) || r.electronicLinkExclusions.any { t.contains(it) }) return@forEachIndexed
                if (best == null || t.length < norm[best!!].length) best = i
            }
            if (best != null) return best
        }
        return null
    }

    /**
     * Script para `evaluateJavascript`: procura (no documento e em frames do mesmo domínio) o link do menu do portal
     * cujo texto normalizado contém os termos de [Rules.electronicLinkTerms] e dispara o `click()` DELE — mesmo que o
     * item de menu esteja oculto. Se o link abriria nova janela/aba (`target`), é redirecionado para a própria aba
     * (`_self`/`_top`); `window.open` é tratado pelo `onCreateWindow` do WebView. Sem link (HV Menu do Comprasnet),
     * carrega a URL do item do menu no frame de destino e reenvia o form do token em `_top`. Devolve só
     * 'clicked' | 'notfound'. Não lê inputs nem cookies.
     */
    fun electronicLinkScript(portal: Portal): String {
        val r = rules(portal)
        val terms = r.electronicLinkTerms.joinToString(",", "[", "]") { "'" + linkTermEscape(it) + "'" }
        val excl = r.electronicLinkExclusions.joinToString(",", "[", "]") { "'" + linkTermEscape(it) + "'" }
        return "(function(){try{" +
            "var N=function(s){return String(s||'').normalize('NFD').replace(/[\\u0300-\\u036f]/g,'').toLowerCase().replace(/\\s+/g,' ').trim();};" +
            "var W=" + terms + ",X=" + excl + ";" +
            "var D=[];var add=function(w,top){try{var d=w.document;if(d&&d.body)D.push({d:d,top:top});}catch(e){}" +
            "try{for(var i=0;i<w.frames.length;i++)add(w.frames[i],false);}catch(e){}};add(window,true);" +
            "for(var t=0;t<W.length;t++){for(var k=0;k<D.length;k++){" +
            "var es=D[k].d.querySelectorAll('a,[role=menuitem],button,[onclick]');var best=null,bl=1e9;" +
            "for(var i=0;i<es.length;i++){var e=es[i];var tx=N(e.innerText||e.textContent||e.getAttribute('title'));" +
            "if(tx.indexOf(W[t])<0)continue;var bad=false;for(var x=0;x<X.length;x++){if(tx.indexOf(X[x])>=0){bad=true;break;}}if(bad)continue;" +
            "if(tx.length<bl||(tx.length===bl&&e.tagName==='A'&&best&&best.tagName!=='A')){best=e;bl=tx.length;}}" +
            "if(best){var a=(best.tagName==='A')?best:((best.querySelector&&best.querySelector('a'))||(best.closest&&best.closest('a'))||best);" +
            "if(a.tagName==='A'){var tg=String(a.getAttribute('target')||'').toLowerCase();" +
            "if(D[k].top){if(tg&&tg!=='_self'&&tg!=='_top'&&tg!=='_parent')a.setAttribute('target','_self');}" +
            "else{a.setAttribute('target','_top');}}" +
            "a.click();return 'clicked';}}}" +
            // Comprasnet: o menu "Compras" é um HV Menu (arrays `MenuN_M` no frame nav, itens são <div> sem link nem
            // onclick). Mesmo destino do clique: carrega a URL do item no frame DocTargetFrame (main2). A página
            // intermediária (/assinadas/dispensa_eletronica.asp) faz submit do form `dispensaEletronica` (com o token)
            // em target=_blank — bloqueado como pop-up sem gesto; por isso o form é reenviado em _top.
            "var H=null,hw=null;for(var k=0;k<D.length&&!H;k++){var w=D[k].d.defaultView;if(!w)continue;" +
            "for(var key in w){if(!/^Menu\\d+(_\\d+)+$/.test(key))continue;var m=w[key];" +
            "if(!m||typeof m[0]!=='string'||typeof m[1]!=='string'||!m[1])continue;var tx=N(m[0]);var ok=false;" +
            "for(var t=0;t<W.length;t++){if(tx.indexOf(W[t])>=0){ok=true;break;}}" +
            "for(var x=0;x<X.length;x++){if(tx.indexOf(X[x])>=0)ok=false;}if(ok){H=m[1];hw=w;break;}}}" +
            "if(H){var tf=null;try{tf=window.frames[hw.DocTargetFrame||'main2'];}catch(e){}" +
            "if(!tf||!tf.location)tf=window;tf.location.href=H;var n=0;var iv=setInterval(function(){n++;" +
            "try{var f=tf.document&&tf.document.getElementById('dispensaEletronica');" +
            "if(f){clearInterval(iv);f.setAttribute('target','_top');f.submit();}}catch(e){}" +
            "if(n>60)clearInterval(iv);},250);return 'clicked';}" +
            "return 'notfound';}catch(e){return 'notfound';}})()"
    }

    /** Resultado do [electronicLinkScript]: true = clicou no link do portal. */
    fun parseElectronicLinkResult(raw: String?): Boolean = raw?.trim()?.trim('"') == "clicked"

    private fun linkTermEscape(s: String): String = s.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '(' || it == ')' }

    // ------------------------------------------------------------ entrada / "Não autorizado"

    /** Máximo de voltas automáticas à área de trabalho (após "Não autorizado") por abertura da tela. */
    const val MAX_REENTRIES = 1

    /**
     * Cliques automáticos no link "Licitação e Dispensa (novo)" por abertura da tela: 1 logo após chegar logado à
     * área de trabalho (sempre, com ou sem login automático) + 1 repetição após "Não autorizado".
     */
    const val MAX_AUTO_ELECTRONIC_CLICKS = 2

    /** A URL fica num host que o app nunca abre por `loadUrl` (ex.: cnetmobile). */
    fun needsEntryFirst(portal: Portal, url: String): Boolean {
        val h = host(url) ?: return false
        return rules(portal).entryOnlyHosts.any { it == h }
    }

    /** Página da entrada oficial (Comprasnet: loginPortal, landing, seleção de empresa, intro…): conta como "passou pela entrada". */
    fun isEntryFlowPage(portal: Portal, url: String): Boolean {
        val r = rules(portal)
        if (r.entryOnlyHosts.isEmpty() || !isAllowed(portal, url)) return false
        val h = host(url) ?: return false
        return r.loginTransitHosts.any { it == h }
    }

    /** Página pública de passagem da entrada (ex.: Comprasnet intro.htm): nem login, nem área logada. */
    fun isLoginTransitPage(portal: Portal, url: String): Boolean {
        val h = host(url) ?: return false
        return rules(portal).loginTransitHosts.any { it == h } && isAllowed(portal, url) &&
            !isLoginPage(portal, url) && !isLoggedArea(portal, url)
    }

    /**
     * Primeira navegação de um WebView (aba vazia, sem estado): portal com área de trabalho → SEMPRE a entrada oficial
     * (nunca intro.htm, nunca a última URL, nunca o cnetmobile); demais portais → [safeLoadUrl].
     */
    fun firstNavigationUrl(portal: Portal, target: String): String =
        if (requiresEntryOnFreshTab(portal)) startUrl(portal) else safeLoadUrl(portal, target)

    /**
     * Página que exige o usuário entrar no gov.br de novo: SSO do gov.br ("Acesse sua Conta") ou a seleção de perfil
     * do Comprasnet (a entrada não redirecionou sozinha → a sessão gov.br não está válida).
     */
    fun requiresUserLogin(portal: Portal, url: String): Boolean {
        if (!isLoginPage(portal, url)) return false
        return when (CertAutoLogin.detectStep(portal, url)) {
            CertAutoLogin.Step.GOVBR_LOGIN, CertAutoLogin.Step.PROFILE_SELECT -> true
            else -> false
        }
    }

    /** O que a tela faz em seguida no fluxo de entrada/reentrada. */
    sealed interface EntryAction {
        /** Nada a fazer. */
        data object None : EntryAction
        /**
         * Navegar por `loadUrl` — SÓ para a área de trabalho/entrada oficial (nunca o cnetmobile; a tela ainda passa
         * a URL por [safeLoadUrl]).
         */
        data class Navigate(val url: String) : EntryAction
        /** Na área de trabalho logada: clicar no link "Licitação e Dispensa (novo)" do menu do portal ([electronicLinkScript]). */
        data object OpenElectronicPurchases : EntryAction
        /**
         * A aba caiu na página pública (www.gov.br) vindo do portal / com sessão aberta = NÃO logado: marcar
         * SESSAO_EXPIRADA (se estava CONECTADO) e, se [url] ≠ null, UMA navegação para a entrada oficial (o login
         * automático, se ligado, segue a partir dela). [url] null = limite atingido (sem nova navegação).
         */
        data class LoginRequired(val url: String?) : EntryAction
        /** Login automático com certificado ligado e ainda não tentado nesta abertura: iniciar. */
        data object StartAutoLogin : EntryAction
        /** A área de trabalho caiu no login (sem login automático ou com ele tendo parado): marcar SESSAO_EXPIRADA. */
        data object Expire : EntryAction
    }

    /**
     * Estado de navegação automática de UM WebView (puro, testável), com contadores por abertura da tela:
     * - a aba vazia (WebView sem estado) abre SEMPRE a entrada oficial (nunca intro.htm, a última URL ou o cnetmobile);
     *   a área de trabalho só é alvo depois de o WebView ter estado na área logada ([reachedLoggedArea]);
     * - cair no www.gov.br público vindo do portal (ou com sessão aberta) = não logado → [EntryAction.LoginRequired]
     *   com UMA ida à entrada oficial por abertura;
     * - chegando logado à área de trabalho (ou reabrindo a tela com a aba nela), clica UMA vez no link "Licitação e
     *   Dispensa (novo)" — a SPA só recebe o token por esse link; já no cnetmobile, não navega;
     * - "Não autorizado" no cnetmobile → volta à área de trabalho (intro.htm, ainda logada) e repete o clique, no máximo
     *   [MAX_REENTRIES] vez por abertura; se a área de trabalho cair no login, segue o fluxo de login automático/expiração.
     */
    class EntryGate(private val portal: Portal) {
        /** A aba passou pela entrada oficial/Comprasnet nesta vida do WebView. */
        var passedEntry = false
            private set
        /** Voltas à área de trabalho após "Não autorizado" nesta abertura. */
        var reentries = 0
            private set
        /** Há uma ida à área de trabalho (inicial ou após "Não autorizado") em andamento, aguardando chegar à área logada. */
        var entering = false
            private set
        /** A volta foi pedida e a área de trabalho ainda não começou a carregar (ignora avisos repetidos da página antiga). */
        var awaitingEntryPage = false
            private set
        /** Login automático já iniciado por este fluxo nesta abertura. */
        var autoLoginTried = false
            private set
        /** Cliques automáticos no link de compras eletrônicas nesta abertura. */
        var autoElectronicClicks = 0
            private set
        /** Repetir o clique no link ao chegar à área de trabalho (pedido pelo "Não autorizado"). */
        var retryElectronic = false
            private set
        /**
         * O "ir para Compras eletrônicas" desta abertura já foi resolvido: clicou, ou a aba já estava/chegou no cnetmobile.
         * Daí em diante a navegação do usuário dentro da tela não é redirecionada (só a repetição após "Não autorizado").
         */
        var initialElectronicDone = false
            private set
        /**
         * O WebView esteve na área logada nesta vida (página da área logada concluída). Só então a área de trabalho
         * (intro.htm) pode ser alvo de `loadUrl`; sem isso, SEMPRE a entrada oficial. Volta a false ao cair no www.gov.br.
         */
        var reachedLoggedArea = false
            private set
        /** A navegação atual passou por uma página do próprio portal (Comprasnet/cnetmobile). */
        var cameFromPortal = false
            private set
        /** Idas automáticas à entrada após cair no www.gov.br nesta abertura (limite [MAX_REENTRIES]). */
        var landingRedirects = 0
            private set

        /** Nova abertura da tela: zera os contadores (o "passou pela entrada" é da vida do WebView). */
        fun onScreenOpened() {
            reentries = 0
            landingRedirects = 0
            autoLoginTried = false
            awaitingEntryPage = false
            entering = false
            autoElectronicClicks = 0
            retryElectronic = false
            initialElectronicDone = false
        }

        /**
         * Tela aberta/reanexada com o WebView retido JÁ numa página ([url]; [loading] = ainda carregando). Na área de
         * trabalho logada do Comprasnet (intro.htm, /seguro/…) e com a sessão aberta: clica no link "Licitação e
         * Dispensa (novo)" (1x por abertura). Já no cnetmobile: não navega (não interrompe o usuário, ex.: sala de
         * disputa). Página ainda carregando ou fora da área logada: nada agora — o [onPage] da chegada decide.
         * Nunca devolve navegação (loadUrl).
         */
        fun onScreenReattached(url: String?, loading: Boolean, sessionOpen: Boolean): EntryAction {
            val u = url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: return EntryAction.None
            if (needsEntryFirst(portal, u)) { initialElectronicDone = true; return EntryAction.None }
            // Aba parada no www.gov.br público com a sessão marcada como aberta: não está logada → entrada oficial.
            if (!loading && sessionOpen && isPublicLanding(portal, u)) return onPublicLanding(u, sessionOpen = true)
            if (loading || !sessionOpen || initialElectronicDone || !canGoToElectronicPurchases(portal, u)) return EntryAction.None
            if (autoElectronicClicks >= MAX_AUTO_ELECTRONIC_CLICKS) return EntryAction.None
            initialElectronicDone = true
            autoElectronicClicks++
            return EntryAction.OpenElectronicPurchases
        }

        /** onPageStarted de qualquer página permitida. */
        fun onPageStarted(url: String) {
            if (isEntryFlowPage(portal, url)) {
                passedEntry = true
                awaitingEntryPage = false
            }
            // "Veio do portal" = a página anterior desta navegação era do Comprasnet/cnetmobile (ex.: intro.htm → gov.br);
            // passar pelo SSO (usuário saindo do login pelo logo do gov.br) desfaz o vínculo.
            when {
                isPortalHostPage(portal, url) -> cameFromPortal = true
                !isPublicLanding(portal, url) -> cameFromPortal = false
            }
        }

        /**
         * onPageFinished de qualquer página permitida (também em segundo plano, pelo cliente base): página da área logada
         * → o WebView "tem estado"; página pública (www.gov.br) → perdeu (a volta passa pela entrada oficial).
         */
        fun onPageLoaded(url: String) {
            if (isPublicLanding(portal, url)) reachedLoggedArea = false
            else if (isLoggedArea(portal, url)) reachedLoggedArea = true
        }

        /** Para onde voltar agora (área de trabalho só se o WebView já esteve na área logada; senão a entrada). */
        fun reentryTarget(): String = reentryUrl(portal, reachedLoggedArea)

        /**
         * Primeira navegação da aba (WebView sem estado): portal com área de trabalho → SEMPRE a entrada oficial (nunca
         * intro.htm, a última URL ou o cnetmobile). [verify] = a sessão está marcada como aberta e a entrada vai
         * confirmá-la: a página de login dela, se assentar, conta como "login necessário" ([onPage]).
         */
        fun firstLoad(target: String, verify: Boolean = false): String {
            val first = firstNavigationUrl(portal, target)
            reachedLoggedArea = false
            if (first != target || verify) entering = true
            return first
        }

        /**
         * A aba concluiu [url]. Se é a página pública (www.gov.br) e a navegação veio do portal (Comprasnet/cnetmobile)
         * ou a sessão está marcada como aberta: NÃO está logada → [EntryAction.LoginRequired] com UMA ida à entrada
         * oficial por abertura ([MAX_REENTRIES]); depois disso, só marca (url null). Senão [EntryAction.None].
         */
        fun onPublicLanding(url: String, sessionOpen: Boolean): EntryAction {
            if (!isPublicLanding(portal, url)) return EntryAction.None
            val fromPortal = cameFromPortal
            cameFromPortal = false
            reachedLoggedArea = false
            if (!fromPortal && !sessionOpen) return EntryAction.None
            retryElectronic = false
            if (landingRedirects >= MAX_REENTRIES) {
                entering = false
                return EntryAction.LoginRequired(null)
            }
            landingRedirects++
            entering = true
            awaitingEntryPage = true
            return EntryAction.LoginRequired(startUrl(portal))
        }

        /** A página exibe "Não autorizado" (marcadores de conteúdo). */
        fun onUnauthorized(url: String, autoLoginOn: Boolean): EntryAction {
            if (!needsEntryFirst(portal, url)) return EntryAction.Expire
            if (awaitingEntryPage) return EntryAction.None // aviso repetido da página antiga: a volta já foi pedida
            if (reentries < MAX_REENTRIES) {
                reentries++
                entering = true
                awaitingEntryPage = true
                retryElectronic = hasElectronicLink(portal)
                // O cnetmobile só é aberto pelo link da área logada: estar nele = a aba já esteve na área logada.
                val hasState = reachedLoggedArea || isLoggedArea(portal, url)
                return EntryAction.Navigate(safeLoadUrl(portal, reentryUrl(portal, hasState), hasState))
            }
            retryElectronic = false
            if (autoLoginOn && !autoLoginTried) {
                autoLoginTried = true
                entering = true
                return EntryAction.StartAutoLogin
            }
            entering = false
            return EntryAction.Expire
        }

        /**
         * Página concluída. [settled] = a aba ficou nesta URL por alguns segundos (só então uma página de login conta
         * como "não conseguiu entrar"). [sessionOpen] = a sessão está aberta (CONNECTED agora ou já CONECTADO).
         * Área de trabalho logada: clique automático no link de compras eletrônicas (no máximo
         * [MAX_AUTO_ELECTRONIC_CLICKS] por abertura). Nunca devolve navegação para o cnetmobile.
         */
        fun onPage(
            url: String,
            settled: Boolean,
            autoLoginOn: Boolean,
            autoLoginRunning: Boolean,
            sessionOpen: Boolean = true,
        ): EntryAction {
            if (isLoggedArea(portal, url)) {
                entering = false
                awaitingEntryPage = false
                reachedLoggedArea = true
                // Chegou ao cnetmobile (pelo clique do app ou do usuário): nada mais a forçar nesta abertura.
                if (needsEntryFirst(portal, url)) { initialElectronicDone = true; return EntryAction.None }
                if (!canGoToElectronicPurchases(portal, url) || !sessionOpen) return EntryAction.None
                if (autoElectronicClicks >= MAX_AUTO_ELECTRONIC_CLICKS) { retryElectronic = false; return EntryAction.None }
                // Sempre (com ou sem login automático): 1 clique por abertura ao chegar logado; + repetição após "Não autorizado".
                val want = retryElectronic || !initialElectronicDone
                if (!want) return EntryAction.None
                retryElectronic = false
                initialElectronicDone = true
                autoElectronicClicks++
                return EntryAction.OpenElectronicPurchases
            }
            if (!entering) return EntryAction.None
            if (!settled || !requiresUserLogin(portal, url) || autoLoginRunning) return EntryAction.None
            if (autoLoginOn && !autoLoginTried) {
                autoLoginTried = true
                return EntryAction.StartAutoLogin
            }
            entering = false
            retryElectronic = false
            return EntryAction.Expire
        }

        /** O login automático parou (CAPTCHA, 2FA, erro…): se a entrada não chegou à área logada, expira. */
        fun onAutoLoginStopped(currentUrl: String?): EntryAction {
            if (!entering) return EntryAction.None
            if (currentUrl != null && isLoggedArea(portal, currentUrl)) return EntryAction.None
            entering = false
            retryElectronic = false
            return EntryAction.Expire
        }
    }

    // ------------------------------------------------------------ certificado digital (KeyChain)

    /** Certificado do cliente (A1 no KeyChain) só é oferecido a hosts HTTPS da allowlist do portal. */
    fun clientCertAllowed(portal: Portal, host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() && !it.contains('/') } ?: return false
        return isAllowed(portal, "https://$h/")
    }

    /** Chave (no SecretStore) do alias escolhido por empresa+host. Guarda só o ALIAS, nunca a chave privada. */
    fun clientCertAliasKey(companyId: Long, host: String): String = "portal.clientcert.$companyId.${host.trim().lowercase()}"

    /** Chave do índice de hosts com alias lembrado da empresa (para "Trocar certificado"). */
    fun clientCertIndexKey(companyId: Long): String = "portal.clientcert.hosts.$companyId"

    fun parseHostIndex(raw: String?): Set<String> =
        raw.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun formatHostIndex(hosts: Set<String>): String = hosts.sorted().joinToString(",")
}
