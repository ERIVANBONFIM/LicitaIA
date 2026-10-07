package com.licitaia.feature.live.automation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Scripts das telas REAIS do Compras.gov.br (SPA Angular + PrimeNG), mapeadas no aparelho. Seletores usados:
 * `p-tab[role=tab]` (pelo TEXTO — os ids `pn_id_*` são gerados), `span.p-select-label[role=combobox]` +
 * `div.p-select-dropdown` + `[role=option]`, `div.cp-itens-card`, `button[aria-label=...]`, `#unidadeCompradora`,
 * `input[placeholder="Ex: 102021"]`, `input[data-test="input-valor-proposta"]`, `.p-toast-message`, ids das declarações.
 *
 * Regras: nunca lê cookies/storage; o único valor de campo lido é o que o próprio robô digitou; NUNCA clica em
 * declarações, termo, lixeira (`fa-trash-alt`), favoritos ou "Desfazer alterações". Parâmetros como literal JSON.
 */
object SpaScripts {

    val SPA_PRELUDE: String = """
var SP={};
SP.T=function(e){return String((e&&(e.innerText||e.textContent))||'').replace(/\s+/g,' ').trim();};
SP.cards=function(){return [].slice.call(document.querySelectorAll('div.cp-itens-card')).filter(function(c){return LZ.vis(c);});};
SP.fav=function(c){if(c.querySelector('button[aria-label="Remover dos favoritos"]'))return true;var h=c.querySelector('.fa-heart');return !!(h&&h.classList.contains('fas'));};
SP.itemNo=function(c){var s=c.querySelector('app-identificacao-item span');var n=s?parseInt(SP.T(s),10):NaN;if(isNaN(n)){var m=SP.T(c).match(/^(\d{1,4})\b/);n=m?parseInt(m[1],10):-1;}return n;};
SP.itemCards=function(n){return SP.cards().filter(function(c){return SP.itemNo(c)===n;});};
SP.inputs=function(){return [].slice.call(document.querySelectorAll('input[data-test="input-valor-proposta"]')).filter(function(e){return LZ.vis(e);});};
SP.marked=function(n){var es=[].slice.call(document.querySelectorAll('input[data-lz-item="'+n+'"]')).filter(function(e){return e.isConnected&&LZ.vis(e);});return es.length===1?es[0]:null;};
SP.form=function(inp){var e=inp;for(var i=0;i<10&&e&&e.parentElement;i++){e=e.parentElement;if(e.querySelectorAll('input[data-test="input-valor-proposta"]').length>1)return null;if(e.querySelector('div.cp-itens-card')&&e.querySelectorAll('div.cp-itens-card').length>1)return null;var bs=[].slice.call(e.querySelectorAll('button')).filter(function(b){return LZ.N(SP.T(b))==='salvar'&&LZ.vis(b);});if(bs.length===1)return {box:e,save:bs[0]};if(bs.length>1)return null;}return null;};
SP.toasts=function(){return [].slice.call(document.querySelectorAll('.p-toast-message')).map(function(t){return {c:String(t.className),t:SP.T(t).slice(0,300)};});};
SP.chk=function(e){if(!e)return false;if(e.checked)return true;var w=e.closest&&e.closest('p-checkbox,p-radiobutton,.p-checkbox,.p-radiobutton');return !!(w&&(w.classList.contains('p-checkbox-checked')||w.classList.contains('p-radiobutton-checked')));};
SP.focus=function(e){try{e.scrollIntoView({block:'center'});}catch(x){}try{e.focus();}catch(x){}try{e.select();}catch(x){}try{e.setSelectionRange(0,String(e.value||'').length);}catch(x){}return document.activeElement===e;};
SP.rect=function(e){try{e.scrollIntoView({block:'center'});}catch(x){}var r=e.getBoundingClientRect();var vv=window.visualViewport;var s=(vv?vv.scale:1)*(window.devicePixelRatio||1);var ox=vv?vv.offsetLeft:0,oy=vv?vv.offsetTop:0;return {x:(r.left+r.width/2-ox)*s,y:(r.top+r.height/2-oy)*s};};
SP.chevron=function(c){var bs=[].slice.call(c.querySelectorAll('button')).filter(function(b){if(b.querySelector('.fa-trash-alt,.fa-heart')||b.getAttribute('aria-label'))return String(b.getAttribute('aria-label')||'')==='Mostrar detalhes da compra';return String(b.className).indexOf('animationRotate')>=0||!!b.querySelector('.fa-chevron-down,.fa-chevron-up');});return bs.length?bs[bs.length-1]:null;};
""".trimIndent()

