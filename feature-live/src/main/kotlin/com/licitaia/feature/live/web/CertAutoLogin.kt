package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import java.net.URI

/**
 * "Entrar automaticamente com certificado digital" (opt-in por empresa+portal; hoje só Compras.gov.br).
 *
 * Regras puras (sem Android). O app apenas CLICA/SELECIONA elementos de NAVEGAÇÃO do login, localizados pelo texto
 * visível normalizado (minúsculas, sem acentos):
 * 1. `www.comprasnet.gov.br/seguro/loginPortal.asp` → expande "Fornecedor Brasileiro" e clica "Entrar com Gov.br";
 * 2. `sso.acesso.gov.br` → clica a opção "Seu certificado digital" (nunca "certificado digital em nuvem");
 *    o certificado é apresentado pelo `onReceivedClientCertRequest` com o alias já lembrado (sem diálogo);
 * 3. `loginFornecedorSelecEmpresa.asp` → marca o rádio cuja linha tem o CNPJ da empresa ativa (só dígitos) e
 *    clica "Confirmar"; sem CNPJ correspondente nada é clicado.
 *
 * Nunca digita CPF/senha, nunca lê valores digitados em campos (a única leitura de `value` é o RÓTULO de botões
 * `input[type=submit|button]`, para achar "Confirmar"), nunca contorna CAPTCHA. Para e devolve o controle ao usuário
 * em CAPTCHA/hCaptcha/reCAPTCHA visível, pedido de código/2FA/aprovação no app gov.br, página de erro, falha de
 * carga, etapa repetida ([MAX_RUNS_PER_STEP]) ou tempo esgotado.
 */
object CertAutoLogin {

    /** Portais com o fluxo mapeado. */
    fun supports(portal: Portal): Boolean = portal == Portal.COMPRAS_GOV

    enum class Step {
        /** "Acesse sua Conta / Selecione o perfil desejado" (Comprasnet). */
        PROFILE_SELECT,
        /** SSO gov.br: opções de login. */
        GOVBR_LOGIN,
        /** `certificado.sso.acesso.gov.br`: o WebView apresenta o certificado (KeyChain), nada a clicar. */
        CERTIFICATE,
        /** "Comprasnet 4.0 · Selecionar Empresa". */
        COMPANY_SELECT,
        /** Área logada do fornecedor. */
        LOGGED_IN,
        /**
         * cnetmobile mostrando "Não autorizado" (detectado pelo conteúdo, não pela URL): a única ação é NAVEGAR de volta
         * para a área de trabalho ([PortalWebPolicy.workspaceUrl], Comprasnet intro.htm) — nunca o cnetmobile por URL.
         * No máximo uma vez por tentativa.
         */
        UNAUTHORIZED,
        /** Qualquer outra página (redirecionamentos intermediários, landing_sso etc.). */
        OTHER,
    }

    /** Etapas em que o app executa um script de clique. */
    val ACTION_STEPS = setOf(Step.PROFILE_SELECT, Step.GOVBR_LOGIN, Step.COMPANY_SELECT)

    private fun isComprasnet(host: String) = host == "comprasnet.gov.br" || host == "www.comprasnet.gov.br"

    /** Etapa do login pela URL (host + caminho). Pura. */
    fun detectStep(portal: Portal, url: String): Step {
        if (!supports(portal) || !PortalWebPolicy.isAllowed(portal, url)) return Step.OTHER
        val host = PortalWebPolicy.host(url) ?: return Step.OTHER
        val path = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("").lowercase()
        return when {
            host == "certificado.sso.acesso.gov.br" -> Step.CERTIFICATE
            host == "sso.acesso.gov.br" && (path.isEmpty() || path == "/" || path.startsWith("/login")) -> Step.GOVBR_LOGIN
            isComprasnet(host) && path.contains("selecempresa") -> Step.COMPANY_SELECT
            isComprasnet(host) && path.contains("loginportal") -> Step.PROFILE_SELECT
            PortalWebPolicy.isLoggedArea(portal, url) -> Step.LOGGED_IN
            else -> Step.OTHER
        }
    }

    // ------------------------------------------------------------------ CNPJ

    /** CNPJ com ou sem máscara (14 dígitos). CPF (11 dígitos) não casa. */
    private val CNPJ_PATTERN = Regex("""\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}""")

    fun digits(text: String): String = text.filter { it in '0'..'9' }

