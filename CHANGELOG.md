# Changelog — LicitaIA

Formato baseado em Keep a Changelog. Versões seguem SemVer.

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