    private fun script(params: JsonObject, body: String): String =
        AutomationScripts.wrap("$SPA_PRELUDE\nvar P=${AutomationScripts.jsLiteral(params)};\n$body")

    private fun noParams(body: String) = script(buildJsonObject { }, body)

    /** Estado da tela (URL com o `compra=`, abas, cartões, paginação, filtro, avisos). Não devolve o nome/CPF do cabeçalho. */
    fun state(): String = noParams(
        """var r={url:String(location.href),h1:'',tabs:[],active:'',cards:[],showing:'',next:false,page:'',filter:'',toasts:SP.toasts(),search:false};
var h=document.querySelector('h1');if(h)r.h1=SP.T(h);
[].slice.call(document.querySelectorAll('p-tab,[role=tab]')).forEach(function(t){var x=SP.T(t);if(!x||x.length>40)return;if(r.tabs.indexOf(x)<0)r.tabs.push(x);if(t.classList.contains('p-tab-active')||t.getAttribute('aria-selected')==='true'||t.getAttribute('data-p-active')==='true')r.active=x;});
SP.cards().forEach(function(c){if(r.cards.length<80)r.cards.push({t:SP.T(c).slice(0,700),fav:SP.fav(c)});});
var m=String(document.body.innerText||'').match(/Exibindo\s+\d+\s+de\s+\d+/i);if(m)r.showing=m[0];
var nx=document.querySelector('button.p-paginator-next');r.next=!!(nx&&LZ.vis(nx)&&!nx.disabled&&!nx.classList.contains('p-disabled'));
var pg=document.querySelector('button.p-paginator-page-selected');if(pg)r.page=SP.T(pg);
var sl=document.querySelector('span.p-select-label[role=combobox]');if(sl)r.filter=SP.T(sl);
r.search=!!document.getElementById('unidadeCompradora');
return JSON.stringify(r);""",
    )

    /** Clica na aba PrimeNG pelo texto ("Minhas participações" / "Todas as compras"). */
    fun clickTab(label: String): String = script(
        buildJsonObject { put("label", label) },
        """var L=LZ.N(P.label);var ts=[].slice.call(document.querySelectorAll('p-tab,[role=tab]')).filter(function(t){return LZ.N(SP.T(t))===L;});
if(!ts.length)return JSON.stringify({ok:false});LZ.click(ts[0]);return JSON.stringify({ok:true});""",
    )

    /** Botão do topo pelo aria-label (ex.: "Tela inicial"). */
    fun clickAria(label: String): String = script(
        buildJsonObject { put("label", label) },
        """var bs=[].slice.call(document.querySelectorAll('button[aria-label]')).filter(function(b){return b.getAttribute('aria-label')===P.label&&LZ.vis(b);});
if(!bs.length)return JSON.stringify({ok:false});LZ.click(bs[0]);return JSON.stringify({ok:true});""",
    )

    /**
     * Filtro de "Minhas participações" (`p-select`): 1ª chamada abre a lista; a seguinte clica na opção. `done` = já
     * estava selecionado.
     */
    fun selectFilter(label: String): String = script(
        buildJsonObject { put("label", label) },
        """var L=LZ.N(P.label);var sl=document.querySelector('span.p-select-label[role=combobox]');if(!sl)return JSON.stringify({ok:false,error:'sem filtro'});
if(LZ.N(SP.T(sl))===L)return JSON.stringify({ok:true,done:true});
var os=[].slice.call(document.querySelectorAll('[role=option]')).filter(function(o){return LZ.vis(o);});
if(!os.length){var hs=sl.closest('p-select,.p-select')||sl.parentElement;var dd=(hs&&hs.querySelector('.p-select-dropdown'))||sl;LZ.click(dd);return JSON.stringify({ok:true,opened:true});}
var hit=os.filter(function(o){return LZ.N(SP.T(o)||o.getAttribute('aria-label'))===L;});
if(!hit.length)return JSON.stringify({ok:false,error:'opção não encontrada'});LZ.click(hit[0]);return JSON.stringify({ok:true,picked:true});""",
    )

    /** Paginação PrimeNG: "Próxima Página" (só se habilitada). */
    fun nextPage(): String = noParams(
        """var nx=document.querySelector('button.p-paginator-next');if(!nx||nx.disabled||nx.classList.contains('p-disabled'))return JSON.stringify({ok:false});
var pg=document.querySelector('button.p-paginator-page-selected');LZ.click(nx);return JSON.stringify({ok:true,from:pg?SP.T(pg):''});""",
    )