    /** CNPJs (só dígitos) presentes no texto de uma linha. */
    fun cnpjsIn(text: String): List<String> = CNPJ_PATTERN.findAll(text).map { digits(it.value) }.filter { it.length == 14 }.toList()

    /** A linha mostra o CNPJ da empresa (comparação só por dígitos)? */
    fun rowMatchesCnpj(rowText: String, cnpj: String): Boolean {
        val target = digits(cnpj)
        return target.length == 14 && cnpjsIn(rowText).any { it == target }
    }

    /** Índice da primeira linha com o CNPJ da empresa; null = nenhuma (não clicar). */
    fun pickCompanyRow(rows: List<String>, cnpj: String): Int? = rows.indexOfFirst { rowMatchesCnpj(it, cnpj) }.takeIf { it >= 0 }

    // ------------------------------------------------------------------ marcadores (texto normalizado)

    /** Pedido de código/2FA/aprovação no app gov.br. Normalizados como [PortalWebPolicy.normalizeText]. */
    val MFA_MARKERS = listOf(
        "verificacao em duas etapas", "verificacao em 2 etapas", "autenticacao em duas etapas",
        "digite o codigo", "informe o codigo", "insira o codigo", "codigo de verificacao", "codigo enviado",
        "aprove o acesso", "aprovar o acesso", "autorize o acesso", "confirme no aplicativo", "aprovacao no aplicativo",
    )

    /** Página de erro do portal/SSO. */
    val ERROR_MARKERS = listOf(
        "ocorreu um erro", "erro inesperado", "erro interno", "pagina nao encontrada", "servico indisponivel",
        "acesso negado", "requisicao invalida",
        // Compras.gov.br: "Operacao nao realizada. Tente novamente mais tarde. (503)" (instabilidade do portal).
        "operacao nao realizada", "tente novamente mais tarde",
    )

    fun textIndicatesMfa(visibleText: String): Boolean = PortalWebPolicy.normalizeText(visibleText).let { t -> MFA_MARKERS.any { t.contains(it) } }

    fun textIndicatesError(visibleText: String): Boolean = PortalWebPolicy.normalizeText(visibleText).let { t -> ERROR_MARKERS.any { t.contains(it) } }

    /** Elemento com "captcha" no id/classe/src/título (hCaptcha, reCAPTCHA...). O selo passivo do reCAPTCHA v3 não é desafio. */
    fun looksLikeCaptcha(id: String?, className: String?, src: String?): Boolean {
        val cls = className.orEmpty().lowercase()
        if (cls.contains("grecaptcha-badge")) return false
        return listOf(id, className, src).any { it?.lowercase()?.contains("captcha") == true }
    }

    // ------------------------------------------------------------------ resultado dos scripts

    enum class ScriptResult { CLICKED, CAPTCHA, MFA, ERROR_PAGE, PORTAL_UNSTABLE, NO_COMPANY_MATCH, NOT_FOUND, UNKNOWN }

    fun parseResult(raw: String?): ScriptResult = when (raw?.trim()?.trim('"')) {
        "clicked" -> ScriptResult.CLICKED
        "captcha" -> ScriptResult.CAPTCHA
        "mfa" -> ScriptResult.MFA
        "error" -> ScriptResult.ERROR_PAGE
        "unstable" -> ScriptResult.PORTAL_UNSTABLE
        "nomatch" -> ScriptResult.NO_COMPANY_MATCH
        "notfound" -> ScriptResult.NOT_FOUND
        else -> ScriptResult.UNKNOWN
    }

    /** Por que o login automático parou (texto para o usuário e para a auditoria — sem URLs). */
    enum class StopReason(val userText: String, val auditText: String) {
        CAPTCHA("O portal pediu CAPTCHA.", "parou em CAPTCHA"),
        MFA("O gov.br pediu código ou aprovação no aplicativo.", "parou em pedido de código/aprovação (2FA)"),
        ERROR_PAGE("O portal respondeu com erro (pode estar instável). Tente entrar de novo em alguns minutos.", "falhou: página de erro do portal"),
        /** loginPortal.asp com "Operação não realizada… (503)" (após o retorno do gov.br): instabilidade do portal. */
        PORTAL_UNSTABLE(PortalInstability.USER_TEXT, "parou: Compras.gov.br instável (erro 503 do portal após o retorno do gov.br)"),
        LOAD_FAILED("A página não carregou.", "falhou: página não carregou"),
        LOOP("A mesma etapa se repetiu.", "falhou: etapa repetida (evitando laço)"),
        NO_COMPANY_MATCH("Nenhuma empresa da lista tem o CNPJ da empresa ativa no LicitaIA.", "parou: CNPJ da empresa ativa não está na lista do portal"),
        NOT_FOUND("Não encontrei o botão esperado nesta página.", "falhou: botão esperado não encontrado"),
        TIMEOUT("O login demorou demais.", "falhou: tempo esgotado"),
        SESSION_REJECTED("O portal não aceitou a sessão.", "falhou: área logada recusou a sessão"),
        NO_CERTIFICATE("Nenhum certificado escolhido para este portal.", "falhou: sem certificado lembrado"),
        BUSY("Você está usando o portal na tela.", "não executado: portal aberto na tela"),
    }

