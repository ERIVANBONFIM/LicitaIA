# LicitaIA — Android (Kotlin + Jetpack Compose)

Aplicativo Android para centralizar o ciclo operacional de licitações públicas e pregões eletrônicos:
radar de oportunidades, análise de edital com IA, "Vale a pena participar?", cofre de documentos,
proposta comercial em PDF com aprovação humana, pregões ao vivo em sessões isoladas, robô de lances
supervisionado (modo SIMULAÇÃO), workflow de CAPTCHA/MFA manual, Sala de Guerra, mensagens do pregoeiro,
concorrência, auditoria, multiempresa e perfis de acesso.

> **Versão atual (v0.2.0-personal):** preparada para **uso pessoal com dados reais**: busca real de licitações na
> API pública do **PNCP**, cadastro manual + importação do PDF do edital para análise por IA, backup cifrado da
> empresa, atualização automática via GitHub Releases e login Google com assinatura do token verificada. Os dados
> de demonstração (seed) foram desativados. Lances, propostas e mensagens aos portais **continuam sem integração**
> (não há API oficial autenticada): o app prepara, você executa no portal. Nenhuma ação vinculante é enviada.

Especificação: [SDD_LicitaIA.md](SDD_LicitaIA.md) · Prompt de execução: [PROMPT_MESTRE_CLAUDE.md](PROMPT_MESTRE_CLAUDE.md)

Documentação: [ARCHITECTURE.md](ARCHITECTURE.md) · [SECURITY.md](SECURITY.md) · [TESTING.md](TESTING.md) ·
[ROADMAP.md](ROADMAP.md) · [CHANGELOG.md](CHANGELOG.md)

---

## APK gerado

| Item | Valor |
|---|---|
| Caminho | `app\build\outputs\apk\debug\app-debug.apk` |
| Caminho absoluto | `C:\Users\DESKTOP\Documents\app licitaçao\app\build\outputs\apk\debug\app-debug.apk` |
| applicationId | `com.licitaia.app.debug` |
| versão | `0.2.0-personal-debug` (versionCode 3, build 06/10 02:24) |
| minSdk / targetSdk | 26 (Android 8.0) / 34 |
| Assinatura | debug keystore padrão do Android SDK |

## Instalar no celular

1. No celular: **Configurações → Sobre o telefone → toque 7× em "Número da versão"** para liberar as
   Opções do desenvolvedor; depois ative **Depuração USB**.
2. Conecte o cabo USB e aceite o aviso "Permitir depuração USB" no celular.
3. No PC (PowerShell, na pasta do projeto):

```powershell
C:\Android\platform-tools\adb.exe devices
C:\Android\platform-tools\adb.exe install -r "app\build\outputs\apk\debug\app-debug.apk"
```

Se `adb` já estiver no PATH, basta:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Instalação manual (sem ADB):** copie `app-debug.apk` para o celular (cabo, Drive, WhatsApp "Documento"),
abra o arquivo no gerenciador de arquivos e autorize "Instalar apps desconhecidos" para o app que abriu.

**Primeiro acesso (sem conta demo):** na tela de login use **"Criar conta local"** (nome, e-mail, senha, razão
social, CNPJ válido) — isso cria a sua empresa com você como **Administrador**. Depois, em Empresas e Perfis, vincule
sua conta Google ("Aguardando vínculo") ou entre direto por Google e use "Criar minha empresa". A conta demo
`demo@licitaia.app` foi desativada nesta versão (login recusado); um modo demonstração isolado para clientes está no ROADMAP.

## Entrar com Google

A tela de login tem o botão **"Entrar com Google"** (Credential Manager / *Sign in with Google*, usando a
conta Google do próprio celular). Ele pede **somente o ID token** — nenhum acesso a Gmail, Drive ou contatos.