    /** Campo da busca de "Todas as compras" ([which] = "uasg" | "numero"): foca e seleciona tudo (a digitação é nativa). */
    fun focusSearch(which: String): String = script(
        buildJsonObject { put("which", which) },
        """var e=P.which==='uasg'?document.getElementById('unidadeCompradora'):document.querySelector('input[placeholder="Ex: 102021"]');
if(!e||!LZ.vis(e)){var tg=document.querySelector('[aria-label="Parâmetros de pesquisa"]');if(tg&&!e){var b=tg.querySelector('button')||tg;LZ.click(b);return JSON.stringify({found:false,toggled:true});}return JSON.stringify({found:false});}
return JSON.stringify({found:true,focused:SP.focus(e),value:String(e.value||'')});""",
    )

    fun readSearch(which: String): String = script(
        buildJsonObject { put("which", which) },
        """var e=P.which==='uasg'?document.getElementById('unidadeCompradora'):document.querySelector('input[placeholder="Ex: 102021"]');
if(!e)return JSON.stringify({found:false});return JSON.stringify({found:true,value:String(e.value||'')});""",
    )

    /** Botão "Pesquisar": clique JS ou, com [tap], só a posição na tela (para um toque nativo). */
    fun searchButton(tap: Boolean): String = script(
        buildJsonObject { put("tap", tap) },
        """var bs=[].slice.call(document.querySelectorAll('button')).filter(function(b){return LZ.N(SP.T(b))==='pesquisar'&&LZ.vis(b);});
if(bs.length!==1)return JSON.stringify({ok:false,n:bs.length});if(P.tap)return JSON.stringify({ok:true,rect:SP.rect(bs[0])});LZ.click(bs[0]);return JSON.stringify({ok:true});""",
    )

    /**
     * Cartão da compra (UASG + "N° n/aaaa") na lista atual. Com [click], clica em "Participar/acompanhar compra" ou
     * "Acompanhar compra" (clique JS — validado no aparelho). Mais de um cartão igual = ambíguo (não clica).
     */
    fun purchase(uasg: String, number: String, year: Int, click: Boolean): String = script(
        buildJsonObject {
            put("uasg", uasg.filter(Char::isDigit).trimStart('0'))
            put("num", number.filter(Char::isDigit).trimStart('0').ifEmpty { "0" })
            put("year", year.toString())
            put("click", click)
        },
        """var re=new RegExp('N\\s*[°ºo.]*\\s*0*'+P.num+'\\s*/\\s*'+P.year+'(?!\\d)','i');var ru=new RegExp('(^|\\D)0*'+P.uasg+'(?!\\d)');
var hits=SP.cards().filter(function(c){var t=SP.T(c);return re.test(t)&&ru.test(t);});
if(!hits.length)return JSON.stringify({found:false,cards:SP.cards().length});
if(hits.length>1)return JSON.stringify({found:true,ok:false,error:'ambiguous',n:hits.length});
var c=hits[0];var b=c.querySelector('button[aria-label="Participar/acompanhar compra"]')||c.querySelector('button[aria-label="Acompanhar compra"]');
if(!b)return JSON.stringify({found:true,ok:false,error:'sem o botão Acompanhar compra'});
if(P.click)LZ.click(b);return JSON.stringify({found:true,ok:true,label:b.getAttribute('aria-label')});""",
    )

    /** Termo/declarações: SÓ leitura (o robô nunca marca). */
    fun declarations(): String = noParams(
        """var r={section:false,warning:false,termo:null,modal:false,groups:[]};var body=LZ.N(document.body.innerText);
r.section=body.indexOf('termo/declaracoes')>=0;r.warning=body.indexOf('e necessario o aceite do termo')>=0;
var a=[].slice.call(document.querySelectorAll('a')).filter(function(x){return LZ.N(SP.T(x)).indexOf('termo de aceitacao')===0&&!(x.closest&&x.closest('modal-container,[role=dialog]'));})[0];
if(a){var el=a,box=null;for(var up=0;up<6&&el&&!box;up++){el=el.parentElement;if(el)box=el.querySelector('input[type=checkbox]');}if(box)r.termo=SP.chk(box);}
r.modal=!!document.querySelector('modal-container.modal.show,modal-container.show');
[['meepp','Declaração para fornecedores ME/EPP','labelSimMeepp','labelNaoMeepp'],['equidade','Equidade entre mulheres e homens (art. 60, III)','declaracaoEquidadeGeneroSim','declaracaoEquidadeGeneroNao'],['integridade','Programa de integridade (art. 60, IV)','declaracaoProgramasIntegridadeSim','declaracaoProgramasIntegridadeNao']].forEach(function(g){
var s=document.getElementById(g[2]),n=document.getElementById(g[3]);if(!s&&!n)return;r.groups.push({k:g[0],l:g[1],s:SP.chk(s),n:SP.chk(n)});});
return JSON.stringify(r);""",
    )

