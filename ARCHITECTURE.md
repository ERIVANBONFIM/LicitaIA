# Arquitetura — LicitaIA

## Visão geral

Clean Architecture + MVVM, Kotlin, Jetpack Compose (Material 3), Hilt, Room, DataStore, Coroutines/Flow,
Navigation Compose. O app é **mobile-first e offline-first**: tudo da primeira entrega roda no aparelho.
Integrações externas (portais e IA) ficam atrás de contratos (`PortalConnector`, `AIProvider`) e, nesta
versão, são servidas por implementações mock.

```
┌──────────────────────────────── app ────────────────────────────────┐
│ MainActivity · AppRoot (drawer, bottom bar, NavHost, lock, toasts) │
└──────┬───────────────┬───────────────┬──────────────┬───────────────┘
       │ feature-auth  │ feature-radar │ feature-live │ feature-settings  …(12 features)
       └───────────────┴───────┬───────┴──────────────┴───────────────┘
                               │ só interfaces
                ┌──────────────▼──────────────┐
                │          core-domain        │  modelos · repositórios · LiveSessionManager
                │  (Kotlin puro, sem Android) │  Rbac · BidRuleEngine · AuctionSimulator · scoring
                └──────┬──────────────┬───────┘
          ┌────────────▼───┐    ┌─────▼──────────┐
          │ connector-api  │    │ ai-provider-api│
          └──────┬─────────┘    └─────┬──────────┘
          connector-mock        ai-provider-mock · core-ai (OpenAI/Anthropic/Gemini/Custom)
                     core-data (Room/DataStore) · core-security (Keystore) · core-network (OkHttp)
```

Regra de dependência: features dependem apenas de `core-ui` (que expõe `core-domain`). As implementações
são injetadas por Hilt (`SingletonComponent`) a partir de `core-data`, `core-ai`, `core-security`,
`feature-bidding` (motor) e `feature-live` (notificador). `app` agrega tudo.

## Módulos

| Módulo | Tipo | Conteúdo |
|---|---|---|
| `app` | Android app | `LicitaApplication` (Hilt, WorkManager), `MainActivity` (FragmentActivity p/ biometria, FLAG_SECURE, rota de notificação), `shell/` (AppRoot, DrawerContent, LockScreen, ShellViewModel) |
| `core-domain` | Kotlin JVM | `model/` (enums e data classes), `repository/Repositories.kt` (todas as interfaces), `live/LiveSessionManager.kt` (+ `LiveSessionStore`), `security/Rbac.kt`, `bidding/` (BidRuleEngine, AuctionSimulator, DemoSessionSpecs), `scoring/` (OpportunityScorer, RadarMatcher), `util/Formatters.kt`, `demo/DemoAccount.kt` |
| `core-ui` | Android lib | `theme/` (LicitaTheme, LicitaColors), `components/` (cards, badges, botões, estados, diálogos, scaffold, sino), `nav/` (Routes, AppNavigator, ShellState) |
| `core-data` | Android lib | Room (`LicitaDatabase`, 16 entidades, DAOs, conversores kotlinx.serialization), DataStore (`AppSettings`, sessão lembrada), `DatabaseSeeder`, implementações de todos os repositórios, `SessionHolder` |
| `core-security` | Android lib | `KeystoreCipher` (AES-256-GCM, chave no Android Keystore), `SecretStore`, `PasswordHasher` (PBKDF2-SHA256) |
| `core-network` | Android lib | `OkHttpClient` (TLS, timeouts, logging redigido só em debug), `Json` |
| `core-ai` | Android lib | `LlmBackedProvider` + `OpenAiProvider`, `AnthropicProvider`, `GeminiProvider`, `CustomProvider`, `DefaultAiGateway` (empresa → global → mock) |
| `ai-provider-api` | Kotlin JVM | `AIProvider`, `AiGateway`, DTOs (`TenderAnalysisRequest`, `ProposalDraft`, `MessageDraft`, `DocumentComparison`) |
| `ai-provider-mock` | Kotlin JVM | `MockAIProvider` + `TenderHeuristics` (determinístico) |
| `connector-api` | Kotlin JVM | `PortalConnector`, `ConnectorRegistry`, `LiveSessionHandle`, `PortalLiveEvent`, `HumanConfirmation` |
| `connector-mock` | Kotlin JVM | `MockPortalConnector` (4 portais), `MockCatalog`, `MockLiveSession` (fila de eventos por sessão), `MockConnectorRegistry` |
| `feature-auth` | Android lib | Login / criar conta local |
| `feature-dashboard` | Android lib | Dashboard, Notificações |
| `feature-radar` | Android lib | Buscar, Radar (lista/edição/resultados) |
| `feature-tender` | Android lib | Interesse, Analisar, Participações, Resumo, Análise, Vale a pena?, Proposta, PDF (`AndroidProposalPdfGenerator`, FileProvider) |
| `feature-documents` | Android lib | Cofre de documentos |
| `feature-live` | Android lib | Pregões ao Vivo, Sessão, WebView, `AppNotifierImpl` (canais e push) |
| `feature-bidding` | Android lib | `LiveSessionManagerImpl` (motor), Robô, Configurar Robô, Estratégia, Simulador, binding `ConnectorRegistry` |
| `feature-warroom` | Android lib | Sala de Guerra |
| `feature-settings` | Android lib | Configurações, Provedor de IA, Segurança, Portais, Empresas e Perfis |
| `feature-audit` | Android lib | Auditoria |
| `feature-messages` | Android lib | Mensagens do Pregoeiro |
| `feature-competition` | Android lib | Concorrência |

