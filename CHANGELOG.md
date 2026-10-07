# Changelog — LicitaIA

Formato baseado em Keep a Changelog. Versões seguem SemVer.

## [0.5.2] — 2026-10-07

### Alterado
- "Tenho interesse" não dispara mais análise por IA: a licitação abre com os dados da fonte e a análise só roda
  ao tocar em "Analisar com IA" (opção em Configurações > IA para voltar ao automático).
- "Descartar" virou "Arquivar"; licitações de interesse também podem ser arquivadas (dados preservados).

### Adicionado
- Menu "Licitações arquivadas" com busca, Desarquivar e Abrir.
- Concorrentes reais: em "Vale a pena participar?" a concorrência abre a lista com nome, CNPJ, vitórias, valor e
  desconto médios (resultados públicos do PNCP, mesmo órgão e segmento) e "Buscar concorrentes agora".
- "Abrir no portal" (Compras.gov.br no navegador do app, indo até a compra; BLL/Licitanet/PCP pelo link oficial)
  e "Ver no PNCP", na licitação e no card da busca.
- Arquivos oficiais da licitação (edital, termo de referência, anexos…) com Baixar, Abrir, Copiar link e Compartilhar.
- Pergunte ao edital lê o Edital e o Termo de Referência inteiros (OCR de todas as páginas) e envia a base completa
  quando cabe; mostra a cobertura lida.

## [0.5.1] — 2026-10-07

### Adicionado
- Busca do 1º dia do mês atual em diante (licitações, pregões e dispensas, inclusive sem disputa) com filtro
  Todas · Pregão · Dispensa · Concorrência/Outras. A lista mostra de hoje em diante; os dias anteriores somem
  sozinhos na virada do dia e voltam com "Mostrar dias anteriores" ou pela barra de pesquisa.
- Situação oficial no cartão e na licitação: SUSPENSA, CANCELADA/REVOGADA/ANULADA, DESERTA/FRACASSADA e
  ADIADA (com a data nova e a anterior). Robô não inicia sozinho em compra suspensa/cancelada.
- UASG em todas as licitações ("48/2026 · UASG 160123"), na tela da licitação, nos itens, no PDF e no robô;
  pesquisa por número da UASG.
- Descartar licitação (com desfazer e "Descartadas"), selo "Nova" e filtro "Só novas", filtro de período
  (Hoje, 7 dias, 30 dias, personalizado).
- Aviso de mudança de fase nas licitações acompanhadas: suspensa, revogada/anulada, adiada, resultado.
- Atualização do app: verifica ao abrir/voltar ao app (a cada 30 min); se tocar em "Depois", fica a faixa
  "Nova versão disponível · Atualizar" até instalar. A verificação manual nas Configurações continua.

## [0.5.0] — 2026-10-07

### Adicionado
- Busca diária às 05:30 (horário configurável): ao abrir o app as licitações já estão baixadas; a tela lê o cache
  e não baixa tudo a cada abertura. Limpeza automática de canceladas, encerradas e de meses anteriores já fechadas
  (preserva as de interesse, analisadas, com proposta ou no robô).
- Robô do Comprasnet: lê "Minhas participações" (Em andamento e Propostas, favoritas marcadas), cadastra a proposta
  item a item em "Cadastrar propostas" (seletores mapeados nas telas reais), confere o "Operação realizada com
  sucesso!", nunca marca declarações legais; robô de lance manual/automático com piso, 20 s/3 s e PARAR.
  "Robô do Comprasnet" direto na tela da licitação (licitação nova, sem precisar separar antes no portal).
- Proposta comercial pela IA a partir dos itens oficiais do edital (PNCP/Compras.gov.br), preço nunca acima do
  estimado, sigiloso marcado; "Atualizar valores do edital"; atalho "Aprovar e liberar para o portal" (Admin);
  itens liberados vão para o plano do robô.
- PDF da proposta reescrito: dados completos da empresa (endereço, contato, representante legal, dados bancários),
  planilha com nº do item do edital, total por extenso, declarações, assinatura e "Página X de Y".
