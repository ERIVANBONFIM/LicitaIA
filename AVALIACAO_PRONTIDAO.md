# Avaliação de prontidão — LicitaIA v0.1.1-mock (06/10/2026)

Objetivo: uso pessoal com dados reais + modo demonstração isolado para futuros clientes.
Método: auditoria do código-fonte (não das telas), com evidências `arquivo:linha`. Nada foi alterado.

Legenda: **PV** pronto e validado · **IS** implementado sem validação em uso real · **MK** mock/simulado · **NI** não implementado.

## Resumo executivo

- O app é um **produto de demonstração completo**: todas as telas e fluxos existem, mas **nenhum dado de licitação é real**.
  Os 4 "portais" são um único conector mock (`feature-bidding/.../di/BiddingModule.kt:26` injeta `MockConnectorRegistry`;
  não há outra implementação de `PortalConnector`). Não existe nenhuma chamada HTTP fora dos provedores de IA.
- O que já funciona com dados reais **seus**: cadastro local da sua empresa como ADMIN, cofre de documentos com anexos,
  propostas com PDF real, radares salvos, auditoria, PIN/biometria, chaves de IA no Keystore e chamadas reais a
  OpenAI/Anthropic/Gemini (implementadas, não validadas com chave).
- **Bloqueadores para uso real hoje**: (1) a conta demo (senha pública) pode editar/excluir empresas reais e vincular
  usuários reais; (2) qualquer atualização que mude o banco **apaga todos os dados**; (3) não há como cadastrar/analisar
  um edital real; (4) sem assinatura de release e sem backup; (5) alertas morrem quando o app é fechado.

## Tabela por área