## Camadas

- **UI (Compose)** — uma tela = um `@Composable` + `@HiltViewModel`. Estado em `StateFlow`, coletado com
  `collectAsStateWithLifecycle`. Toda tela logada usa `LicitaScaffold` (barra superior com menu/voltar,
  empresa ativa sempre visível e sino). Navegação via `LocalAppNavigator` e constantes em `Routes`.
- **Domínio** — modelos imutáveis (`data class`), enums com rótulos pt-BR, contratos de repositório
  baseados em `Flow`, `Rbac` (matriz perfil → permissões), lógica pura de lances e scoring (testável em JVM).
- **Dados** — Room como fonte da verdade local; DataStore para preferências; mapeadores entidade ↔ domínio;
  seed no primeiro uso; auditoria registrada pelos repositórios nas ações que executam.
- **Integrações** — `ConnectorRegistry` resolve `PortalConnector` por portal; `AiGateway` resolve `AIProvider`.

## Fluxos principais

### "Tenho Interesse" → análise → proposta → aprovação → envio simulado
`OpportunityRepository.search` (conectores) → `TenderRepository.markInterest` (salva, registra edital,
dispara `analyze` em escopo de aplicação; status `EM_ANALISE` → `ANALISADA`) → `AIProvider.analyzeTender`
(extração, `FitScore`, recomendação, faixa de preço, checklist) → `ProposalRepository.generateDraft` →
versões → `approve` (RBAC `APROVAR_PROPOSTA`) → `simulateSubmission` após `BindingConfirmDialog`
(RBAC `APROVAR_ENVIO`) → auditoria `ENVIO`.

### Pregões ao vivo e robô (SIMULAÇÃO)
`LiveSessionManagerImpl` mantém **um ator (coroutine) por sessão** com estado, `BidRule`, fila de eventos
do conector (`LiveSessionHandle.events`), log (`BidEvent`) e controle de CAPTCHA próprios. Nada é
compartilhado entre sessões. A cada evento, `BidRuleEngine.decide` produz `Place / Suggest /
RequestAuthorization / Wait / StopAtFloor / Blocked` conforme modo e estratégia; `validateBid` é o único
caminho de qualquer lance (manual ou robô) e rejeita valor abaixo do piso efetivo
(`max(piso, custo − limite de perda)`), item errado, sessão errada ou CAPTCHA pendente.
As sessões são persistidas em `LiveSessionStore` e restauradas em `restoreOrSeed(companyId)` com o robô
PAUSADO (nunca retoma sozinho). Sem sessões, cria as 3 de demonstração.

### CAPTCHA / MFA
`PortalLiveEvent.CaptchaRequired` → a sessão vai para `CAPTCHA_PENDENTE`, robô `BLOQUEADO_CAPTCHA`,
`AppNotifier.notify(CAPTCHA, crítico)` → sino vermelho pulsante, toast in-app, push local com vibração;
o alerta repete a cada `AppSettings.captchaRepeatMinutes` (0 desativa) até `confirmCaptchaResolved`
(ação do usuário, após resolver manualmente na WebView) ou pausa manual. Só a sessão afetada para.

### Sala de Guerra / emergência
`pauseAllRobots(reason)` pausa todas as automações, mantém as sessões abertas e registra `EMERGENCIA`.

## Navegação
Rotas em `core-ui/.../nav/Navigation.kt` (`Routes`). Cada feature expõe `fun NavGraphBuilder.<x>Graph()`;
`app/shell/AppRoot.kt` monta o `NavHost`. Destinos em `Routes.topLevel` mostram o ícone de menu e a barra
inferior (Início, Radar, Pregões, Robô, Mais). Toque em notificação do sistema chega como extra `"route"`
na `MainActivity` e é navegado após login/desbloqueio.

## Persistência
Room (`exportSchema=false`, migração destrutiva no MVP): Company, UserProfile, Radar, Opportunity (cache),
Tender, TenderAnalysis, Document, Proposal, PortalSession, LiveSession (+BidRule), BidEvent, Notification,
AuditEvent, AIConfig, AuctioneerMessage, CompetitionRecord. DataStore: `AppSettings`, sessão lembrada.
Segredos (hash de senha/PIN, chaves de IA, credenciais de portal): `SecretStore` cifrado pelo Keystore.

## Multiempresa e RBAC
`AuthSession(user, activeCompany)` em `AuthRepository.session`. Quase todo fluxo é escopado por `companyId`
e reage a `switchCompany` (`flatMapLatest`). `Rbac.can(role, permission)` governa ações sensíveis
(aprovar piso/envio, alterar regras, configurar IA, gerenciar empresas…); a UI mostra o motivo quando bloqueia.

## Decisões técnicas
- **Kotlin JVM puro** para domínio, contratos e mocks → testes rápidos sem emulador.
- **Sem backend obrigatório** na primeira entrega (SDD §2).
- **Sem versões de API privadas**: os adapters de IA usam só endpoints públicos documentados; os portais
  ficam em mock até validação técnica/contratual (SDD §10).
- **Rotas como String** (Navigation 2.8) por simplicidade entre módulos independentes.
- `compileSdk 34` por compatibilidade com o SDK já instalado na máquina (platform 34 / build-tools 34.0.0);
  as versões das bibliotecas foram escolhidas para esse compileSdk.