- Cadastro da empresa: endereço, contato, representante legal e dados bancários.
- "Pergunte ao edital": perguntas livres sobre TODOS os documentos da licitação (edital, TR, anexos, ETP), resposta
  com fonte (documento/página), histórico gravado; aba "Itens" com detalhe completo do item, órgão e local.
- Documentos da empresa: anexar PDF, foto ou galeria; OCR lê tipo, emissor, código, CNPJ, emissão e validade;
  alertas 15 e 3 dias antes de vencer.
- Concorrência com resultados públicos do PNCP (diário) e ranking do segmento; Estratégias viram a configuração
  real do robô; Sala de Guerra e Mensagens com dados reais.

### Corrigido
- Provedor de IA: o provedor logado (conta ChatGPT/Google) ou com chave é usado em todo o app; análises antigas
  heurísticas são refeitas com a IA.
- Atalho "Compras eletrônicas" (menu do portal desenhado por script e token enviado em nova janela).
- hCaptcha invisível do Compras eletrônicas não é mais tratado como CAPTCHA.
- Página do portal não trava mais em "carregando" quando não está na tela; selo Logado/Deslogado real.
- Busca: ordenação por nota, baixa aderência oculta, indicador de atualização.

### Removido
- Simulador e textos de "modo simulação" nas telas operacionais (demonstração continua isolada).

## [0.4.3-personal] — 2026-10-06

### Corrigido
- Radar/Busca Compras.gov.br vazio: PNCP consultava só propostas que encerravam hoje (`dataFinal=hoje`); agora
  hoje+60 dias, 2 páginas por modalidade no filtro por plataforma, pausa entre páginas e resultado parcial em 429.
  Conector Compras.gov.br lê até 900 itens e amplia a janela para 60 dias. Teste real (todas as UFs, Telecom,
  score ≥ 60): de 1 para 21 resultados. Segmento Telecom não casa mais "radio" solto.
- Sessão dos portais: um único WebView retido por empresa+portal (o sessionStorage da SPA do Compras eletrônicas
  sobrevive ao sair e voltar da tela); o "manter sessão ativa" recarrega essa mesma aba.

### Adicionado
- Login com **certificado digital A1** instalado no Android (seletor do sistema, escolha lembrada por empresa/host,
  "Trocar certificado digital" no menu do portal) + ajuda em Portais.
- Linha "Obtidos das fontes: PNCP N · Compras.gov.br N" na busca e no radar.
## [0.4.2-personal] — 2026-10-06

### Corrigido
- Radar/Busca: licitações publicadas pelo Compras.gov.br apareciam rotuladas "PNCP". Agora cada licitação do PNCP é
  classificada pela plataforma de origem (`usuarioNome`/`linkSistemaOrigem`): Compras.gov.br, BLL, Licitanet,
  Portal de Compras Públicas; demais mostram "via PNCP · <plataforma>". Licitanet/BLL/PCP passam a ter busca real.
- Portais: sem internet a sessão não é mais marcada como encerrada; a recarga automática pausa e retoma sozinha.

### Adicionado
- Busca em tempo real: atualização automática a cada 2 min com a tela aberta, puxar para atualizar, radar em
  segundo plano a cada 15 min (antes 6 h).
- Aviso global "Sem internet" e telas que dependem de rede falham na hora com aviso claro.
- Room v7 (coluna `platformName`).
## [0.4.1-personal] — 2026-10-06

### Corrigido
- Portais: a página "Não autorizado — sua sessão pode ter expirado" do Compras.gov.br agora marca "Sessão expirada"
  (detecção pelo texto visível, sem ler formulários nem cookies); sem falso "expirada" durante o login.

### Adicionado
- Portais voltam para a última página da área logada (ex.: Compras eletrônicas / Minhas participações).
- **"Manter sessão ativa"** (opcional, por portal): recarrega a sua página em segundo plano a cada 5/8/12/15 min para
  evitar queda por inatividade; se o portal encerrar a sessão, envia um alerta único com atalho para entrar de novo.
## [0.4.0-personal] — 2026-10-06