    sealed interface Outcome {
        /** Login concluído pelo fluxo automático. */
        data object Success : Outcome
        /** Já estava na área logada: nada foi feito. */
        data object NotNeeded : Outcome
        data class Stopped(val reason: StopReason) : Outcome
    }

    sealed interface Decision {
        data class Execute(val step: Step) : Decision
        data object Wait : Decision
        data object Success : Decision
        data object NotNeeded : Decision
        data class Stop(val reason: StopReason) : Decision
    }

    /** Execuções do script por etapa: a 1ª + 1 nova tentativa; na 3ª vez que a mesma etapa aparece, para. */
    const val MAX_RUNS_PER_STEP = 2

    /**
     * Estado de UMA tentativa automática (por abertura da tela ou por evento de reconexão). Puro e testável;
     * o [PortalWebViewHolder] alimenta com as páginas carregadas e os resultados dos scripts.
     */
    class Run {
        private val runs = mutableMapOf<Step, Int>()
        var actions = 0
            private set
        var finished = false
            private set

        /**
         * @param unstable a página é o 503 do portal ([PortalInstability]): para com [StopReason.PORTAL_UNSTABLE] — antes
         *        de contar a etapa (nunca vira "etapa repetida").
         */
        fun onPage(step: Step, loadFailed: Boolean = false, unstable: Boolean = false): Decision {
            if (finished) return Decision.Wait
            if (unstable) return stop(StopReason.PORTAL_UNSTABLE)
            if (loadFailed) return stop(StopReason.LOAD_FAILED)
            return when (step) {
                Step.LOGGED_IN -> { finished = true; if (actions > 0) Decision.Success else Decision.NotNeeded }
                Step.CERTIFICATE, Step.OTHER -> Decision.Wait
                Step.UNAUTHORIZED -> onUnauthorized()
                else -> {
                    val n = runs[step] ?: 0
                    if (n >= MAX_RUNS_PER_STEP) {
                        stop(StopReason.LOOP)
                    } else {
                        runs[step] = n + 1
                        actions++
                        Decision.Execute(step)
                    }
                }
            }
        }

        /** Navegou para a entrada oficial após "Não autorizado" nesta tentativa. */
        var reentered = false
            private set

        /**
         * A área logada exibe "Não autorizado": uma vez por tentativa, navega para a entrada oficial
         * ([Decision.Execute] com [Step.UNAUTHORIZED], sem script); na segunda, para com SESSION_REJECTED.
         */
        fun onUnauthorized(): Decision {
            if (reentered) return stop(StopReason.SESSION_REJECTED)
            reentered = true
            finished = false
            actions++
            return Decision.Execute(Step.UNAUTHORIZED)
        }

        fun onScriptResult(result: ScriptResult): Decision {
            if (finished) return Decision.Wait
            return when (result) {
                ScriptResult.CLICKED -> Decision.Wait
                ScriptResult.CAPTCHA -> stop(StopReason.CAPTCHA)
                ScriptResult.MFA -> stop(StopReason.MFA)
                ScriptResult.ERROR_PAGE -> stop(StopReason.ERROR_PAGE)
                ScriptResult.PORTAL_UNSTABLE -> stop(StopReason.PORTAL_UNSTABLE)
                ScriptResult.NO_COMPANY_MATCH -> stop(StopReason.NO_COMPANY_MATCH)
                ScriptResult.NOT_FOUND, ScriptResult.UNKNOWN -> stop(StopReason.NOT_FOUND)
            }
        }