| Área | Status | O que funciona com dados reais (evidência) | O que continua simulado | O que falta / depende de você |
|---|---|---|---|---|
| **Login local** | PV | Hash PBKDF2-SHA256 120k iter., salt, comparação constante (`core-security/PasswordHasher.kt:20-43`); criar conta cria empresa + ADMIN (`AuthRepositoryImpl.kt:78-119`); sessão lembrada por ids (`SettingsRepositoryImpl.kt:85-120`). Validado por testes unitários e abertura no aparelho. | — | Validar dígitos do CNPJ no cadastro (função existe e não é usada). |
| **Login Google** | IS | Credential Manager real, só ID token, nonce de 32 bytes, valida `aud`/`nonce`/`exp` (`GoogleIdTokenClaims.kt:26-32`); usuário entra sem empresa/sem ADMIN (`AuthRepositoryImpl.kt:154-172`). Compilado e instalado; fluxo no seletor de contas **ainda não exercitado**. | — | **Assinatura do token não é verificada por ninguém** (`GoogleIdTokenClaims.kt:12-14`) → precisa de backend (ver §1). Vínculo automático Google→conta local por e-mail (`:151-153`) deve exigir senha. Não há "criar minha empresa" via Google. |
| **Empresas e perfis** | IS | CRUD em Room, matriz RBAC (`Rbac.kt`), vínculo "Aguardando vínculo". | — | **Sem posse**: `observeCompanies` devolve todas (`CompanyRepositoryImpl.kt:34-35`); `upsertCompany/deleteCompany/upsertUser` não checam sessão nem RBAC (`:39-135`). Empresa não tem flag `demo` (`Entities.kt:31-40`). |
| **Separação demo/real** | NI (vazamentos) | Só `UserEntity.demo` existe (`Entities.kt:56`); `switchCompany` valida `companyIds` (`AuthRepositoryImpl.kt:191-194`). | Seed roda sempre que o banco está vazio (`DatabaseSeeder.kt:58-64`) — impossível desligar. | Demo ADMIN vê e pode **editar/excluir empresas reais**, **vincular usuários reais** a qualquer empresa/perfil (`CompaniesViewModel.kt:213-218`); auditoria "todas as empresas" é global (`AuditRepositoryImpl.kt:24-26`); seletor de login lista todas as empresas antes de autenticar (`LoginScreen.kt:184`). Ver §2. |
| **Radar / busca** | MK | Radares persistem em Room; score heurístico local (`OpportunityScoring.kt:90-112`). | Oportunidades vêm de catálogo fixo de ~35 itens fictícios (`MockCatalog.kt:44-99`). Sem Worker em segundo plano (nenhum `Worker` no projeto); a UI promete alerta que não existe (`RadarResultsScreen.kt:96`). | Fonte real: API pública de consulta do **PNCP** (Lei 14.133) e dados abertos do Compras.gov.br — verificar documentação oficial e termos; `PeriodicWorkRequest` para rodar radares e notificar. |
| **Tenho Interesse** | IS/MK | Persistência, status, checklist (`TenderRepositoryImpl.kt`). | "Edital registrado" = ter URL (`:82`); texto do edital é template "CONTEÚDO FICTÍCIO" (`MockCatalog.kt:171-198`). | **Não há cadastro manual de licitação nem importação do PDF do edital** → não dá para analisar um edital real. |
| **Análise de edital / IA** | IS | Chamadas HTTP reais com endpoints/headers corretos (OpenAI `Providers.kt:52-53`, Anthropic `:86-87`, Gemini `:141-142`); chave cifrada no Keystore e lida só na chamada (`AiCredentials.kt:187-200`); sem logs de chave; `testConnection` real. | Resultado da IA é **mesclado sobre heurística**: `requiredDocuments`, riscos, aderência documental/geográfica/prazo e `overall` são sempre locais (`LlmBackedProvider.kt:46-96, 260-268`). Fallback silencioso para mock se o provedor falhar (`TenderRepositoryImpl.kt:161-177`). | Sua chave de API (Configurações → IA). Trocar modelo padrão do Gemini (`gemini-1.5-flash` descontinuado). Banner explícito de fallback. Extração de texto do PDF do edital. |
| **Documentos / cofre** | IS | CRUD real, anexos via `OpenDocument` com permissão persistente (`DocumentEditScreen.kt:106-110`), status de validade real, comparação com o cofre real (`TenderRepositoryImpl.kt:155-156`). | Lista de documentos exigidos é inferida por segmento, não do edital. | Alerta de vencimento só no dashboard (sem Worker). Anexos não sobrevivem a reinstalação/backup. OCR NI. |
| **Propostas / PDF** | IS | PDF real `PdfDocument` com empresa, CNPJ, itens, totais (`AndroidProposalPdfGenerator.kt`), FileProvider correto, versões e aprovação com RBAC **no repositório** (`ProposalRepositoryImpl.kt:124,151,194`). | Envio: `simulateSubmission` → protocolo `SIM-…` do mock (`MockPortalConnector.kt:124-130`). Marca d'água "SIMULAÇÃO" no PDF. | Envio real depende de portal (§4). `update(proposal)`/`attachPdf` sem RBAC (`:101-103,169`). |
| **Portais** | MK (todos) | Abrir site público no WebView/Custom Tabs com HTTPS forçado e allowlist (`WebViewScreen.kt:86-96`) — funciona com rede real, **não testado no aparelho**. | `authenticate` aceita qualquer credencial (`MockPortalConnector.kt:67-75`); a senha é cifrada no Keystore e **nunca usada** (`PortalAndMessageRepositories.kt:70`); status CONECTADO é fictício. | Ver §4. Allowlist `gov.br` ampla demais; isolamento de cookies por empresa (`MULTI_PROFILE`) cai em fallback silencioso (`:152-156`). |
| **Pregões ao vivo** | MK | Persistência/restauração de sessões em Room (`SimpleRepositories.kt:135-161`; `LiveSessionManagerImpl.kt:102-119`); ao reabrir, robô volta PAUSADO (`:160-162`). | Lances, cronômetro, concorrentes e mensagens vêm de `MockLiveSession` com `Random` (`MockLiveSession.kt:84-140`); ao restaurar, o simulador reinicia. | Tempo real depende de portal (§4). Sem foreground service: processo morto = tudo para. |
| **Robô de lances** | IS (lógica) / MK (execução) | `BidRuleEngine.validateBid` é o caminho único e nunca viola o piso (`BidRuleEngine.kt:156-162`; 17 testes, 20k casos aleatórios). | Lance é simulado; mock **ignora** `HumanConfirmation` (`MockPortalConnector.kt:144-151`); motor fabrica a confirmação sozinho (`LiveSessionManagerImpl.kt:690`). | Confirmação humana e RBAC (piso/regras) só na UI — motor não checa (`:178, 641-643`). |
| **CAPTCHA / MFA** | MK | Pausa só a sessão afetada (`:472-486`); repetição respeita setting e para ao resolver (`:488-513`). | Detecção só por evento mock ou botão "Simular CAPTCHA". | **O app não tem como detectar CAPTCHA num portal real** (nenhuma heurística no WebView). Com portal real, o fluxo seria: usuário vê o CAPTCHA no WebView e resolve; o app só pausa automação (que não existe ainda). Pausar robô cancela alertas mesmo com CAPTCHA pendente (`:570`). |
| **Sala de Guerra** | IS | Contadores derivam do motor; "Pausar todos" audita `EMERGENCIA` (`:193-214`). | Dados do motor são mock. | `pauseAllRobots` atinge sessões de **todas** as empresas (`:195`) e runtimes da empresa anterior seguem rodando após troca. |
| **Mensagens do pregoeiro** | MK | Envio exige APROVADA + RBAC no repositório (`PortalAndMessageRepositories.kt:184-208`); IA real se houver chave. | Mensagens geradas pelo mock; envio só muda status. | Depende de portal (§4). |
| **Auditoria** | IS | 67 pontos de registro; DAO sem delete (append-only lógico, `Daos.kt:274-286`); export texto. | 27 eventos fictícios do seed misturados. | Falhas de gravação engolidas (`AuditRepositoryImpl.kt:82`); sem hash/encadeamento; SQLite em claro editável. |
| **Notificações** | IS | 8 canais, permissão Android 13+, toque abre rota (`AppNotifierImpl.kt`, `MainActivity.kt:58-62`). | — | Só com processo vivo; sem FCM. |
| **Segurança local** | PV | Keystore AES-256-GCM não exportável (`KeystoreCipher.kt:45-61`), PIN/biometria/timeout aplicados (`ShellViewModel.kt:88-136`, `LockScreen.kt`), FLAG_SECURE. | — | Banco Room **em claro**; sem limite de tentativas de PIN; PIN é do aparelho, não por usuário. |
| **Release / atualização / backup** | NI | R8 compila (`app-release-unsigned.apk`), regras ProGuard. | — | Sem `signingConfigs`; release **nunca executado**; `fallbackToDestructiveMigration` + `exportSchema=false` (`DataModule.kt:77`, `LicitaDatabase.kt:14-15`) → **bump de versão apaga tudo**; `allowBackup=false` + regras excluem tudo e não há export/import → perda do aparelho = perda total; release (`com.licitaia.app`) instala como **app separado** do debug (`.debug`), sem migrar dados. |