### Adicionado
- **Entrar com ChatGPT** (Sign in with ChatGPT, fluxo oficial da OpenAI para apps pessoais/open-source): OAuth 2.0 +
  PKCE S256 com registro dinâmico (`dynamic_agent_client` → `client_id` emitido), `ext_agent_host_id`, retorno em
  `http://127.0.0.1:<porta>/auth/callback` dentro do app com Custom Tab e serviço em 1º plano durante o login,
  id_token validado pelo JWKS, escopo `chatgpt.tokens.use.direct` obrigatório, chamadas na Responses API com a
  assinatura do usuário, renovação automática e "Desconectar" com revogação. Chave de API continua disponível.
- **Modo demonstração isolado** ("Explorar demonstração"): empresa e dados de exemplo separados, selo
  "DEMONSTRAÇÃO", sair/reiniciar; nunca mistura com dados reais.
- **Vínculo Google ↔ conta local** com confirmação da senha local.
- **Chaves de IA por empresa** com fallback "padrão do aparelho" (migration 5→6).
- **Banco de dados cifrado (SQLCipher)** com chave no Android Keystore; migração automática do banco existente com
  cópia verificada (contagem de linhas + integridade) antes de substituir — nada é apagado em caso de falha.
- **Serviço em primeiro plano** mantém cronômetro e alertas dos pregões assistidos com o app em segundo plano.
- Alertas de vencimento de documentos sem exigir rede; atalho "Acompanhar pregão" na licitação; tela de IA sem
  botões desabilitados; URLs oficiais de login da Licitanet e do Portal de Compras Públicas.
- Restauração de backup preserva o tipo dos anexos e re-encadeia a auditoria.

### Decisão documentada
- Sem certificate pinning: os certificados dos portais e provedores rotacionam e o pinning quebraria o app; o
  app usa HTTPS obrigatório com as autoridades do sistema.

## [0.3.0-personal] — 2026-10-06

### Adicionado
- **Busca real no Compras.gov.br** (`connector-comprasgov`, API pública de dados abertos `dadosabertos.compras.gov.br`:
  contratações Lei 14.133 por modalidade/UF e legado Lei 8.666), consolidada com o PNCP e **deduplicada** pelo número
  de controle PNCP; 28 testes com fixtures reais.
- **Pregões ao Vivo em modo assistido**: acompanhar pregão (de licitação de interesse ou manual), registrar nosso
  lance (recusado abaixo do piso) e o melhor concorrente, sugestão de próximo lance (copiar), posição, cronômetro,
  abrir portal com sessão salva, alertas (margem mínima, proximidade do piso, cronômetro < 60 s), encerrar com
  resultado → registro em Concorrência e status VENCIDA/PERDIDA; RBAC por ação (operar / piso / regras);
  Sala de Guerra com contadores reais e "Pausar alertas de todas". Robô permanece desligado (sem API autorizada).
- **OCR no aparelho** (ML Kit) para editais escaneados, automático ou manual, com progresso por página.
- **Concorrência com resultados reais**: registro ao marcar vitória/derrota e formulário manual; exclusão.
- **Segurança**: limite de tentativas de PIN com bloqueio progressivo; auditoria com hash encadeado e verificação
  de integridade (migration 4→5); portais mostram capacidades reais; mensagem clara quando o build não está
  cadastrado no Google Cloud.
- Compras.gov.br no navegador interno: entrada oficial do fornecedor → gov.br → seleção de empresa → abre em
  "Compras eletrônicas"; sessão salva.
- Build debug renomeado "LicitaIA Dev".

### Alterado
- APK maior (~50 MB release) por causa do modelo OCR embutido.

## [0.2.0-personal] — 2026-10-06

Preparação para uso pessoal com dados reais. Parte das mudanças foi feita por outra ferramenta (Codex) e
revisada/integrada aqui (ver `PASSAGEM_PARA_CLAUDE.md`).

### Adicionado
- **Busca real no PNCP** (`connector-pncp`): API pública de consulta (`/v1/contratacoes/proposta`,
  `/v1/contratacoes/publicacao`, detalhe por órgão/ano/sequencial, arquivos/itens), paginação, mapeamento para
  `Opportunity`, cache offline, `Portal.PNCP`; 20 testes com fixtures reais.