    /** Cartões de item do cadastro de proposta (número + texto). */
    fun items(): String = noParams(
        """var out=[];SP.cards().forEach(function(c){out.push({n:SP.itemNo(c),t:SP.T(c).slice(0,800)});});return JSON.stringify({items:out,toasts:SP.toasts()});""",
    )

    /**
     * Prepara a abertura do formulário do item: se já há um campo de valor marcado para o item, pronto; senão marca os
     * campos de valor visíveis com [token] (o formulário NOVO que aparecer ao tocar a seta do item é o dele).
     */
    fun prepareItem(n: Int, token: String): String = script(
        buildJsonObject { put("n", n); put("tok", token) },
        """var cs=SP.itemCards(P.n);if(!cs.length)return JSON.stringify({found:false});if(cs.length>1)return JSON.stringify({found:true,ok:false,error:'item repetido na página'});
if(SP.marked(P.n))return JSON.stringify({found:true,ok:true,ready:true});
var ins=SP.inputs();ins.forEach(function(e){e.setAttribute('data-lz-seen',P.tok);});return JSON.stringify({found:true,ok:true,ready:false,open:ins.length});""",
    )

    /** Seta (chevron) do cartão do item — nunca a lixeira nem o coração. */
    fun toggleItem(n: Int): String = script(
        buildJsonObject { put("n", n) },
        """var cs=SP.itemCards(P.n);if(cs.length!==1)return JSON.stringify({ok:false});var b=SP.chevron(cs[0]);if(!b)return JSON.stringify({ok:false,error:'sem seta'});LZ.click(b);return JSON.stringify({ok:true});""",
    )

    /** Procura o campo de valor que apareceu depois da seta e o marca como do item [n]. */
    fun claimItemForm(n: Int, token: String, byPosition: Boolean = false): String = script(
        buildJsonObject { put("n", n); put("tok", token); put("any", byPosition) },
        """var all=SP.inputs();var fresh=all.filter(function(e){return e.getAttribute('data-lz-seen')!==P.tok;});
var seen=all.length-fresh.length;var pick=null;var pool=P.any?all:fresh;
if(!P.any&&fresh.length===1)pick=fresh[0];
else if(pool.length>=1){var cs=SP.cards();var c=SP.itemCards(P.n)[0];var nx=c?cs[cs.indexOf(c)+1]:null;var f2=pool.filter(function(e){return c&&(c.compareDocumentPosition(e)&4)&&(!nx||(nx.compareDocumentPosition(e)&2));});if(f2.length===1)pick=f2[0];}
if(pick){[].slice.call(document.querySelectorAll('input[data-lz-item]')).forEach(function(x){if(x.getAttribute('data-lz-item')===String(P.n))x.removeAttribute('data-lz-item');});pick.setAttribute('data-lz-item',String(P.n));return JSON.stringify({ok:true});}
return JSON.stringify({ok:false,fresh:fresh.length,seen:seen});""",
    )

    /**
     * Campo do formulário do item [n]: "valor" (o `input-valor-proposta` marcado) ou outro pelo rótulo dentro do
     * mesmo formulário ("quantidade ofertada", "marca", "fabricante", "modelo", "descricao detalhada"). [focus] = foca
     * e seleciona tudo; senão só lê o valor.
     */
    fun itemField(n: Int, field: String, focus: Boolean): String = script(
        buildJsonObject { put("n", n); put("field", TextNorm.norm(field)); put("focus", focus) },
        """var e=SP.marked(P.n);if(!e)return JSON.stringify({found:false,error:'formulário do item não aberto'});var t=e;
if(P.field!=='valor'){var fm=SP.form(e);if(!fm)return JSON.stringify({found:false});var lb=[].slice.call(fm.box.querySelectorAll('label')).filter(function(l){return LZ.N(SP.T(l)).indexOf(P.field)===0;})[0];if(!lb)return JSON.stringify({found:false});
var c=lb.htmlFor?document.getElementById(lb.htmlFor):null;if(!c){c=[].slice.call(fm.box.querySelectorAll('input:not([type=hidden]):not([type=checkbox]):not([type=radio]),textarea')).filter(function(i){return LZ.vis(i)&&(lb.compareDocumentPosition(i)&4);})[0];}
if(!c||c===e)return JSON.stringify({found:false});t=c;}
if(P.focus)return JSON.stringify({found:true,focused:SP.focus(t),value:String(t.value||''),disabled:!!(t.disabled||t.readOnly)});
return JSON.stringify({found:true,value:String(t.value||''),disabled:!!(t.disabled||t.readOnly)});""",
    )