Regras de acesso:
- O login Google **identifica** o usuário (cria um registro local com nome/e-mail/`sub`), mas **não concede
  empresa nem perfil de administrador**. Até que um administrador vincule a conta em
  **Empresas e Perfis → "Aguardando vínculo"** (escolhendo o perfil), a tela mostra "Conta identificada,
  acesso pendente", com "Verificar acesso" e "Sair da conta Google".
- E-mails de contas de demonstração são recusados no login Google; os dados demo ficam separados das contas reais.
- Uma conta local real com o mesmo e-mail (verificado pelo Google) é vinculada à identidade Google, mantendo
  seu perfil e empresas.
- "Sair" (menu lateral) também limpa o estado de credencial do Google, para o próximo login mostrar o seletor de contas.

### Configuração externa obrigatória (sem ela o botão aparece desabilitado)

1. No [Google Cloud Console](https://console.cloud.google.com/) → *APIs e serviços → Tela de consentimento OAuth*:
   crie a tela (tipo Externo), com os escopos padrão `openid`, `email`, `profile` apenas. Enquanto estiver em
   "Teste", adicione os e-mails dos testadores.
2. *Credenciais → Criar credenciais → ID do cliente OAuth*:
   - **Tipo "Aplicativo da Web"** → copie o *Client ID* (`xxxx.apps.googleusercontent.com`). É o
     `serverClientId` usado pelo app e, no futuro, a audiência que o backend deve verificar.
   - **Tipo "Android"**, um para cada build:
     - teste: nome do pacote `com.licitaia.app.debug` + SHA-1 do keystore de debug
       (`keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android`);
     - produção: nome do pacote `com.licitaia.app` + SHA-1 do keystore de release (e, se publicar na Play,
       também o SHA-1 da *Assinatura de apps do Google Play*).
3. No PC, em `local.properties` (não versionado) ou como propriedade/variável de ambiente:
   ```
   LICITAIA_GOOGLE_SERVER_CLIENT_ID=xxxx.apps.googleusercontent.com
   ```
4. Recompile (`.\gradlew.bat assembleDebug`) e reinstale. O celular precisa ter Google Play Services e ao
   menos uma conta Google adicionada.

Sem backend, o app valida localmente audiência, nonce e expiração do ID token, mas **não verifica a assinatura**
(isso exige as chaves públicas do Google). Antes de produção, leia a recomendação de backend em
[SECURITY.md](SECURITY.md#login-google-e-backend-de-identidade).

## Provedores de IA — chave de API ou "Entrar com conta"

Em **Configurações → Provedor de IA**, cada provedor tem o seletor **Autenticação**: *Chave de API* ou *Entrar com conta*.
Chaves e tokens ficam cifrados no Android Keystore e nunca aparecem em logs ou na auditoria (só o e-mail da conta).

| Provedor | Chave de API | Entrar com conta |
|---|---|---|
| **Google Gemini** | Sim ("Obter chave de API" abre o Google AI Studio) | **Sim — conta Google** (OAuth 2.0, escopo `cloud-platform`) |
| OpenAI / ChatGPT | Sim ("Obter chave" → <https://platform.openai.com/api-keys>) | Não: "Sign in with ChatGPT" está em beta restrito a parceiros; não há OAuth público para apps de terceiros |
| Anthropic / Claude | Sim ("Obter chave" → <https://console.anthropic.com/settings/keys>) | Não: a Anthropic só oferece chave de API |
| API personalizada | Sim | Não |

### Gemini com conta Google — como funciona

O app usa o Google Identity Services (`AuthorizationClient`) para pedir à conta Google do celular um **token de acesso**
com o escopo `https://www.googleapis.com/auth/cloud-platform` e chama a API Gemini com `Authorization: Bearer`
(documentação oficial: <https://ai.google.dev/gemini-api/docs/oauth>). O token dura ~1 h: fica cifrado no Keystore com o
instante de expiração e é **renovado silenciosamente** (sem tela) antes de cada chamada; se o Google exigir nova interação,
a análise falha com a mensagem "Autorização Google expirada — toque em 'Entrar com conta Google' novamente".
**Cota e cobrança vão para o projeto Google Cloud do cliente OAuth** (o seu), não para uma chave do app. "Desconectar"
revoga o token (endpoint oficial `oauth2.googleapis.com/revoke`, melhor esforço) e o apaga do cofre; a permissão também pode
ser removida em <https://myaccount.google.com/permissions>.

Configuração externa (no mesmo projeto Google Cloud do login Google, seção acima):
1. *APIs e serviços → Biblioteca*: **ativar a "Generative Language API"** (opcionalmente também "Vertex AI API").
2. *Tela de consentimento OAuth → Escopos*: adicionar `https://www.googleapis.com/auth/cloud-platform` (escopo
   sensível/restrito — enquanto o app estiver em modo **Teste**, só os usuários de teste cadastrados conseguem autorizar).
3. Manter o **cliente OAuth Android** do pacote (`com.licitaia.app.debug` / `com.licitaia.app`) com o SHA-1 correto —
   sem ele o Google devolve "erro 10 (DEVELOPER_ERROR)".
4. Se a API responder 403 pedindo projeto de cota, preencha **"Projeto Google Cloud (ID)"** no card do Gemini: o app envia
   o cabeçalho `x-goog-user-project` com esse ID (a conta precisa ter permissão `serviceusage.services.use` nele).
5. "Testar conexão" funciona nos dois modos e confirma `via conta Google` na mensagem de sucesso.

## Atualizações pelo GitHub

O app não vem da loja: ele se atualiza por **GitHub Releases**. Ao abrir o app logado (no máximo a cada 6 h)
e em **Configurações → Sobre → Verificar atualizações**, o LicitaIA consulta a API pública
`https://api.github.com/repos/<owner>/<repo>/releases/latest` (sem token; só funciona com repositório
**público**) e, se a release for mais nova que a versão instalada, mostra o diálogo
**"Nova versão X disponível"** com as notas da release, o tamanho do APK e os botões **Atualizar agora**
(baixa o APK para o cache do app e abre o instalador do Android) e **Depois** (não oferece a mesma versão
por 24 h). Nada é baixado sem o toque em "Atualizar agora"; a checagem automática é silenciosa em caso de
falta de rede ou erro.

**Repositório consultado:** `LICITAIA_UPDATE_REPO` em `local.properties` (ou propriedade Gradle / variável de
ambiente), no formato `owner/repo`. Padrão: `ERIVANBONFIM/LicitaIA`. O valor vai para `BuildConfig.UPDATE_REPO`.

**Como publicar uma release que o app reconhece**

1. Suba `versionCode` e `versionName` em `app/build.gradle.kts` (ex.: `versionCode = 4`, `versionName = "0.3.0"`).
2. Gere o APK **release assinado com o keystore fixo** (`LICITAIA_RELEASE_*` em `gradle.properties` fora do
   repositório, ver SECURITY.md): `.\gradlew.bat assembleRelease` → `app\build\outputs\apk\release\app-release.apk`.
   O Android só aceita a atualização se a assinatura for **a mesma** do app instalado; mudar de keystore ou
   publicar um APK debug faz o instalador recusar ("app não instalado"). O `applicationId` do release é
   **`com.licitaia.app`** (o debug é `com.licitaia.app.debug`, um app separado que **não** recebe essas releases).
3. Crie a release no GitHub com a tag na convenção **`v<versionName>+<versionCode>`**, por exemplo
   `v0.3.0+4`. O app compara o `+N` com o seu `versionCode`; se a tag não tiver `+N`, compara o
   `versionName` semântico (`vX.Y.Z`). Não marque como *draft* nem *pre-release* (a API `latest` ignora ambos).
4. Anexe o `app-release.apk` como asset (o nome deve conter `release`, ou ser o único `.apk` da release).
5. Escreva as **notas da release** no corpo (Markdown simples: títulos, listas, negrito, links). É esse
   texto que o app mostra no diálogo, convertido para texto simples.

**No celular (uma vez):** ao tocar em "Atualizar agora" pela primeira vez, o Android pede para permitir
"Instalar apps desconhecidos" para o LicitaIA; o app abre a tela certa das Configurações e retoma o download
quando você volta.

**Limitações:** sem repositório público não há checagem (releases privadas exigiriam token, que o app não
armazena); a API pública do GitHub tem limite de ~60 consultas/hora por IP (o app consulta no máximo a cada
6 h); a verificação de assinatura é feita pelo próprio Android — o app apenas antecipa a mensagem quando
detecta pacote ou certificado diferente; não há download em segundo plano nem retomada de download interrompido.

## Recompilar

Requisitos: JDK 17 (`JAVA_HOME`), Android SDK em `C:\Android` (platform 34, build-tools 34.0.0;
ajuste em `local.properties` se o SDK estiver em outro lugar), Gradle Wrapper 8.8 (baixa sozinho).

```powershell
# Debug (APK de teste)
.\gradlew.bat assembleDebug

# Release (minificado; exige configurar signingConfig — ver SECURITY.md)
.\gradlew.bat assembleRelease

# Testes unitários JVM + lint
.\gradlew.bat test
.\gradlew.bat :app:lintDebug
```

Linux/macOS: `./gradlew assembleDebug`.

> **Atenção ao caminho da pasta.** O nome `app licitaçao` tem um caractere fora do ASCII. O build funciona
> (`android.overridePathCheck=true`), mas o task `test` do Gradle falha com `ClassNotFoundException` nesse
> caminho no Windows. Solução: mapear a pasta para uma unidade e rodar a partir dela:
>
> ```powershell
> subst L: "C:\Users\DESKTOP\Documents\app licitaçao"
> L:
> .\gradlew.bat test
> ```
>
> Ou simplesmente renomear a pasta para `app-licitacao`. Importar no Android Studio: *File → Open* na raiz.

## O que fazer no primeiro teste (roteiro de 15 minutos)

1. Abrir o app → **Criar conta local** (sua empresa, CNPJ válido) → entra como Administrador.
2. Menu → **Buscar Licitações** → chip **PNCP** → filtrar UF/modalidade → resultados reais da API pública do PNCP
   ("Fonte: PNCP · consulta pública"). Sem rede: "Sem conexão com o PNCP" + cache do último resultado.
3. **Radar de Licitações** → criar radar (segmento, palavras-chave, UF) → "Ver resultados" (consulta real). O radar
   também roda em segundo plano a cada ~6 h com rede e notifica novas oportunidades.
4. **Tenho Interesse** numa oportunidade → abrir a licitação → card **Edital**: *Importar PDF* (baixe o edital no
   site do PNCP pelo link da licitação) ou *Colar texto* → "Analisar com IA".
   - Sem chave de IA: a análise é rotulada **"Heurística local (sem IA)"** com banner amarelo.
   - Com chave (Configurações → Provedor de IA → testar conexão): análise sobre o texto real do edital
     (documentos exigidos, riscos, pontos críticos e faixa de preço vêm da IA).
5. **Analisar Edital → Cadastrar licitação manualmente** para editais obtidos fora do PNCP.
6. **Vale a pena participar?**, **Proposta comercial** (gerar, editar, versões, PDF, aprovação com RBAC).
   "Preparar envio" informa que o envio integrado ao portal **não está disponível** — exporte o PDF e envie no portal.
7. **Documentos** (validade, anexos), **Auditoria**, **Empresas e Perfis** (vincular conta Google, perfis).
8. **Configurações → Backup**: exportar backup cifrado da empresa (senha ≥ 12 caracteres) e restaurar.
9. **Configurações → Sobre → Verificar atualizações** (precisa do repositório GitHub público com uma release).
10. **Portais Conectados**: abre as páginas oficiais (PNCP, Compras.gov.br, BLL) em Custom Tabs para você logar
    manualmente; CAPTCHA/MFA são sempre resolvidos por você no portal.

## O que já funciona com dados reais

- **Login** local (PBKDF2) e **Google** via Credential Manager, com assinatura RS256 do ID token verificada contra o
  JWKS oficial do Google (sem verificação online o login falha), nonce/audiência/expiração validados. Usuário Google
  entra sem empresa e sem perfil admin até ser vinculado — ou cria a própria empresa e vira admin dela.
- **Busca e Radar** na API pública do PNCP (contratações com proposta aberta; filtros por UF, modalidade, valor, texto),
  cache offline em Room, radar em segundo plano (WorkManager, a cada 6 h com rede) com notificação.
- **Licitações**: interesse, cadastro manual, importação do PDF do edital (extração de texto com pdfbox; PDF escaneado
  é detectado e pede texto colado), análise por IA real (OpenAI/Anthropic/Gemini/API própria) sobre o texto, sem
  fallback silencioso; checklist comparado com o cofre real.
- **Documentos**, **propostas com PDF**, versões, aprovação/rejeição com RBAC no repositório, auditoria completa.
- **Empresas e perfis** com posse por empresa e RBAC aplicados nos repositórios (não só na UI); sem conta demo.
- **Segurança**: Keystore AES-256-GCM para chaves/segredos, PIN/biometria/timeout, FLAG_SECURE, HTTPS obrigatório.
- **Backup cifrado** da empresa ativa (PBKDF2 210k + AES-GCM) com restauração por CNPJ; **migrations** do Room sem perda
  de dados (v1→v2→v3→v4; v1→v3 validada no aparelho); **release assinável** por keystore externo.
- **Atualização via GitHub Releases** (diálogo "Atualizar agora / Depois" com as notas da versão).

## O que continua sem integração real

| Área | Situação |
|---|---|
| Compras.gov.br, BLL, Licitanet, Portal de Compras Públicas | Sem API oficial pública/autenticada validada: o app só abre as páginas oficiais (Custom Tabs) para uso manual. A descoberta de oportunidades vem do PNCP. |
| Pregões ao vivo, robô de lances, CAPTCHA, Sala de Guerra, mensagens do pregoeiro | Telas e motor existem; sessões fictícias e robô foram **bloqueados** nesta versão — não há acompanhamento nem lance integrado a portal. Operação assistida (você no portal, app registrando) está no ROADMAP. |
| Envio de proposta e resposta ao pregoeiro | Não disponível por integração; o app prepara (PDF, valores, checklist) e informa a indisponibilidade. |
| Concorrência | Sem dados (o seed foi desativado); registro de resultados reais está no ROADMAP. |
| OCR de PDF escaneado, foreground service para pregões, certificate pinning, SQLCipher | Não implementados. |
| Modo demonstração isolado para clientes | Removido junto com o seed; reintrodução como modo DEMO separado está no ROADMAP. |

## Estrutura (resumo)

```
app/                 shell: MainActivity, navegação, drawer, bottom bar, bloqueio, toasts
core-domain/         modelos, contratos (repositórios, LiveSessionManager), RBAC, motor de regras, simulador
core-ui/             tema premium e componentes Compose
core-data/           Room (migrations), DataStore, repositórios, backup cifrado, importação de edital
core-security/       Keystore AES-GCM, SecretStore, PBKDF2
core-network/        OkHttp/Json
core-ai/             adapters OpenAI/Anthropic/Gemini/Custom + AiGateway
connector-api/       contrato PortalConnector      connector-pncp/     PNCP real (API pública)   connector-mock/ mocks (inativos)
ai-provider-api/     contrato AIProvider           ai-provider-mock/   MockAIProvider
feature-*/           auth, dashboard, radar, tender, documents, live, bidding, warroom,
                     settings, audit, messages, competition
```

Detalhes em [ARCHITECTURE.md](ARCHITECTURE.md).
