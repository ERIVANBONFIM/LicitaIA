package com.licitaia.feature.live.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Scripts injetados por `evaluateJavascript` no WebView logado (strings puras, testáveis na JVM).
 *
 * Todos procuram no documento principal E nos frames do mesmo domínio (o Comprasnet legado é um frameset), ignoram
 * elementos invisíveis (exceto caixas de seleção estilizadas) e devolvem SEMPRE uma string JSON curta.
 *
 * Privacidade: nenhum script lê cookies, `localStorage`/`sessionStorage` nem valores de campos de senha. O único
 * valor de campo lido é o do campo que o próprio robô acabou de preencher (conferência). O snapshot do MODO MAPEAR
 * e o HTML das tabelas vêm SEM valores de campos, sem `<input type=hidden>`, sem scripts e com links sem query string.
 * Parâmetros vão como literal JSON (nunca concatenados como código).
 */
object AutomationScripts {

    /** Biblioteca comum (objeto `LZ`). */
    val PRELUDE: String = """
var LZ={};
LZ.N=function(s){return String(s==null?'':s).normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\s+/g,' ').trim();};
LZ.docs=function(){var D=[];var add=function(w,d){if(d>4)return;try{var doc=w.document;if(doc&&doc.body)D.push(doc);}catch(e){}try{for(var i=0;i<w.frames.length;i++)add(w.frames[i],d+1);}catch(e){}};add(window,0);return D;};
LZ.vis=function(e){try{if(!e||!e.getClientRects||e.getClientRects().length===0)return false;var s=(e.ownerDocument.defaultView||window).getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none';}catch(x){return true;}};
LZ.textOf=function(e){var tg=e.tagName;if(tg==='INPUT'){var ty=String(e.type||'').toLowerCase();if(ty==='submit'||ty==='button'||ty==='image')return e.value||e.getAttribute('alt')||'';return '';}var t=e.innerText;if(t==null||t==='')t=e.textContent||'';if(!LZ.N(t))t=e.getAttribute('title')||e.getAttribute('aria-label')||'';return t;};
LZ.labelOf=function(e){var d=e.ownerDocument,t=[];try{var a=e.getAttribute('aria-label');if(a)t.push(a);var lb=e.getAttribute('aria-labelledby');if(lb){lb.split(/\s+/).forEach(function(id){var x=d.getElementById(id);if(x)t.push(x.innerText||x.textContent);});}
if(e.id){var ls=d.getElementsByTagName('label');for(var i=0;i<ls.length;i++){if(ls[i].htmlFor===e.id)t.push(ls[i].innerText||ls[i].textContent);}}
var p=e.closest&&e.closest('label');if(p)t.push(p.innerText||p.textContent);
var ff=e.closest&&e.closest('mat-form-field,.mat-form-field,.mat-mdc-form-field,.form-group,.p-field,.br-input,.field');if(ff){var ll=ff.querySelector('label,mat-label,.mat-mdc-floating-label');if(ll)t.push(ll.innerText||ll.textContent);}
var td=e.closest&&e.closest('td');if(td&&td.previousElementSibling)t.push(td.previousElementSibling.innerText||'');
var ph=e.getAttribute('placeholder');if(ph)t.push(ph);var ti=e.getAttribute('title');if(ti)t.push(ti);}catch(x){}return LZ.N(t.join(' | '));};
LZ.sel={CLICKABLE:'a,button,input[type=submit],input[type=button],input[type=image],[role=button],[role=menuitem],[role=tab],[role=link],[role=option],[role=treeitem],mat-option,[onclick],summary',INPUT:'input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=password]):not([type=submit]):not([type=button]):not([type=file]),textarea,select,[contenteditable=true]',CHECKBOX:'input[type=checkbox],input[type=radio],[role=checkbox],mat-checkbox',ANY:'body *'};
LZ.has=function(t,s){if(!s)return false;var i=t.indexOf(s);while(i>=0){var a=i===0?'':t.charAt(i-1),b=t.charAt(i+s.length);var okA=!(/[0-9a-z]/.test(a)&&/^[0-9a-z]/.test(s));var okB=!(/[0-9]/.test(b)&&/[0-9]$/.test(s));if(okA&&okB)return true;i=t.indexOf(s,i+1);}return false;};
LZ.match=function(e,L){var v=LZ.N(L.v),t;switch(L.k){case 'TEXT':t=LZ.N(LZ.textOf(e));break;case 'LABEL':t=LZ.labelOf(e);break;case 'PLACEHOLDER':t=LZ.N(e.getAttribute('placeholder'));break;case 'ARIA_LABEL':t=LZ.N(e.getAttribute('aria-label'));break;
case 'ROLE':return LZ.N(e.getAttribute('role'))===v?1:-1;case 'NAME':return (LZ.N(e.getAttribute('name'))===v||LZ.N(e.getAttribute('formcontrolname'))===v||LZ.N(e.id)===v)?1:-1;default:return -1;}
if(!t)return -1;if(L.x)return t===v?t.length:-1;return LZ.has(t,v)?t.length:-1;};
LZ.roots=function(scope,kind){var D=LZ.docs();if(!scope)return D.map(function(d){return d.body;});var s=LZ.N(scope),best=[],bl=1e12,ks=LZ.sel[kind]||'*';
D.forEach(function(d){var cs=d.querySelectorAll('tr,[role=row],mat-row,li,mat-card,mat-expansion-panel,fieldset,section,article,div');for(var i=0;i<cs.length;i++){var c=cs[i];var t=LZ.N(c.textContent);if(t.length<s.length||!LZ.has(t,s))continue;if(kind!=='ANY'&&!c.querySelector(ks))continue;if(!LZ.vis(c))continue;if(t.length<bl){bl=t.length;best=[c];}}});return best;};
LZ.isKind=function(e,k){var tg=e.tagName,ty=String(e.type||'').toLowerCase(),r=String(e.getAttribute('role')||'').toLowerCase();
if(k==='CLICKABLE')return tg==='A'||tg==='BUTTON'||(tg==='INPUT'&&(ty==='submit'||ty==='button'||ty==='image'))||['button','menuitem','tab','link','option','treeitem'].indexOf(r)>=0||tg==='MAT-OPTION'||e.hasAttribute('onclick')||tg==='SUMMARY';
if(k==='INPUT')return ((tg==='INPUT'&&['hidden','checkbox','radio','password','submit','button','file'].indexOf(ty)<0)||tg==='TEXTAREA'||tg==='SELECT'||e.isContentEditable===true);
if(k==='CHECKBOX')return (tg==='INPUT'&&(ty==='checkbox'||ty==='radio'))||r==='checkbox'||tg==='MAT-CHECKBOX';return true;};
LZ.find=function(Q){var roots=LZ.roots(Q.scope,Q.kind);for(var li=0;li<Q.locs.length;li++){var L=Q.locs[li],hits=[];
roots.forEach(function(r){var es;if(L.k==='CSS'){try{es=r.ownerDocument.querySelectorAll(L.v);}catch(x){es=[];}}else{es=r.querySelectorAll(LZ.sel[Q.kind]||'body *');}
for(var i=0;i<es.length;i++){var e=es[i];if(r!==r.ownerDocument.body&&!r.contains(e))continue;if(Q.kind!=='CHECKBOX'&&!LZ.vis(e))continue;
if(L.k==='CSS'){if(Q.kind==='ANY'||LZ.isKind(e,Q.kind))hits.push({e:e,s:0});continue;}var s=LZ.match(e,L);if(s>=0)hits.push({e:e,s:s});}});
if(hits.length){hits.sort(function(a,b){return a.s-b.s;});var best=hits[0].s,uniq=[];hits.forEach(function(h){if(h.s!==best)return;if(!uniq.some(function(u){return u.e===h.e||u.e.contains(h.e)||h.e.contains(u.e);}))uniq.push(h);});
return {e:uniq[0].e,i:li,n:uniq.length};}}return null;};
LZ.css=function(e){try{var tg=e.tagName.toLowerCase(),q=function(s){return String(s).replace(/\\/g,'\\\\').replace(/"/g,'\\"');};
if(e.id&&!/^(mat-|cdk-|ng-|mat_|ui-id-|p-)[\w-]*\d/.test(e.id)&&!/\d{4,}/.test(e.id)&&/^[A-Za-z][\w-]*$/.test(e.id))return '#'+e.id;
var fc=e.getAttribute('formcontrolname');if(fc)return tg+'[formcontrolname="'+q(fc)+'"]';
var nm=e.getAttribute('name');if(nm&&!/\d{4,}/.test(nm))return tg+'[name="'+q(nm)+'"]';
var dt=e.getAttribute('data-testid')||e.getAttribute('data-cy');if(dt)return '[data-testid="'+q(dt)+'"]';
var al=e.getAttribute('aria-label');if(al&&al.length<60)return tg+'[aria-label="'+q(al)+'"]';return null;}catch(x){return null;}};
LZ.res=function(f){if(!f)return {found:false};var e=f.e;return {found:true,i:f.i,n:f.n,css:LZ.css(e),text:LZ.N(LZ.textOf(e)||LZ.labelOf(e)).slice(0,160),tag:e.tagName.toLowerCase()};};
LZ.setVal=function(e,v){var tg=e.tagName,w=e.ownerDocument.defaultView||window;if(tg==='SELECT'){var nv=LZ.N(v),o=null;for(var i=0;i<e.options.length;i++){var op=e.options[i];if(LZ.N(op.text)===nv||op.value===v){o=op;break;}if(!o&&LZ.N(op.text).indexOf(nv)>=0)o=op;}if(!o)return false;e.value=o.value;}
else if(e.isContentEditable){e.focus();e.textContent=v;}
else{try{e.focus();}catch(x){}var proto=tg==='TEXTAREA'?w.HTMLTextAreaElement.prototype:w.HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(e,v);else e.value=v;}
['input','change'].forEach(function(n){try{e.dispatchEvent(new w.Event(n,{bubbles:true}));}catch(x){}});try{e.dispatchEvent(new w.KeyboardEvent('keyup',{bubbles:true}));}catch(x){}try{e.dispatchEvent(new w.Event('blur',{bubbles:true}));if(e.blur)e.blur();}catch(x){}return true;};
LZ.valOf=function(e){if(e.tagName==='SELECT'){var o=e.options[e.selectedIndex];return o?o.text:'';}if(e.isContentEditable)return e.textContent||'';if('value' in e)return String(e.value==null?'':e.value);return LZ.textOf(e);};
LZ.click=function(e){try{e.scrollIntoView({block:'center'});}catch(x){}if(e.tagName==='A'){var tg=String(e.getAttribute('target')||'').toLowerCase();if(tg&&tg!=='_self'&&tg!=='_top'&&tg!=='_parent')e.setAttribute('target','_self');}
var w=e.ownerDocument.defaultView||window;['mousedown','mouseup'].forEach(function(n){try{e.dispatchEvent(new w.MouseEvent(n,{bubbles:true,cancelable:true,view:w}));}catch(x){}});e.click();return true;};
LZ.path=function(h){try{var a=document.createElement('a');a.href=h;return a.pathname;}catch(x){return '';}};
""".trimIndent()