- **Edital real**: cadastro manual de licitação (`tender/new`), importação do PDF do edital (pdfbox-android,
  ≤300 páginas, detecção de PDF escaneado) ou texto colado, armazenado em `filesDir/editais/`; análise de IA
  recebe o texto real (recorte priorizando habilitação/documentos/prazos/garantias/penalidades); a IA passa a
  preencher documentos exigidos, riscos e pontos críticos (`TenderAnalysis.aiFields`); **sem fallback silencioso**
  (falha do provedor = erro auditado; provedor MOCK rotulado "Heurística local (sem IA)" com banner).
- **Atualização via GitHub Releases**: `UpdateChecker` (tag `v<versionName>+<versionCode>`), diálogo
  "Atualizar agora / Depois" com notas da release, download com progresso, instalador do Android,
  `REQUEST_INSTALL_PACKAGES`, item "Verificar atualizações" em Configurações. `LICITAIA_UPDATE_REPO`.
- **Login Google**: verificação da assinatura RS256 do ID token contra o JWKS oficial do Google (falha sem rede);
  "Criar minha empresa" para usuário Google pendente (vira ADMIN dela); vínculo automático por e-mail bloqueado.
- **Backup cifrado** da empresa ativa (PBKDF2-HMAC-SHA256 210k + AES-256-GCM, senha ≥ 12) com restauração por
  CNPJ, remapeamento de IDs e anexos; aceita backups de schema anterior.
- **Migrations** Room 1→2→3 (`exportSchema=true`, schemas em `core-data/schemas`), sem migração destrutiva.
- **Release assinável** via `LICITAIA_RELEASE_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`.
- `PersonalAlertsWorker`: radares e vencimento de documentos a cada 6 h com rede e sessão lembrada.
- Posse por empresa + RBAC nos repositórios (`RepositoryAccess`), inclusive busca/radar e licitações.

- **Provedores de IA — "Entrar com conta"**: Gemini via OAuth Google (`cloud-platform`, token cifrado no Keystore,
  renovação silenciosa, "Desconectar", projeto Google Cloud opcional); OpenAI/Anthropic com "Obter chave de API"
  (login para terceiros indisponível nesses provedores). Migration 3→4 (`ai_configs.authMode/oauthAccount/cloudProject`).
- **Portais com login manual e sessão persistente**: WebView interna por portal (`portal/{portal}`), allowlist de hosts
  oficiais, cookies persistentes por empresa (perfis `androidx.webkit` quando suportado), Autofill do Android,
  detecção heurística de "Sessão aberta/expirada" sem ler credenciais, "Sair do portal". Diálogo usuário/senha removido.
- Modelos padrão atualizados (`gemini-3.8-flash`, `gpt-6-luna`); configurações antigas com modelos descontinuados
  passam a exibir o padrão atual.
- Mensagens de atualização sem o nome do repositório.

### Alterado
- Seed de demonstração desativado; conta demo recusada; botão "Entrar com conta demo" removido.
- Portais sem API: WebView mock substituída por abertura das páginas oficiais em Custom Tabs (login manual).
- Sessões ao vivo fictícias e robô bloqueados; envio simulado de proposta/mensagem passa a informar indisponibilidade.
- `versionCode 3`, `versionName 0.2.0-personal`. R8: `-dontwarn com.gemalto.jp2.**` (pdfbox).

### Conhecido
- Pregões ao vivo/robô/CAPTCHA sem integração real; OCR não implementado; `CompanyBackupRepository` exige
  validação no aparelho; adapters de IA ainda não validados com chave real pelo autor.

## [0.1.1-mock] — 2026-10-05

### Adicionado
- **Entrar com Google** via Credential Manager (`androidx.credentials` 1.3.0 + `googleid` 1.1.1), só ID token
  (sem escopos de Gmail). Estados de carregando, cancelado, erro, sem conta Google, build não configurado e
  "conta identificada, acesso pendente" (com "Verificar acesso" e "Sair da conta Google").
- `AuthRepository.loginWithGoogle`: identifica o usuário sem conceder empresa nem perfil admin; vincula a conta
  local real de mesmo e-mail verificado; recusa e-mails de contas demo. Auditoria em todos os desfechos.
- `UserProfile.provider` / `demo`; Room v2 (`users.provider`, `externalId`, `demo`).
- Empresas e Perfis: seção **"Aguardando vínculo"** para o administrador vincular contas identificadas,
  escolhendo o perfil (`CompanyRepository.observeUnassignedUsers`).
