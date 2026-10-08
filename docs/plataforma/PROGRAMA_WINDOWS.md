# Programa Windows LicitaPRO (F5) - decisao e plano

## 1. Decisao: Electron (shell do site + modulo local de robo)
| Criterio | (a) Shell: Electron / Tauri / WebView2 | (b) Compose Desktop + Kotlin |
|---|---|---|
| Reuso do site React | total (abre a mesma URL; zero retrabalho) | nenhum, reescreve telas |
| Reuso do app Kotlin | nenhum | parcial (logica; UI Android nao serve) |
| Certificado A1 do Windows | Chromium usa o store do SO (evento select-client-certificate) | navegador embutido (JCEF) fraco para isso |
| Robo existente | e Node/navegador na VPS: portavel quase direto | reescrita |
| Toolchain neste PC | Node 24 ok; sem Rust nem .NET | JDK 17 ok |
| Auto-update | electron-updater + GitHub Releases pronto | a construir |

Recomendacao: **(a) com Electron**. Tauri exigiria instalar Rust e o robo precisaria de sidecar Node; WebView2 puro exigiria .NET. Electron traz Chromium (necessario para o portal + A1), roda o robo em Node (mesmo ecossistema do robo da VPS) e atualiza via GitHub Releases (requisito do projeto). Custo: instalador ~80 MB, aceitavel.

## 2. Arquitetura
- **Janela principal**: BrowserWindow carrega o site (SITE_URL). Se offline, tela local. Site detecta `window.licitaproDesktop` e mostra o painel do robo.
- **Login unico**: o usuario loga no site (JWT da plataforma). O site passa o token ao shell via `licitaproDesktop.setToken()`; o robo usa o mesmo token na API. Token apenas em memoria/safeStorage do Electron.
- **Modulo do robo** (processo main, `src/robot`): lista certificados do store do Windows (CurrentUser\My), responde ao `select-client-certificate`, abre janela de navegador no portal, executa proposta/lance, reporta eventos a API. Certificado e senha nunca saem do PC (regra SDD 6). Antes de agir, pede a trava "um robo por licitacao" (F4).
- **Atualizacao**: electron-updater, GitHub Releases, dialogo "Atualizar agora / Depois" com notas da versao.
- **Seguranca**: contextIsolation, sandbox, preload expondo so 5 metodos.

## 3. Plano (4-5 dias)
1. (feito) Esqueleto: janela, preload, updater, stub do robo, README. Em `C:\Users\DESKTOP\Documents\licitapro-windows\`.
2. Dia 1-2: listar certs do Windows (PowerShell), selecao de certificado, teste com A1 em portal de homologacao.
3. Dia 2-3: portar o robo da VPS (proposta/lance/captcha) para `src/robot`.
4. Dia 3-4: ligar a API: login/token, lock e eventos (**aguardando contrato F1** - CONTRATO_API.md ainda nao existe), escolha local vs nuvem (F4).
5. Dia 4-5: instalador NSIS, publicar release no GitHub, teste de auto-update, rebrand com logo (F6).

## 4. Pendencias
- Contrato F1 (login, lock, eventos); dominio final do site/API (config.js); repo GitHub (owner em package.json); icone do F6; assinatura de codigo (SmartScreen).