    private val json = Json

    /** Literal JSON seguro para embutir em JS (sem U+2028/U+2029, que quebram literais em JS antigo). */
    fun jsLiteral(element: JsonElement): String =
        json.encodeToString(JsonElement.serializer(), element).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    fun queryJson(q: ElementQuery): JsonElement = buildJsonObject {
        put("kind", q.kind.name)
        q.scopeText?.let { put("scope", it) }
        put("critical", q.critical)
        put("locs", buildJsonArray {
            q.locators.forEach { l -> add(buildJsonObject { put("k", l.kind.name); put("v", l.value); put("x", l.exact) }) }
        })
    }

    internal fun wrap(body: String): String = "(function(){try{\n$PRELUDE\n$body\n}catch(err){return JSON.stringify({found:false,error:String(err&&err.message||err)});}})()"

    fun find(q: ElementQuery): String = wrap("var Q=${jsLiteral(queryJson(q))};return JSON.stringify(LZ.res(LZ.find(Q)));")

    fun fill(q: ElementQuery, value: String): String = wrap(
        "var Q=${jsLiteral(queryJson(q))},V=${jsLiteral(JsonPrimitive(value))};var f=LZ.find(Q);if(!f)return JSON.stringify({found:false});" +
            "var r=LZ.res(f);if(f.n>1)return JSON.stringify({found:true,ok:false,res:r,error:'ambiguous'});" +
            "var ok=LZ.setVal(f.e,V);return JSON.stringify({found:true,ok:ok,res:r,readBack:LZ.valOf(f.e)});",
    )