    /** Fecha avisos antigos (botão "Fechar" do toast) antes de salvar. */
    fun dismissToasts(): String = noParams(
        """var bs=[].slice.call(document.querySelectorAll('.p-toast-close-button'));bs.forEach(function(b){try{b.click();}catch(x){}});return JSON.stringify({ok:true,n:bs.length});""",
    )

    /** "Salvar" do formulário do item [n] (o único botão "Salvar" do bloco do campo marcado). */
    fun saveItem(n: Int): String = script(
        buildJsonObject { put("n", n) },
        """var e=SP.marked(P.n);if(!e)return JSON.stringify({ok:false,error:'formulário do item não aberto'});var fm=SP.form(e);
if(!fm)return JSON.stringify({ok:false,error:'botão Salvar do item não identificado'});if(fm.save.disabled)return JSON.stringify({ok:false,error:'Salvar desabilitado'});
LZ.click(fm.save);return JSON.stringify({ok:true});""",
    )

    /** Cartão do item [n] + avisos (conferência depois de salvar). */
    fun itemStatus(n: Int): String = script(
        buildJsonObject { put("n", n) },
        """var cs=SP.itemCards(P.n);return JSON.stringify({found:cs.length===1,t:cs.length===1?SP.T(cs[0]).slice(0,800):'',toasts:SP.toasts()});""",
    )

    // ------------------------------------------------------------------ leitura das respostas

    data class State(
        val url: String = "",
        val h1: String = "",
        val tabs: List<String> = emptyList(),
        val activeTab: String = "",
        val cards: List<SpaCard> = emptyList(),
        val showing: String = "",
        val hasNext: Boolean = false,
        val page: String = "",
        val filter: String = "",
        val toasts: List<PortalToast> = emptyList(),
        val hasSearch: Boolean = false,
    ) {
        val isComprasList: Boolean get() = tabs.any { TextNorm.norm(it) == "minhas participacoes" } && tabs.any { TextNorm.norm(it) == "todas as compras" }
        fun tabActive(label: String) = TextNorm.norm(activeTab) == TextNorm.norm(label)
    }

    fun parseState(raw: String?): State? {
        val o = AutomationJson.obj(raw) ?: return null
        with(AutomationJson) {
            val cards = (o["cards"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
                (el as? JsonObject)?.let { SpaCard(it.str("t").orEmpty(), it.bool("fav")) }
            }
            return State(
                url = o.str("url").orEmpty(), h1 = o.str("h1").orEmpty(), tabs = o.strings("tabs"), activeTab = o.str("active").orEmpty(),
                cards = cards, showing = o.str("showing").orEmpty(), hasNext = o.bool("next"), page = o.str("page").orEmpty(),
                filter = o.str("filter").orEmpty(), toasts = parseToasts(o), hasSearch = o.bool("search"),
            )
        }
    }

    fun parseToasts(o: JsonObject?): List<PortalToast> = with(AutomationJson) {
        (o?.get("toasts") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
            (el as? JsonObject)?.let { PortalToast.of(it.str("c").orEmpty(), it.str("t").orEmpty()) }
        }
    }

    /** Resposta simples `{ok, found, error, value, ...}`. */
    data class Reply(val ok: Boolean, val found: Boolean, val error: String?, val value: String?, val raw: JsonObject?) {
        fun flag(key: String): Boolean = raw?.let { with(AutomationJson) { it.bool(key) } } ?: false
        fun int(key: String): Int? = raw?.let { with(AutomationJson) { it.int(key) } }
        fun double(key: String): Double? = (raw?.get(key) as? JsonPrimitive)?.content?.toDoubleOrNull()
        fun obj(key: String): JsonObject? = raw?.get(key) as? JsonObject
    }

    fun reply(raw: String?): Reply {
        val o = AutomationJson.obj(raw) ?: return Reply(false, false, "sem resposta da página", null, null)
        return with(AutomationJson) { Reply(o.bool("ok"), o.bool("found"), o.str("error"), o.str("value"), o) }
    }
}