        /** Tempo esgotado: sem nenhuma ação, encerra em silêncio (nada foi tentado). */
        fun onTimeout(): Decision {
            if (finished) return Decision.Wait
            return if (actions > 0) stop(StopReason.TIMEOUT) else { finished = true; Decision.NotNeeded }
        }

        fun stop(reason: StopReason): Decision {
            finished = true
            return Decision.Stop(reason)
        }
    }

    // ------------------------------------------------------------------ scripts (constantes revisáveis)

    private fun jsList(items: List<String>): String =
        items.joinToString(",", "[", "]") { "'" + it.filter { c -> c.isLetterOrDigit() || c == ' ' || c == '.' || c == '-' } + "'" }

    /**
     * Funções comuns injetadas antes de cada etapa:
     * N = normaliza texto; V = elemento visível; T = texto visível do elemento; F = elemento clicável (a/button/
     * role=button...) cujo texto contém `want` e nenhum de `not` (o mais específico); K = sobe até o clicável;
     * B = bloqueios (CAPTCHA visível, 2FA, erro) → 'captcha' | 'mfa' | 'error' | null.
     */
    val COMMON_JS: String = """
var N=function(s){return String(s||'').normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().replace(/\s+/g,' ').trim();};
var V=function(e){if(!e||!e.getBoundingClientRect)return false;var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;var s=window.getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';};
var T=function(e){return N(e&&(e.innerText||e.textContent));};
var CL='a,button,[role=button],[role=radio],[role=tab],summary,[aria-expanded],[onclick],label,input[type=submit],input[type=button]';
var K=function(e){if(!e)return e;var up=e.closest&&e.closest(CL);if(up)return up;var inner=e.querySelector&&e.querySelector('a,button,[role=button],input[type=submit],input[type=button]');return (inner&&V(inner))?inner:e;};
var F=function(root,want,not){var all=(root||document).querySelectorAll('a,button,[role=button],[role=tab],summary,[aria-expanded],[onclick],label,li,h2,h3,h4,h5,span,p,div');var best=null,bl=1e9;for(var i=0;i<all.length;i++){var e=all[i];if(!V(e))continue;var t=T(e);if(t.indexOf(want)<0)continue;var bad=false;for(var j=0;j<not.length;j++){if(t.indexOf(not[j])>=0){bad=true;break;}}if(bad)continue;if(t.length<bl){best=e;bl=t.length;}}return best?K(best):null;};
var B=function(){var cs=document.querySelectorAll('iframe,[id*=captcha i],[class*=captcha i],[data-sitekey]');for(var i=0;i<cs.length;i++){var e=cs[i];var id=String(e.id||''),cl=String((e.className&&e.className.baseVal!==undefined)?e.className.baseVal:(e.className||'')),src=String(e.getAttribute('src')||'')+' '+String(e.getAttribute('title')||'');if(cl.toLowerCase().indexOf('grecaptcha-badge')>=0)continue;var hit=(id+' '+cl+' '+src).toLowerCase().indexOf('captcha')>=0||e.hasAttribute('data-sitekey');if(hit&&V(e))return 'captcha';}var t=T(document.body);var M=${jsList(MFA_MARKERS)};for(var m=0;m<M.length;m++){if(t.indexOf(M[m])>=0)return 'mfa';}if(t.indexOf('operacao nao realizada')>=0||t.indexOf('(503)')>=0)return 'unstable';var E=${jsList(ERROR_MARKERS)};for(var k=0;k<E.length;k++){if(t.indexOf(E[k])>=0)return 'error';}return null;};
""".trimIndent()

    /**
     * Etapa 1 — perfil: expande o card "Fornecedor Brasileiro" (não o "Estrangeiro") e clica o "Entrar com Gov.br"
     * DESSE card (procura subindo a partir do título, sem passar de um bloco que contenha outro perfil).
     */
    val PROFILE_JS: String = """
// Mapa verificado no aparelho (loginPortal.asp?perfil=1): form#frmLogin; card Fornecedor Brasileiro =
// button.expand.fornecedor (onclick=mudaPerfilBotao(0)); botão Entrar = button.is-primary com onclick para
// sso.acesso.gov.br/authorize ... state=F (F = fornecedor brasileiro).
var go=document.querySelector('#frmLogin button.is-primary[onclick*="sso.acesso.gov.br/authorize"][onclick*="state=F"]');
if(go&&V(go)){go.click();return 'clicked';}
var ex=document.querySelector('#frmLogin button.expand.fornecedor');if(ex&&V(ex)&&!go){ex.click();return 'notfound';}
var h=F(document,'fornecedor brasileiro',['estrangeiro']);if(!h)return 'notfound';
var inCard=function(){var a=h;for(var k=0;k<8&&a;k++){if(k>0&&T(a).indexOf('fornecedor estrangeiro')>=0)return null;var bs=a.querySelectorAll('a,button,[role=button]');for(var j=0;j<bs.length;j++){if(T(bs[j]).indexOf('entrar com gov.br')>=0&&V(bs[j]))return bs[j];}a=a.parentElement;}return null;};
var b=inCard();if(b){b.click();return 'clicked';}
h.click();
var n=0;var tm=setInterval(function(){n++;var b2=inCard();if(b2){clearInterval(tm);b2.click();}else if(n>=20){clearInterval(tm);}},150);
return 'clicked';
""".trimIndent()