    fun click(q: ElementQuery): String = wrap(
        "var Q=${jsLiteral(queryJson(q))};var f=LZ.find(Q);if(!f)return JSON.stringify({found:false});var r=LZ.res(f);" +
            "if(f.n>1&&Q.critical)return JSON.stringify({found:true,ok:false,res:r,error:'ambiguous'});" +
            "LZ.click(f.e);return JSON.stringify({found:true,ok:true,res:r});",
    )

    fun read(q: ElementQuery): String = wrap(
        "var Q=${jsLiteral(queryJson(q))};var f=LZ.find(Q);if(!f)return JSON.stringify({found:false});" +
            "return JSON.stringify({found:true,value:String(LZ.valOf(f.e)).slice(0,2000)});",
    )

    /**
     * Estado da página (texto visível normalizado, CAPTCHA, campo de código, diálogos). Fica só na memória do app.
     * CAPTCHA = desafio VISÍVEL (iframe/imagem com tamanho de desafio): o Compras eletrônicas embute um hCaptcha
     * invisível (textarea h-captcha-response, iframes ocultos) em toda página, que não pede nada ao usuário.
     */
    fun probe(): String = wrap(
        """var D=LZ.docs(),t='',cap=false,otp=false,dl=[];
D.forEach(function(d){t+=' '+(d.body.innerText||'');
var cs=d.querySelectorAll('iframe[src*="recaptcha"],iframe[src*="hcaptcha"],iframe[title*="captcha" i],img[src*="captcha" i]');for(var c=0;c<cs.length;c++){var r=cs[c].getBoundingClientRect();if(LZ.vis(cs[c])&&r.width>=80&&r.height>=60)cap=true;}
if(d.querySelector('input[autocomplete="one-time-code"],input[name*="otp" i],input[id*="otp" i],input[name*="totp" i]'))otp=true;
var ds=d.querySelectorAll('[role=dialog],[role=alertdialog],mat-dialog-container,.modal.show,.p-dialog,.swal2-popup,.br-modal');for(var i=0;i<ds.length;i++){if(LZ.vis(ds[i]))dl.push(LZ.N(ds[i].innerText).slice(0,600));}});
var n=LZ.N(t);if(/nao sou um robo|nao sou robo/.test(n))cap=true;
var u=String(location.href).replace(/\?[^#]*/,'');u=u.replace(/(#[^?]*)\?.*/,'${'$'}1');
return JSON.stringify({url:u,title:String(document.title||'').slice(0,160),text:n.slice(0,8000),cap:cap,otp:otp,dialogs:dl.slice(0,5)});""",
    )