## Pontos especiais

### 1. Login Google — assinatura, sessão e permissões
- Validado no cliente: `aud` = Web Client ID, `nonce` anti-replay, `exp`, `iss`. **Não validado**: assinatura RS256 (exige chaves públicas do Google ou backend). Como toda a autorização é local e o banco não é cifrado, a fronteira de segurança hoje é **o aparelho**, não o token: um APK modificado já controla o banco inteiro. Isso é aceitável para uso pessoal em aparelho próprio; **não** para múltiplos clientes.
- Sessão: só `userId`/`companyId` em DataStore; restaurada sem reautenticar. Permissões: usuário Google entra como `LICITACOES` sem empresa; só um ADMIN vincula. Risco: qualquer ADMIN pode conceder qualquer perfil/empresa sem validação de posse.
- Caminho recomendado (SECURITY.md): Firebase Authentication (Google) + Firestore como fonte de usuários/empresas/perfis, ou backend próprio verificando o token. Ponto de troca: `AuthRepository.loginWithGoogle`.

### 2. Separação demo/real — hoje NÃO está garantida
Brechas confirmadas: demo ADMIN (senha pública) lista, edita e exclui empresas reais (com cascata em radares/documentos/propostas), vincula usuários Google reais a qualquer empresa/perfil, vê a auditoria global; usuários reais veem empresas e eventos demo; o seed demo é obrigatório. Correção: flag `demo` em **empresa** e em todos os dados por empresa; "modo do aparelho" (DEMO ou REAL) em DataStore; seed só em modo DEMO; repositórios filtram por `session.companyIds`/`demo`; nenhuma operação cruzando `demo ≠ demo`; conta demo sem `GERENCIAR_EMPRESAS` fora das empresas demo.

