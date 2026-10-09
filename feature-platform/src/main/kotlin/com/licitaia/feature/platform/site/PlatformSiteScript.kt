package com.licitaia.feature.platform.site

import org.json.JSONObject

/**
 * Script que roda no INÍCIO de cada página do site (só no domínio da VPS), dentro do app:
 *  1) login único: grava o token/usuário da plataforma no localStorage, como o próprio site faz ao entrar
 *     (`licitapro_token` / `licitapro_user`), e depois atualiza o usuário completo por `GET /api/auth/me`;
 *  2) MODELO B: barra de atalhos embaixo (Início, Pesquisar, Disputas, Minhas, Mais) no padrão do site.
 *     "Mais" abre o menu lateral do próprio site; os atalhos clicam nos links do menu (mesma navegação do site).
 *  3) pequenos ajustes de tela pequena (tabelas roláveis de lado, espaço para a barra).
 * Nada aqui envia dados para fora: só lê/grava o localStorage do próprio site.
 */
internal object PlatformSiteScript {

    fun build(token: String, userJson: String?): String =
        TEMPLATE
            .replace("__TOKEN__", JSONObject.quote(token))
            .replace("__USER__", JSONObject.quote(userJson ?: ""))

    private val TEMPLATE = """
(function () {
  if (window.__lzApp) return; window.__lzApp = true;
  var T = __TOKEN__, U = __USER__;
  try {
    if (localStorage.getItem('licitapro_token') !== T) {
      localStorage.setItem('licitapro_token', T);
      if (U) localStorage.setItem('licitapro_user', U);
    } else if (U && !localStorage.getItem('licitapro_user')) {
      localStorage.setItem('licitapro_user', U);
    }
  } catch (e) {}
  // usuario completo do site (o app guarda so os campos que usa)
  try {
    fetch('/api/auth/me', { headers: { Authorization: 'Bearer ' + T } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) { var u = d && (d.user || d); if (u && u.id) localStorage.setItem('licitapro_user', JSON.stringify(u)); })
      .catch(function () {});
  } catch (e) {}

  // ---- ponte: o que precisa do certificado roda NO CELULAR (window.LicitaApp), nunca no robo da VPS ----
  var seq = 0, pend = {};
  window.__lzResp = function (id, st, txt) { var f = pend[id]; delete pend[id]; if (f) f(st, txt); };
  function doApp(m, p) { try { return !!(window.LicitaApp && LicitaApp.handles(m, p) === '1'); } catch (e) { return false; } }
  function appReq(m, p, body, cb) { var id = ++seq; pend[id] = cb; LicitaApp.request(id, m, p, typeof body === 'string' ? body : ''); }
  function caminho(u) { try { var x = new URL(u, location.href); return x.origin === location.origin ? x.pathname + x.search : null; } catch (e) { return null; } }
  window.lzApp = function (m, p, body) { return new Promise(function (ok) { appReq(m, p, body ? JSON.stringify(body) : '', function (st, txt) { var d = null; try { d = JSON.parse(txt); } catch (e) {} ok({ status: st, data: d }); }); }); };
  (function () {
    var X = XMLHttpRequest.prototype, oOpen = X.open, oSend = X.send;
    X.open = function (m, u) {
      this.__lz = null; var p = caminho(u), mm = String(m || 'GET').toUpperCase();
      if (p && doApp(mm, p.split('?')[0])) this.__lz = { m: mm, p: p };
      return oOpen.apply(this, arguments);
    };
    X.send = function (body) {
      if (!this.__lz) return oSend.apply(this, arguments);
      var xhr = this, lz = this.__lz;
      appReq(lz.m, lz.p, body, function (st, txt) {
        var def = function (k, v) { try { Object.defineProperty(xhr, k, { configurable: true, get: function () { return v; } }); } catch (e) {} };
        var parsed = txt; if (xhr.responseType === 'json') { try { parsed = JSON.parse(txt); } catch (e) { parsed = null; } }
        def('readyState', 4); def('status', st); def('statusText', st >= 200 && st < 300 ? 'OK' : 'Erro');
        def('responseText', txt); def('response', parsed); def('responseURL', location.origin + lz.p);
        xhr.getAllResponseHeaders = function () { return 'content-type: application/json; charset=utf-8\r\n'; };
        xhr.getResponseHeader = function (h) { return /content-type/i.test(h) ? 'application/json; charset=utf-8' : null; };
        ['readystatechange', 'load', 'loadend'].forEach(function (t) { try { xhr.dispatchEvent(new ProgressEvent(t)); } catch (e) {} });
      });
    };
    var oFetch = window.fetch;
    window.fetch = function (input, init) {
      var u = typeof input === 'string' ? input : (input && input.url), m = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
      var p = caminho(u);
      if (p && doApp(m, p.split('?')[0])) {
        var body = init && typeof init.body === 'string' ? init.body : '';
        return new Promise(function (ok) { appReq(m, p, body, function (st, txt) { ok(new Response(txt, { status: st, headers: { 'content-type': 'application/json; charset=utf-8' } })); }); });
      }
      return oFetch.apply(this, arguments);
    };
  })();

  // ---- cartoes "deste celular" nas Configuracoes (padrao do site) ----
  var CARD = 'background:#fff;border:1px solid #e6eaf0;border-radius:14px;padding:14px 16px;margin:0 0 14px;font-family:Inter,system-ui,sans-serif';
  var BTN = 'display:inline-flex;align-items:center;justify-content:center;gap:6px;border-radius:10px;font-weight:600;font-size:13px;padding:9px 14px;border:1px solid #00874a;background:#00874a;color:#fff;cursor:pointer';
  function cartao(id, alvo, antes, html) {
    if (!alvo || document.getElementById(id)) return null;
    var d = document.createElement('div'); d.id = id; d.setAttribute('style', CARD); d.innerHTML = html;
    if (antes && antes.parentNode) antes.parentNode.insertBefore(d, antes); else alvo.insertBefore(d, alvo.firstChild);
    return d;
  }
  function selo(ok, sim, nao) { return '<span style="font-size:11px;font-weight:700;padding:3px 10px;border-radius:999px;white-space:nowrap;' + (ok ? 'background:#e0f0e7;color:#066b3d' : 'background:#faebdb;color:#c2691a') + '">' + (ok ? sim : nao) + '</span>'; }
  function cabec(t, s, ok, sim, nao) { return '<div style="display:flex;justify-content:space-between;gap:10px;align-items:flex-start"><div><div style="font-weight:700;font-size:15px;color:#0f172a">' + t + '</div><div style="color:#64748b;font-size:12.5px;margin-top:2px">' + s + '</div></div>' + selo(ok, sim, nao) + '</div>'; }
  var estado = null, estadoEm = 0;
  function comEstado(cb) { if (estado && Date.now() - estadoEm < 8000) return cb(estado); lzApp('GET', '/__app/status').then(function (r) { estado = r.data || {}; estadoEm = Date.now(); cb(estado); }); }
  function cartoesCelular() {
    if (!window.LicitaApp) return;
    var ia = document.querySelector('.aba-provedores-ia');
    if (ia && !document.getElementById('lz-ia')) comEstado(function (s) {
      var c = cartao('lz-ia', ia, null, cabec('Inteligência Artificial deste celular', 'A IA do app roda no próprio celular, com a sua conta ou chave. Nada vai para a VPS.', s.ia, 'EM USO: ' + (s.iaNome || ''), 'NÃO CONFIGURADA') +
        '<div style="margin-top:12px"><button type="button" id="lz-ia-b" style="' + BTN + '">Abrir a IA do celular</button></div>');
      if (c) document.getElementById('lz-ia-b').onclick = function () { lzApp('GET', '/__app/abrir/ia'); };
    });
    var cert = document.querySelector('.cert-head') || document.querySelector('[class*="certificado"]');
    if (cert && /certific/i.test(document.body.innerText.slice(0, 4000)) && !document.getElementById('lz-cert') && location.pathname.indexOf('/configuracoes') === 0) comEstado(function (s) {
      var c = cartao('lz-cert', cert.parentNode || cert, cert, cabec('Certificado A1 deste celular', 'Fica no Android do celular (Configurações › Segurança › Instalar certificado). O robô usa ele para entrar sozinho no Compras.gov. Nada sobe para a VPS.', s.certificado, 'ESCOLHIDO', 'NÃO ESCOLHIDO') +
        '<div style="margin-top:12px;display:flex;gap:8px;flex-wrap:wrap"><button type="button" id="lz-cert-b" style="' + BTN + '">Ver passo a passo</button><button type="button" id="lz-cert-p" style="' + BTN + ';background:#fff;color:#15211b;border-color:#d7dcd9">Entrar no Compras.gov</button></div>');
      if (c) { document.getElementById('lz-cert-b').onclick = function () { lzApp('GET', '/__app/abrir/certificado'); }; document.getElementById('lz-cert-p').onclick = function () { lzApp('GET', '/__app/abrir/portal'); }; }
    });
    var rr = document.querySelector('.rr-wrap');
    if (rr && !document.getElementById('lz-decl')) comEstado(function (s) {
      var d = s.declaracoes || {};
      var KS = [['meEpp', 'Declaração para fornecedores ME/EPP e equiparados'], ['genderEquity', 'Equidade entre mulheres e homens (art. 60, III)'], ['integrity', 'Programa de integridade (art. 60, IV)']];
      var tem = KS.every(function (k) { return d[k[0]] === true || d[k[0]] === false; });
      var linhas = KS.map(function (k) {
        var op = function (v, t) { var on = (v === 'sim' && d[k[0]] === true) || (v === 'nao' && d[k[0]] === false);
          return '<button type="button" data-k="' + k[0] + '" data-v="' + v + '" style="flex:0 0 auto;font-size:12.5px;font-weight:600;padding:6px 14px;border-radius:999px;border:1px solid ' + (on ? '#00874a;background:#00874a;color:#fff' : '#d7dcd9;background:#fff;color:#334155') + '">' + t + '</button>'; };
        return '<div style="border-top:1px solid #f0f2f1;padding:9px 0"><div style="font-size:13px;font-weight:600;color:#1e293b;margin-bottom:6px">' + k[1] + '</div><div style="display:flex;gap:6px">' + op('sim', 'Sim') + op('nao', 'Não') + '</div></div>';
      }).join('');
      var c = cartao('lz-decl', rr, rr.querySelector('.rr-head') ? rr.querySelector('.rr-head').nextSibling : null,
        cabec('Declarações da empresa (neste celular)', 'O robô de proposta do celular usa estas respostas no cadastro da proposta.', tem, 'SALVAS', 'DEFINA') + '<div style="margin-top:8px">' + linhas + '</div>');
      if (!c) return;
      c.querySelectorAll('button[data-k]').forEach(function (b) {
        b.onclick = function () { d[b.dataset.k] = b.dataset.v === 'sim'; lzApp('POST', '/__app/declaracoes', d).then(function () { estado = null; c.remove(); cartoesCelular(); }); };
      });
    });
  }

  var CSS = [
    '#lz-bar{position:fixed;left:0;right:0;bottom:0;height:62px;background:#fff;border-top:1px solid #e5e7eb;',
    'display:grid;grid-template-columns:repeat(5,1fr);z-index:998;font-family:Inter,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;',
    'box-shadow:0 -4px 14px rgba(0,0,0,.05)}',
    '#lz-bar button{all:unset;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px;',
    'font-size:10.5px;font-weight:600;color:#64748b;cursor:pointer;-webkit-tap-highlight-color:transparent}',
    '#lz-bar button.on{color:#066b3d}',
    '#lz-bar button.on svg{background:#e0f0e7;border-radius:999px;padding:3px 14px;box-sizing:content-box}',
    '#lz-bar svg{width:20px;height:20px;padding:3px 14px;box-sizing:content-box}',
    '.layout-content{padding-bottom:84px !important}',
    '.mobile-menu-btn{display:none !important}',
    '.header-search{display:none !important}',
    '.layout-content table{display:block;max-width:100%;overflow-x:auto}',
    // Inicio: numeros em 2 colunas (como o modelo B), mais compactos
    '.dashboard-kpis{grid-template-columns:repeat(2,minmax(0,1fr)) !important;gap:10px !important}',
    '.dashboard-kpis .kpi-card{padding:12px 14px !important;min-width:0}',
    '.dashboard-kpis .kpi-label{font-size:10.5px !important;letter-spacing:.04em}',
    '.dashboard-kpis .kpi-value{font-size:22px !important}',
    // cabecalho fixo: titulo centralizado na altura (a regra geral "-header" do site o empurrava para o topo)
    '.layout-header{align-items:center !important;flex-wrap:nowrap !important}',
    '.layout-header .header-title{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0;flex:1 1 auto}',
    // a regra do site "[class*=-card]{padding:14px}" pegava as PARTES de dentro dos cartoes (robo-card-top, -numero...)
    // (o proprio cartao, ex.: "robo-card ativo robo-card-vencida", mantem o respiro)
    '.layout-content [class*="-card-"]:not([class*="-card "]):not([class$="-card"]){padding:0 !important}',
    '.robo-card{gap:6px !important}',
    // abas (fases das Minhas Licitacoes etc.): nao encolhem nem sobrepoem; rolam de lado
    '[class*="-tabs"]{overflow-x:auto !important;flex-wrap:nowrap !important;-webkit-overflow-scrolling:touch}',
    '[class*="-tabs"] > *{flex:0 0 auto !important;white-space:nowrap !important}',
    '[class*="-tabs"]{scrollbar-width:none}[class*="-tabs"]::-webkit-scrollbar{display:none}',
    // --- Minhas Licitacoes (modelo B) ---
    '.minhas-page{padding:2px 0 !important}',
    '.minhas-title{font-size:19px !important;margin:2px 0 12px !important}',
    '.minhas-tabs{background:transparent !important;box-shadow:none !important;padding:0 0 2px !important;gap:6px !important;margin-bottom:12px !important}',
    '.minhas-tabs .tab-btn{padding:7px 13px !important;border:1px solid #d7dcd9 !important;border-radius:999px !important;background:#fff !important;font-size:12.5px !important;font-weight:600 !important;color:#334155 !important;gap:6px !important}',
    '.minhas-tabs .tab-btn.active{background:#00874a !important;border-color:#00874a !important;color:#fff !important}',
    '.minhas-tabs .tab-badge{background:#e0f0e7 !important;color:#066b3d !important}',
    '.minhas-tabs .tab-btn.active .tab-badge{background:rgba(255,255,255,.25) !important;color:#fff !important}',
    '.minhas-filters{display:grid !important;grid-template-columns:1fr 1fr;gap:8px !important;margin-bottom:14px !important}',
    '.minhas-filters .filter-search{grid-column:1 / -1;min-width:0 !important;height:44px;border-radius:12px !important;padding:0 12px !important}',
    '.minhas-filters .filter-search input{border:none !important;box-shadow:none !important;outline:none !important;padding:10px 0 !important;background:transparent !important;min-width:0;height:auto !important}',
    '.minhas-filters .filter-select-wrap{min-width:0}',
    '.minhas-filters .filter-select{width:100% !important;height:44px;border-radius:12px !important;font-size:13px !important;padding:10px 28px 10px 12px !important}',
    // lista em cartoes: numero | portal / orgao / objeto / abertura / fase | arquivar
    '.minhas-table-wrap{overflow:visible !important;background:transparent !important;box-shadow:none !important;border:none !important;padding:0 !important}',
    '.minhas-table,.minhas-table tbody{display:block !important;white-space:normal !important;overflow:visible !important}',
    '.minhas-table thead{display:none !important}',
    '.minhas-table tr{display:grid !important;grid-template-columns:minmax(0,1fr) auto;gap:5px 10px;background:#fff;border:1px solid #e5e7eb;border-radius:14px;padding:12px 13px;margin-bottom:10px}',
    '.minhas-table tr.row-vencida{background:#fdf7f7}',
    '.minhas-table td{display:block !important;padding:0 !important;border:none !important;white-space:normal !important;min-width:0;text-align:left !important}',
    '.minhas-table td:nth-child(1){grid-column:1;grid-row:1;font-weight:700;font-size:13.5px;color:#15211b}',
    '.minhas-table td:nth-child(4){grid-column:2;grid-row:1;justify-self:end}',
    '.minhas-table td:nth-child(2){grid-column:1 / -1;grid-row:2;font-size:12px;color:#6b7280}',
    '.minhas-table td:nth-child(3){grid-column:1 / -1;grid-row:3;font-size:12.5px;color:#334155;display:-webkit-box !important;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}',
    '.minhas-table td:nth-child(5){grid-column:1 / -1;grid-row:4;font-size:12px}',
    '.minhas-table td:nth-child(6){grid-column:1;grid-row:5}',
    '.minhas-table td:nth-child(7){grid-column:2;grid-row:5;align-self:center}',
    '.minhas-table .fase-select{width:100% !important;font-size:12.5px !important}',
    // --- Visao Geral: metricas com icone em cima, sem quebrar palavra ("Recebe / ndo") ---
    '.vg-metricas{grid-template-columns:repeat(2,minmax(0,1fr)) !important;gap:8px !important}',
    '.vg-metrica{flex-direction:column !important;gap:6px !important;padding:10px !important;background:#fafbfa !important;border:1px solid #eef0ef !important}',
    '.vg-metrica-ico{width:32px !important;height:32px !important}',
    '.vg-metrica *{word-break:normal !important;overflow-wrap:normal !important}',
    '.vg-metrica-label{font-size:11.5px !important;line-height:1.25}',
    '.vg-metrica-acao{font-size:12px !important}',
    // --- Radar: botoes lado a lado e filtros salvos em botoes que deslizam ---
    '.radar-page{display:block !important}',
    '.radar-sidebar{width:100% !important;min-width:0 !important;max-height:none !important;border:none !important;background:transparent !important;padding:0 !important;overflow:visible !important}',
    '.radar-sidebar .sidebar-header{display:grid !important;grid-template-columns:1fr 1fr;gap:8px !important;padding:0 0 10px !important;border:none !important}',
    '.radar-sidebar .sidebar-header > :first-child:not(button){grid-column:1 / -1}',
    '.radar-sidebar .sync-btn,.radar-sidebar .novo-filtro-btn{width:100% !important;margin:0 !important;justify-content:center;min-height:42px;font-size:13px !important;padding:8px 10px !important}',
    '.radar-sidebar .sidebar-list{display:flex !important;gap:8px;overflow-x:auto !important;overflow-y:hidden !important;max-height:none !important;padding:0 0 8px !important;scrollbar-width:none}',
    '.radar-sidebar .sidebar-list::-webkit-scrollbar{display:none}',
    '.radar-sidebar .filtro-item{flex:0 0 auto !important;display:flex !important;align-items:center;gap:8px;white-space:nowrap;border:1px solid #d7dcd9 !important;border-radius:999px !important;padding:6px 12px !important;background:#fff !important;margin:0 !important}',
    '.radar-sidebar .filtro-item.active{background:#e0f0e7 !important;border-color:#00874a !important;color:#066b3d}',
    '.radar-sidebar .filtro-actions{display:none !important}',
    '.radar-sidebar .filtro-item.active .filtro-actions{display:flex !important;gap:4px}',
    '.radar-main{padding:8px 0 0 !important;overflow:visible !important}',
    '.radar-toolbar-date select{min-width:96px !important}',
    // --- Agenda: calendario de 7 colunas (a regra geral do site o deixava em 1 coluna) ---
    '.agenda-v2{margin:0 !important;padding:0 !important}',
    '.cal-mensal-header,.cal-mensal-grid{grid-template-columns:repeat(7,minmax(0,1fr)) !important;gap:3px !important}',
    '.cal-mensal-weekday{font-size:10px !important;padding:4px 0 !important;text-align:center}',
    '.cal-mensal-grid > *{min-height:46px !important;height:auto !important;padding:3px !important;min-width:0;overflow:hidden}',
    '.cal-mensal-num{font-size:11.5px !important}',
    '.cal-mensal-events{font-size:9px !important;line-height:1.15}',
    '.cal-mensal-events > *{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '.cal-mensal{padding:8px 6px !important}',
    '.agenda-v2-busca{display:flex !important;align-items:center;gap:8px;background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:0 12px !important}',
    '.agenda-v2-busca input{border:none !important;box-shadow:none !important;outline:none !important;background:transparent !important;min-width:0;flex:1}',
    '.agenda-v2-tipos{display:flex !important;flex-wrap:nowrap !important;overflow-x:auto;gap:6px !important;scrollbar-width:none}',
    '.agenda-v2-tipos > *{flex:0 0 auto !important;white-space:nowrap}',
    // ajustes finos: Visao Geral sem margem dupla; filtro do Radar do tamanho do texto; calendario na largura toda
    '.vg-page{padding:2px 0 !important;max-width:100% !important}',
    '.radar-sidebar .filtro-item{width:auto !important;max-width:none !important}',
    '.agenda-v2-content{display:block !important}',
    '.agenda-v2-calendar,.cal-mensal,.cal-semanal{width:100% !important;max-width:100% !important;box-sizing:border-box}',
    // --- Participacoes: resumo em 2 colunas e lista em cartoes ---
    '.participacoes-page{padding:2px 0 !important}',
    '.participacoes-cards{display:grid !important;grid-template-columns:repeat(2,minmax(0,1fr)) !important;gap:8px !important}',
    '.participacoes-card{padding:10px 12px !important;gap:10px !important;min-width:0}',
    '.participacoes-card-icon{width:34px !important;height:34px !important;flex:0 0 auto}',
    '.participacoes-table-wrap{overflow:visible !important;background:transparent !important;box-shadow:none !important;border:none !important;padding:0 !important}',
    '.participacoes-table,.participacoes-table tbody{display:block !important;white-space:normal !important;overflow:visible !important}',
    '.participacoes-table thead{display:none !important}',
    '.participacoes-table tr{display:grid !important;grid-template-columns:minmax(0,1fr) auto;gap:5px 10px;background:#fff;border:1px solid #e5e7eb;border-radius:14px;padding:12px 13px;margin-bottom:10px}',
    '.participacoes-table td{display:block !important;padding:0 !important;border:none !important;white-space:normal !important;min-width:0;text-align:left !important}',
    '.participacoes-table td:nth-child(1){grid-column:1;grid-row:1;font-weight:700;font-size:13.5px}',
    '.participacoes-table td:nth-child(6){grid-column:2;grid-row:1;justify-self:end}',
    '.participacoes-table td:nth-child(2){grid-column:1 / -1;grid-row:2;font-size:12px;color:#6b7280}',
    '.participacoes-table td:nth-child(3){grid-column:1 / -1;grid-row:3;font-size:12.5px;color:#334155;display:-webkit-box !important;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}',
    '.participacoes-table td:nth-child(4){grid-column:1;grid-row:4}',
    '.participacoes-table td:nth-child(5){grid-column:2;grid-row:4;justify-self:end;font-weight:700;font-variant-numeric:tabular-nums}',
    '.participacoes-table td:nth-child(7){grid-column:1 / -1;grid-row:5;font-size:12px;color:#6b7280}',
    // --- Licitacao aberta: textos e datas dentro da tela ---
    '.detalhe-page,.detalhe-v2{padding:2px 0 !important;max-width:100% !important;overflow-x:hidden}',
    '.aba-visao-geral,.detalhe-tab-content-v2{min-width:0;max-width:100%;overflow-x:hidden}',
    '.visao-geral-grid{grid-template-columns:minmax(0,1fr) !important}',
    '.visao-geral-objeto,.visao-geral-valor,.visao-geral-item{white-space:normal !important;overflow-wrap:anywhere;min-width:0;max-width:100%}',
    '.visao-geral-valor{font-size:13.5px !important}',
    '.visao-geral-actions{display:flex !important;flex-wrap:wrap;gap:8px}',
    '.detalhe-header-v2{gap:8px !important}',
    '.detalhe-header-titulo{min-width:0}',
    '.detalhe-meta-row{flex-wrap:wrap !important;gap:6px 10px !important}',
    '.participacoes-cards,.radar-cards{padding:0 !important}',
    '.participacoes-card *{word-break:normal !important;overflow-wrap:normal !important}',
    '.participacoes-card-label{font-size:11.5px !important;line-height:1.25}',
    // abas em botoes arredondados (IA Studio, Configuracoes, Participacoes, licitacao)
    '.ia-tabs,.config-tabs,.participacoes-tabs,.detalhe-tabs-v2{background:transparent !important;box-shadow:none !important;border:none !important;padding:0 0 2px !important;gap:6px !important;margin-bottom:12px !important}',
    '.ia-tabs > *,.config-tabs > *,.participacoes-tabs > *{padding:7px 13px !important;border:1px solid #d7dcd9 !important;border-radius:999px !important;background:#fff !important;font-size:12.5px !important;font-weight:600 !important;color:#334155 !important}',
    '.ia-tabs > .active,.config-tabs > .active,.participacoes-tabs > .active{background:#00874a !important;border-color:#00874a !important;color:#fff !important}',
    // --- IA Studio: nada passa da largura da tela ---
    '.ia-studio,.ia-tab-content,.chat-messages{min-width:0 !important;max-width:100% !important;overflow-x:hidden !important;box-sizing:border-box}',
    '.chat-messages .empty-state{white-space:normal !important;text-align:center;padding:0 8px}',
    '.chat-input-area{display:flex !important;gap:8px;align-items:stretch}',
    '.chat-input-area input,.chat-input-area textarea{min-width:0 !important;flex:1 1 auto;width:auto !important}',
    '.chat-input-area button{flex:0 0 auto;white-space:nowrap}',
    // --- Documentos: lista em cartoes (nome | acoes / categoria | status) ---
    '.documentos-page{padding:2px 0 !important}',
    '.doc-section{padding:12px !important}',
    '.doc-table-wrapper{overflow:visible !important;border:none !important;box-shadow:none !important}',
    '.doc-table,.doc-table tbody{display:block !important;white-space:normal !important;overflow:visible !important}',
    '.doc-table thead{display:none !important}',
    '.doc-table tr{display:grid !important;grid-template-columns:minmax(0,1fr) auto;gap:6px 10px;align-items:center;border:1px solid #e5e7eb;border-radius:12px;padding:10px 12px;margin-bottom:8px;background:#fff}',
    '.doc-table td{display:block !important;padding:0 !important;border:none !important;white-space:normal !important;min-width:0}',
    '.doc-table td:nth-child(1){grid-column:1;grid-row:1;font-weight:600}',
    '.doc-table td:nth-child(4){grid-column:2;grid-row:1 / span 2;align-self:center}',
    '.doc-table td:nth-child(2){grid-column:1;grid-row:2;display:flex !important;gap:6px;flex-wrap:wrap}',
    '.doc-table td:nth-child(3){grid-column:1;grid-row:3}',
    '.cert-grid{grid-template-columns:minmax(0,1fr) !important}',
    // --- Configuracoes ---
    '.config-page{padding:2px 0 !important}',
    '.portal-actions{display:grid !important;grid-template-columns:1fr 1fr;gap:8px !important}',
    '.portal-actions .portal-btn{width:100% !important;justify-content:center;margin:0 !important}',
    // textos longos (avisos tecnicos, codigos) quebram dentro do cartao
    '.layout-content pre,.layout-content code{white-space:pre-wrap !important;word-break:break-word}',
    '.layout-content [class*="aviso"],.layout-content [class*="alert"],.layout-content [class*="motivo"]{overflow-wrap:anywhere;min-width:0}',
    // selos/etiquetas nao quebram no meio da palavra ("ATIV / O")
    '[class*="badge"],[class*="-tag"],[class*="pill"],[class*="status-badge"]{white-space:nowrap !important;word-break:normal !important;flex:0 0 auto}',
    // caixa de marcar ao lado do texto ("Somente recebendo proposta")
    'label > input[type=checkbox],label > input[type=radio]{width:18px !important;height:18px;flex:0 0 auto !important;margin:0 !important}',
    'label:has(> input[type=checkbox]),label:has(> input[type=radio]){display:inline-flex !important;align-items:center;gap:8px;width:auto !important}'
  ].join('');

  var I = {
    inicio: '<path d="M3 11l9-8 9 8v10a1 1 0 01-1 1h-5v-6h-6v6H4a1 1 0 01-1-1z"/>',
    pesquisar: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/>',
    disputa: '<path d="M13 2L4 14h7l-1 8 9-12h-7z"/>',
    minhas: '<rect x="3" y="7" width="18" height="13" rx="2"/><path d="M8 7V5a2 2 0 012-2h4a2 2 0 012 2v2"/>',
    mais: '<path d="M4 7h16M4 12h16M4 17h16"/>'
  };
  var ITENS = [
    { k: 'inicio', t: 'Início', to: '/', ativo: function (p) { return p === '/' || p === '/visao-geral'; } },
    { k: 'pesquisar', t: 'Pesquisar', to: '/pesquisar', ativo: function (p) { return p.indexOf('/pesquisar') === 0 || p.indexOf('/radar') === 0; } },
    { k: 'disputa', t: 'Disputas', to: '/disputa', ativo: function (p) { return p.indexOf('/disputa') === 0; } },
    { k: 'minhas', t: 'Minhas', to: '/minhas', ativo: function (p) { return p.indexOf('/minhas') === 0 || p.indexOf('/licitacao') === 0; } },
    { k: 'mais', t: 'Mais', to: null, ativo: function () { return false; } }
  ];

  function ir(to) {
    var a = document.querySelector('.sidebar-nav a[href="' + to + '"]');
    if (a) { a.click(); return; }
    try { history.pushState({}, '', to); window.dispatchEvent(new PopStateEvent('popstate')); } catch (e) { location.href = to; }
  }
  function abrirMenu() { var b = document.querySelector('.mobile-menu-btn'); if (b) b.click(); }

  function montar() {
    if (!document.body) return;
    try { cartoesCelular(); } catch (e) {}
    var logado = !!document.querySelector('.layout-sidebar');
    var bar = document.getElementById('lz-bar');
    if (!logado) { if (bar) bar.remove(); return; }
    if (!document.getElementById('lz-css')) {
      var st = document.createElement('style'); st.id = 'lz-css'; st.textContent = CSS; document.head.appendChild(st);
    }
    if (!bar) {
      bar = document.createElement('nav'); bar.id = 'lz-bar';
      ITENS.forEach(function (it) {
        var b = document.createElement('button'); b.type = 'button'; b.dataset.k = it.k;
        b.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + I[it.k] + '</svg>' + it.t;
        b.onclick = function () { if (it.to) ir(it.to); else abrirMenu(); };
        bar.appendChild(b);
      });
      document.body.appendChild(bar);
    }
    var p = location.pathname;
    ITENS.forEach(function (it) { var b = bar.querySelector('[data-k="' + it.k + '"]'); if (b) b.classList.toggle('on', it.ativo(p)); });
  }

  function iniciar() {
    montar();
    try { new MutationObserver(function () { montar(); }).observe(document.body, { childList: true, subtree: true }); } catch (e) {}
    window.addEventListener('popstate', montar);
    setInterval(montar, 700);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', iniciar); else iniciar();
})();
""".trimIndent()
}