    /** Etapa 2 — gov.br: opção "Seu certificado digital" (exclui "em nuvem"). */
    val GOVBR_JS: String = """
// SSO gov.br (verificado no aparelho): <div id="cert-digital"><button id="login-certificate" type="submit" ...>
var o=document.getElementById('login-certificate');if(o&&!V(o))o=null;
if(!o)o=F(document,'certificado digital',['nuvem']);
if(!o){var ids=document.querySelectorAll('[id*=certificad i],[id*=certificate i],[class*=certificad i],[class*=certificate i],[href*=certificado i]');for(var i=0;i<ids.length;i++){var e=ids[i];var t=N(String(e.id||'')+' '+String(e.className||'')+' '+String(e.getAttribute('href')||'')+' '+T(e));if(t.indexOf('nuvem')>=0||t.indexOf('cloud')>=0)continue;if(V(e)){o=K(e);break;}}}
if(!o)return 'notfound';
o.click();return 'clicked';
""".trimIndent()

    /**
     * Etapa 3 — seleção de empresa: para cada rádio, o texto da sua linha (label associado ou o maior bloco que
     * contém só esse rádio); marca o rádio cuja linha tem o CNPJ `D` (só dígitos) e clica "Confirmar".
     * Não lê o `value` dos rádios. Sem correspondência: 'nomatch' (nada é clicado).
     */
    private val COMPANY_JS_TEMPLATE: String = """
var D='%CNPJ%';if(D.length!==14)return 'nomatch';
var conf=F(document,'confirmar',['voltar']);
if(!conf){var bi=document.querySelectorAll('input[type=submit],input[type=button]');for(var q=0;q<bi.length;q++){if(N(bi[q].getAttribute('value'))==='confirmar'&&V(bi[q])){conf=bi[q];break;}}}
if(!conf)return 'notfound';
var rs=document.querySelectorAll('input[type=radio],[role=radio]');var hit=null;
for(var i=0;i<rs.length&&!hit;i++){var r=rs[i];var txt='';
if(r.labels&&r.labels.length){txt=String(r.labels[0].innerText||r.labels[0].textContent||'');}
var c=r.parentElement;var row=c;while(c&&c!==document.body&&c.querySelectorAll('input[type=radio],[role=radio]').length===1){row=c;c=c.parentElement;}
txt+=' '+String((row&&(row.innerText||row.textContent))||'');
var ms=txt.match(/\d{2}\.?\d{3}\.?\d{3}\/?\d{4}-?\d{2}/g)||[];
for(var j=0;j<ms.length;j++){if(ms[j].replace(/\D/g,'')===D){hit=r;break;}}}
if(!hit)return 'nomatch';
hit.click();
setTimeout(function(){conf.click();},400);
return 'clicked';
""".trimIndent()

    /**
     * Script completo da etapa: funções comuns + checagem de bloqueios (CAPTCHA/2FA/erro → para sem clicar) + ação.
     * Devolve só um código curto; nenhum texto da página sai dela.
     */
    fun script(step: Step, cnpj: String): String? {
        val body = when (step) {
            Step.PROFILE_SELECT -> PROFILE_JS
            Step.GOVBR_LOGIN -> GOVBR_JS
            Step.COMPANY_SELECT -> COMPANY_JS_TEMPLATE.replace("%CNPJ%", digits(cnpj))
            else -> return null
        }
        return "(function(){try{\n$COMMON_JS\nvar bl=B();if(bl)return bl;\n$body\n}catch(e){return 'notfound';}})()"
    }
}