### 3. Primeiro administrador real — sem depender da conta demo
Hoje, já é possível: tela de login → **"Criar conta local"** (nome, e-mail, senha, razão social, CNPJ) → cria **sua empresa** com você como **ADMIN** (`AuthRepositoryImpl.kt:78-119`). Depois: Empresas e Perfis → vincular sua conta Google ("Aguardando vínculo") à sua empresa como ADMIN; a partir daí, entre por Google ou senha. Limitação: via Google **não** há "criar empresa" (fica pendente); e, num aparelho novo sem conta local, só a demo poderia vincular — por isso o plano prevê o fluxo "Criar minha empresa" após o login Google (vira ADMIN dela) e desativar a demo em modo REAL.

### 4. Portais — avaliação individual (Compras.gov.br, BLL, Licitanet, Portal de Compras Públicas)
Os quatro compartilham a mesma classe mock; diferem só em `capabilities` (`MockPortalConnector.kt:189-248`). Por nível:
| Nível | Situação hoje | O que seria necessário (sem inventar APIs, sem contornar CAPTCHA/MFA) |
|---|---|---|
| (a) Abrir o site e **logar manualmente** no WebView/Custom Tabs | IS: carrega `publicUrl` real com HTTPS; cookies persistem no perfil do WebView; isolamento por empresa só se o aparelho suportar `MULTI_PROFILE`. Não testado. | Testar no aparelho os hosts reais de login de cada portal (subdomínios), ajustar allowlist, decidir se Custom Tabs (sessão do Chrome) é melhor para MFA. Verificar termos de uso sobre WebView embutida. Viável agora. |
| (b) **Buscar oportunidades** | MK | **PNCP** tem API pública de consulta documentada (cobre pregões de todos os portais integrados à Lei 14.133); Compras.gov.br tem dados abertos. BLL/Licitanet/PCP: verificar se publicam API pública; sem isso, o PNCP já cobre a descoberta. Implementar `PortalConnector` real com OkHttp, cache offline, rate limit. |
| (c) **Acompanhar pregão** (lances, cronômetro, mensagens) | MK | Só com API autenticada oficial ou automação de navegador **autorizada** pelo portal. Alternativa honesta imediata: acompanhar **manualmente** no WebView dentro do app, com o LicitaIA registrando lances/piso/margem que você digita (telemetria assistida). |
| (d) **Executar ações** (proposta, lance, resposta) | MK | Idem (c), mais contrato/termos. Até lá: ações permanecem simuladas, e o app pode preparar (PDF, valores, checklist) para você executar manualmente no portal. CAPTCHA/MFA sempre resolvidos por você. |

### 5. Operação no celular
Persistência: sessões/eventos/propostas/documentos em Room — ok. Recuperação após fechar: sessões voltam com robô PAUSADO (correto), mas o simulador reinicia; no mundo real exigiria reconectar ao portal. **Perda de conexão**: hoje nada usa rede exceto IA (erros viram mensagem) e WebView (tela de erro). **Segundo plano**: motor roda só enquanto o processo vive; sem foreground service/WorkManager → notificação de CAPTCHA/autorização não chega com app fechado. **Isolamento entre pregões**: real (estado, fila, log, regra e CAPTCHA por sessão); entre **empresas** não (runtimes e "pausar todos" cruzam empresas).

### 6. Versão para uso pessoal
Falta assinatura de release (keystore fixo — também pré-requisito da atualização via GitHub), migrations do Room (hoje destrutivas), backup/exportação cifrada e restauração, teste do APK release (R8) no aparelho. Atenção: release e debug são apps distintos; migre para o release antes de inserir dados reais.

## Plano por prioridade