- "Sair" limpa o estado de credencial do Google (`IdentitySignOut`).
- Configuração por `LICITAIA_GOOGLE_SERVER_CLIENT_ID` (local.properties / propriedade Gradle / env) → `BuildConfig`.
- Testes: `GoogleIdTokenClaimsTest` (7) e 5 novos casos em `LoginViewModelTest`.

### Alterado
- Banco de dados recriado na atualização (migração destrutiva do MVP): dados locais anteriores são perdidos.

## [0.1.0-mock] — 2026-10-05

Primeira entrega: aplicativo Android completo funcionando com dados MOCK, sem backend e sem chaves de IA.

### Adicionado
- Projeto Gradle multi-módulo (23 módulos) com Kotlin 2.0.20, AGP 8.5.2, Compose BOM 2024.09.03, Hilt 2.52,
  Room 2.6.1, DataStore, Navigation Compose, WorkManager, OkHttp/Retrofit, androidx.webkit, biometric.
- Domínio e contratos: modelos, repositórios, `PortalConnector`, `AIProvider`, `LiveSessionManager`,
  `Rbac`, `BidRuleEngine`, `AuctionSimulator`, scoring de oportunidades.
- Design system premium dark (azul/verde, vermelho crítico, amarelo alerta), componentes e microanimações.
- Shell: login, menu lateral (todos os itens do SDD §4.1), barra inferior, sino com estado crítico, toasts
  in-app, bloqueio por PIN/biometria/timeout, proteção de captura de tela, rota via notificação.
- Radar de Licitações (CRUD completo) e Busca com filtros por portal; "Tenho Interesse".
- Licitações de Interesse: resumo, análise do edital por IA, "Vale a pena participar?", score de aderência,
  análise de risco, checklist, Analisar Edital, Minhas Participações.
- Cofre de documentos com validade/vencimento/anexos/tags.
- Proposta comercial com versões, PDF gerado no aparelho, visualizador, compartilhamento, revisão, aprovação,
  rejeição e envio SIMULADO com confirmação dupla.
- Pregões ao Vivo com 3 sessões mock paralelas isoladas, telemetria, lance manual, autorização, PARAR E ASSUMIR.
- Robô de lances em SIMULAÇÃO (3 modos, 4 estratégias, piso/redução/margem/limite/intervalo/limiar).
- Workflow de CAPTCHA/MFA manual com alerta repetitivo configurável (1/3/5/10/15 min/desativado).
- WebView por sessão (domínio, cadeado, allowlist, Custom Tabs, file chooser, download, erro, perfis).
- Sala de Guerra com "PAUSAR TODOS OS ROBÔS"; Estratégia; Simulador de pregão.
- Mensagens do pregoeiro com resumo/resposta por IA e envio simulado; Concorrência; Auditoria completa.
- Multiempresa, perfis RBAC (Admin, Diretoria, Licitações, Financeiro, Técnico), Empresas e Perfis.
- Camada de IA intercambiável: Mock, OpenAI, Anthropic, Gemini, API personalizada; chaves no Keystore.
- Conectores mock para Compras.gov.br, BLL, Licitanet e Portal de Compras Públicas (~47 oportunidades).
- Seed realista (2 empresas, 8 usuários, documentos, radares, licitações, mensagens, auditoria, concorrência).
- Testes unitários JVM (domínio, scoring, motor de lances, simulador, mock de IA, conectores, segurança).
- Documentação: README, ARCHITECTURE, SECURITY, TESTING, ROADMAP, CHANGELOG.

### Segurança
- Nenhuma chave/senha hardcoded (exceto conta demo documentada, armazenada como hash).
- HTTPS obrigatório; logs HTTP redigidos e só em debug; `allowBackup=false`.
- CAPTCHA/MFA nunca são resolvidos pelo app; toda ação vinculante exige confirmação humana e é simulada.

### Conhecido
- Nenhum teste em dispositivo físico foi executado nesta versão (sem aparelho/emulador no ambiente de build).
- `gradlew test` falha no Windows quando o caminho contém "ç"; use `subst L:` (ver README).
- Adapters de IA reais não validados com chaves reais.