    /** Textos das linhas visíveis (células separadas por " | "). */
    fun rows(scopeText: String?): String = wrap(
        "var S=${jsLiteral(JsonPrimitive(scopeText ?: ""))};var roots=S?LZ.roots(S,'ANY'):LZ.docs().map(function(d){return d.body;});var R=[],seen={};" +
            "roots.forEach(function(r){var es=r.querySelectorAll('tr,[role=row],mat-row,mat-card,li,mat-expansion-panel-header,.card,.list-group-item,.br-item');" +
            "for(var i=0;i<es.length&&R.length<400;i++){var e=es[i];if(!LZ.vis(e))continue;var t;" +
            "if(e.tagName==='TR'||e.getAttribute('role')==='row'||e.tagName==='MAT-ROW'){var cs=e.querySelectorAll('td,th,[role=cell],[role=gridcell],mat-cell');var a=[];for(var j=0;j<cs.length;j++){if(cs[j].querySelector('table'))continue;a.push(String(cs[j].innerText||cs[j].textContent||'').replace(/\\s+/g,' ').trim());}t=a.join(' | ');}" +
            "else{t=String(e.innerText||'').split(/\\n+/).map(function(x){return x.trim();}).filter(function(x){return x;}).join(' | ');}" +
            "if(t.length<3||t.length>3000||seen[t])continue;seen[t]=1;R.push(t);}});return JSON.stringify({rows:R});",
    )

    /** HTML das tabelas sem campos ocultos, scripts, valores de campos e query strings dos links. */
    fun tablesHtml(): String = wrap(
        "var out=[],total=0;LZ.docs().forEach(function(d){var ts=d.querySelectorAll('table');for(var i=0;i<ts.length;i++){var t=ts[i];if(t.parentElement&&t.parentElement.closest&&t.parentElement.closest('table'))continue;" +
            "var c=t.cloneNode(true);c.querySelectorAll('script,style,input[type=hidden],input[type=password]').forEach(function(x){x.remove();});" +
            "c.querySelectorAll('input,textarea,select,option').forEach(function(x){x.removeAttribute('value');if(x.tagName==='TEXTAREA')x.textContent='';});" +
            "c.querySelectorAll('[href]').forEach(function(x){x.setAttribute('href',LZ.path(x.getAttribute('href')));});" +
            "c.querySelectorAll('[onclick]').forEach(function(x){x.removeAttribute('onclick');});" +
            "var h=c.outerHTML;if(total+h.length>700000)break;total+=h.length;out.push(h);}});return JSON.stringify({html:out.join('\\n')});",
    )