**P0 — Pré-requisitos para colocar dados reais (1ª semana)**
1. Keystore de release fixo + `signingConfigs` via `gradle.properties` externo; smoke test do APK release no aparelho.
2. Room: `exportSchema=true`, remover `fallbackToDestructiveMigration`, migrations incrementais + teste de migração.
3. Modo do aparelho **REAL/DEMO**: flag `demo` em empresa e dados; seed só em DEMO; repositórios filtram por posse (`session.companyIds`) e por `demo`; conta demo nunca administra dados reais; ocultar "Entrar com conta demo"/seletor global em modo REAL.
4. "Criar minha empresa" após login Google (vira ADMIN); exigir senha local para vincular Google a conta existente.
5. Backup/exportação cifrada (Keystore) + importação validada; manter `allowBackup=false`.
6. **Atualização via GitHub Releases**: `UpdateChecker` (release `latest`, compara `versionCode`), diálogo "Atualizar agora / Depois" com as notas da release, download do APK e instalador (`REQUEST_INSTALL_PACKAGES`). Depende do keystore fixo (item 1) e do repositório GitHub.

**P1 — Tornar o núcleo útil com dados reais (2ª–3ª semanas)**
7. Cadastro manual de licitação + importação do PDF do edital com extração de texto (PdfRenderer/ML Kit) → análise de IA sobre texto real; incluir `requiredDocuments`/riscos no merge da IA; banner explícito de fallback; modelo Gemini atual.
8. Conector real de **descoberta** via PNCP (e dados abertos Compras.gov.br): busca, radar em `PeriodicWorkRequest` com notificação de novas oportunidades; alerta de vencimento de documentos em segundo plano.
9. RBAC e confirmação humana **nos repositórios/motor** (piso, regras, IA, empresas, documentos, `update(proposal)`), não só na UI; `pauseAllRobots` e runtimes por empresa.
10. Concorrência alimentada por resultados reais (ao marcar VENCIDA/PERDIDA) e importação do histórico.

**P2 — Operação ao vivo assistida (4ª semana em diante)**
11. WebView por portal validado em aparelho (hosts de login, cookies, `MULTI_PROFILE`), com "modo assistido": você opera no portal, o app registra lances/piso/margem e alerta; CAPTCHA/MFA sempre manuais.
12. Foreground service "pregões em andamento" para manter alertas com app em segundo plano.
13. Hardening: SQLCipher, hash encadeado na auditoria, limite de tentativas de PIN, allowlist estrita.

**P3 — Automação integrada (somente com autorização dos portais)**
14. API autenticada oficial ou automação formalmente autorizada por portal; robô supervisionado real com `HumanConfirmation` obrigatória no conector; publicação na loja só depois.

## Ações que dependem de você
- Criar e guardar o **keystore de release** (senhas em `gradle.properties` fora do repo) — ou autorizar que eu gere um e você faça o backup.
- Criar o **repositório GitHub** (público ou privado + token) para as releases/atualizações.
- Chaves de IA reais em Configurações → Provedor de IA (OpenAI/Anthropic/Gemini) para validar a análise.
- Decidir a fonte de descoberta (PNCP + Compras.gov.br dados abertos) e verificar os termos de uso de cada portal para WebView/automação.
- Executar os roteiros de teste no aparelho (TESTING.md), inclusive o login Google ponta a ponta.
- Para múltiplos clientes: escolher o backend de identidade (recomendado Firebase Auth) — não necessário para uso pessoal.

---

## Atualização — 06/10/2026 (após as frentes PNCP, edital real, atualização/isolamento)

Resolvidos: P0 1 (release assinável via propriedades externas), P0 2 (migrations 1→2→3, validada no aparelho),
P0 3 parcialmente (demo desativada e posse/RBAC nos repositórios — o modo DEMO isolado ainda não foi reintroduzido),
P0 4 ("Criar minha empresa" pós-Google; vínculo automático por e-mail bloqueado), P0 5 (backup cifrado), P0 6
(atualização via GitHub Releases), P1 7 (cadastro manual + PDF do edital + IA sobre texto real, sem fallback
silencioso), P1 8 (PNCP + radar em segundo plano), P1 9 (RBAC/posse nos repositórios; sessões por empresa).
Login Google: assinatura RS256 agora verificada no aparelho (JWKS do Google). Build: 148 testes, lint 0 erros,
release R8 OK (não assinado). Pendentes: validação no aparelho dos fluxos novos, modo DEMO isolado, operação
assistida de pregões, OCR, hardening (P2/P3).
