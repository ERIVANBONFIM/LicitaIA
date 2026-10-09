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