    /** MODO MAPEAR: estrutura (campos, botões, tabelas, títulos, diálogos), sem valores digitados. */
    fun snapshot(): String = wrap(
        """var D=LZ.docs(),S={url:String(location.href).replace(/\?[^#]*/,''),title:String(document.title||'').slice(0,160),frames:D.length,inputs:[],buttons:[],checkboxes:[],tables:[],headings:[],dialogs:[]};
var cls=function(e){return String(e.getAttribute('class')||'').split(/\s+/).filter(function(c){return c&&c.length<40;}).slice(0,4).join(' ');};
D.forEach(function(d,fi){
var es=d.querySelectorAll(LZ.sel.INPUT);for(var i=0;i<es.length&&S.inputs.length<150;i++){var e=es[i];S.inputs.push({f:fi,tag:e.tagName.toLowerCase(),type:String(e.type||''),id:e.id||'',name:e.getAttribute('name')||'',fc:e.getAttribute('formcontrolname')||'',ph:e.getAttribute('placeholder')||'',aria:e.getAttribute('aria-label')||'',label:LZ.labelOf(e).slice(0,160),cls:cls(e),req:!!e.required,dis:!!e.disabled,vis:LZ.vis(e),css:LZ.css(e)});}
var bs=d.querySelectorAll(LZ.sel.CLICKABLE);for(var i=0;i<bs.length&&S.buttons.length<250;i++){var b=bs[i];if(!LZ.vis(b))continue;S.buttons.push({f:fi,tag:b.tagName.toLowerCase(),text:LZ.N(LZ.textOf(b)).slice(0,100),id:b.id||'',cls:cls(b),aria:b.getAttribute('aria-label')||'',role:b.getAttribute('role')||'',href:b.getAttribute('href')?LZ.path(b.getAttribute('href')):'',css:LZ.css(b)});}
var cs=d.querySelectorAll(LZ.sel.CHECKBOX);for(var i=0;i<cs.length&&S.checkboxes.length<80;i++){var c=cs[i];S.checkboxes.push({f:fi,tag:c.tagName.toLowerCase(),id:c.id||'',name:c.getAttribute('name')||'',fc:c.getAttribute('formcontrolname')||'',label:LZ.labelOf(c).slice(0,200),css:LZ.css(c)});}
var ts=d.querySelectorAll('table,mat-table,[role=table],[role=grid]');for(var i=0;i<ts.length&&S.tables.length<30;i++){var t=ts[i];if(!LZ.vis(t))continue;var hs=[].map.call(t.querySelectorAll('th,[role=columnheader],mat-header-cell'),function(h){return LZ.N(h.innerText).slice(0,60);}).slice(0,30);
var rs=t.querySelectorAll('tr,[role=row],mat-row');var sm=[];for(var j=0;j<rs.length&&sm.length<3;j++){var cells=[].map.call(rs[j].querySelectorAll('td,[role=cell],[role=gridcell],mat-cell'),function(x){return String(x.innerText||'').replace(/\s+/g,' ').trim().slice(0,80);});if(cells.length)sm.push(cells.slice(0,20));}
S.tables.push({f:fi,tag:t.tagName.toLowerCase(),id:t.id||'',cls:cls(t),headers:hs,rows:rs.length,sample:sm});}
var hd=d.querySelectorAll('h1,h2,h3,h4,legend,mat-card-title,.titulo,.title,[role=heading]');for(var i=0;i<hd.length&&S.headings.length<60;i++){if(LZ.vis(hd[i]))S.headings.push(LZ.N(hd[i].innerText).slice(0,120));}
var dg=d.querySelectorAll('[role=dialog],[role=alertdialog],mat-dialog-container,.modal.show,.br-modal');for(var i=0;i<dg.length;i++){if(LZ.vis(dg[i]))S.dialogs.push(LZ.N(dg[i].innerText).slice(0,400));}
});return JSON.stringify(S);""",
    )

    /** Escapa uma lista de strings como array JSON (para testes/depuração). */
    fun arrayLiteral(items: List<String>): String = jsLiteral(JsonArray(items.map { JsonPrimitive(it) }))
}
