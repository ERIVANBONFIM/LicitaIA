# Segurança — LicitaIA

## Princípios (SDD §11, §15, §28, §32)

1. **Nunca contornar CAPTCHA ou MFA.** O app detecta o evento, pausa SOMENTE a sessão afetada, alerta o
   usuário e só retoma após confirmação humana explícita. Não existe código de resolução automática.
2. **Nenhuma ação vinculante sem confirmação humana.** Envio de proposta, resposta ao pregoeiro e lance
   manual passam por `BindingConfirmDialog` (resumo + caixa de ciência). Nesta entrega todos são SIMULADOS.
3. **Robô começa em SIMULAÇÃO** e nunca envia lance abaixo do piso, em item/sessão diferente, com CAPTCHA
   pendente ou após erro crítico (`BidRuleEngine.validateBid`, coberto por testes).
4. **Sem segredos no código.** Nenhuma chave de API, token ou senha hardcoded. A única credencial conhecida
   é a da conta demo local (`demo@licitaia.app` / `demo1234`), armazenada apenas como hash PBKDF2 e
   documentada como dado de demonstração.
5. **Sem APIs privadas ou endpoints adivinhados.** Portais ficam atrás de conectores mock.

## Implementado nesta versão

| Controle | Implementação |
|---|---|
| Segredos (chaves de IA, credenciais de portal, hash de PIN) | `core-security/KeystoreCipher` — AES-256-GCM com chave gerada no **Android Keystore**; valores cifrados gravados em `SharedPreferences` privado (`SecretStore`). |
| Senhas locais | PBKDF2-WithHmacSHA256, salt aleatório, 120 mil iterações, comparação em tempo constante (`PasswordHasher`). |
| HTTPS obrigatório | `network_security_config.xml` com `cleartextTrafficPermitted="false"`; `usesCleartextTraffic=false`; OkHttp só TLS. `CustomProvider` exige `https://`. |
| Logs | Logging HTTP só em build debuggable, nível BASIC, com redação de `Authorization`, `x-api-key`, `x-goog-api-key`. Senhas/PIN/chaves nunca são logados nem exibidos (a UI mostra apenas "chave configurada ✓"). |
| Bloqueio do app | PIN (4–6 dígitos) e/ou biometria (`BiometricPrompt`, BIOMETRIC_WEAK ou credencial do aparelho) após timeout configurável (1/5/15/30 min) ou abertura a frio com sessão lembrada. **Limite de tentativas de PIN**: a partir do 5º erro consecutivo, bloqueio progressivo (30 s, 1 min, 5 min, 15 min, 30 min, 1 h) persistido no cofre cifrado (`PinLockoutPolicy`, `AuthRepositoryImpl.verifyPinDetailed`); enquanto bloqueado o PIN nem é comparado; falhas e bloqueios geram `LOGIN` FALHA/BLOQUEADO na auditoria, sem o PIN. O desbloqueio biométrico só é aceito com o `AuthenticationResult` entregue pelo callback do sistema (`ShellViewModel.unlock(result)`). |
| Proteção de captura de tela | `FLAG_SECURE` opcional (Configurações → Segurança). |
| Backup | `allowBackup=false` (sem backup do sistema). **Backup cifrado manual** da empresa ativa: PBKDF2-HMAC-SHA256 210k iterações + AES-256-GCM com cabeçalho autenticado, senha ≥ 12 caracteres, sem identidades/chaves/sessões (`core-security/PortableBackupCipher.kt`, `core-data/backup/CompanyBackupRepository.kt`). O backup grava nome e MIME de cada anexo (`fileMeta`); na restauração os arquivos vão para `filesDir/restored/<uuid>/<uuid>.<ext>` preservando a extensão (ou inferindo-a do caminho/assinatura do conteúdo em backups antigos), servidos pelo `FileProvider` `${applicationId}.licitaia.fileprovider` (`files-path restored/`), que assim volta a informar o MIME correto. |
| Auditoria | Login, logout, troca de empresa, conexão de portal, análise, geração de documento, aprovação/rejeição, mudança de piso/regra, ativação/pausa/encerramento do robô, controle manual, lance, CAPTCHA, mensagem, envio, configuração, emergência e erro — com usuário, empresa, portal, pregão, item, valor anterior/novo, motivo, origem e resultado. **Hash encadeado** (banco v5): cada evento guarda `prevHash` e `hash = SHA-256(prevHash + campos essenciais)` (`core-security/AuditHashChain`), calculado na inserção dentro de uma transação serializada por `Mutex`; eventos anteriores à v5 ficam sem hash e a verificação começa no primeiro encadeado. Tela Auditoria → "Verificar integridade" (`AuditRepository.verifyIntegrity`) e exportação com o hash de cada evento. Falhas de gravação não são mais engolidas: `Log.w` sem dados do evento. **Restauração de backup**: os eventos importados são **re-encadeados** a partir da cabeça atual da cadeia local (`prevHash`/`hash` recalculados na ordem original, `core-data/backup/AuditRestoreChain.kt`), de modo que `verifyIntegrity` continua íntegra; os hashes do aparelho de origem não são preservados (a cadeia é por aparelho). |
| RBAC | Perfis Admin, Diretoria, Licitações, Financeiro, Técnico (`Rbac`). Ações sensíveis são bloqueadas na UI com o motivo. |
| Isolamento de sessões | Uma instância lógica por pregão: estado, regra, item, fila de eventos, log e CAPTCHA próprios. Sessões de outra empresa não aparecem. |
| WebView | Allowlist de domínios por portal (fora dela → Custom Tabs/navegador), sem `addJavascriptInterface`, sem acesso a arquivos locais, domínio e cadeado sempre visíveis, perfis isolados do `androidx.webkit` quando o WebView do aparelho suporta. |
| Permissões | INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS (pedida em runtime no Android 13+), VIBRATE, USE_BIOMETRIC. Nenhuma permissão de localização, contatos, SMS ou armazenamento amplo. |
| Dados em repouso | **Banco cifrado com SQLCipher** (`net.zetetic:sqlcipher-android` 4.6.1: AES-256-CBC por página + HMAC-SHA512) em armazenamento privado do app. **Chave**: 32 bytes aleatórios gerados uma única vez, guardados **cifrados pelo Android Keystore** no `SecretStore` (`db.key`, `core-data/crypto/DatabaseKeyProvider.kt`), usados como chave bruta (`x'…'`, sem PBKDF2); nunca em texto puro em disco. **Migração automática** do banco em texto puro já existente (`core-data/crypto/DatabaseEncryptionMigrator.kt`, chamado pela `EncryptedOpenHelperFactory` na primeira abertura, antes do Room): checkpoint do WAL → `ATTACH … KEY` + `sqlcipher_export` para `licitaia_enc.db` → cópia de `user_version` → `integrity_check` e conferência das contagens por tabela → só então `licitaia.db` → `licitaia_plain.bak` e `licitaia_enc.db` → `licitaia.db`; o `.bak` é apagado após a primeira abertura cifrada bem-sucedida. Em qualquer falha o original fica intacto e o app abre-o em claro como antes (`Log.w`); nunca se apaga dado em falha; o processo é reentrante após queda. Limitações: a passphrase fica em memória do processo enquanto o banco está aberto (necessária ao pool de conexões do SQLCipher); se a chave mestra do Keystore for invalidada, o banco cifrado torna-se irrecuperável (o app falha ao abrir em vez de apagar dados — use o backup cifrado manual). PDFs em `filesDir/proposals` compartilhados só via `FileProvider` com permissão temporária de leitura. |

## Login Google e backend de identidade

**Situação atual (v0.2.0):** o LicitaIA não tem backend; a autorização é local (Room). A **assinatura RS256 do ID token é verificada no aparelho** contra o JWKS oficial do Google (`https://www.googleapis.com/oauth2/v3/certs`, `feature-auth/google/GoogleTokenVerifier.kt`); sem rede o login falha (fail-closed).
O botão "Entrar com Google" usa o **Credential Manager** (`androidx.credentials` + `googleid`) e pede apenas o
ID token (escopos `openid email profile`; nunca Gmail/Drive). No cliente:

- o token é lido uma única vez, **nunca persistido nem logado**; guardamos só `sub`, e-mail, nome e `email_verified`;
- validamos audiência (= Web Client ID), nonce aleatório de 32 bytes (anti-replay) e expiração
  (`feature-auth/google/GoogleIdTokenClaims.kt`, com testes);
- a identidade **não concede** empresa nem perfil admin; o vínculo é feito por um administrador ou o próprio
  usuário cria a sua empresa ("Criar minha empresa") e vira ADMIN dela; vínculo automático por e-mail a conta local
  existente foi bloqueado; tudo auditado;
- contas demo e contas reais não se misturam (e-mail demo é recusado no login Google);
- "Sair" chama `clearCredentialState()` no Google.

**Limitação sem backend:** mesmo com a assinatura verificada, a autorização continua local: um aparelho/APK
comprometido controla o banco. Aceitável para uso pessoal; para múltiplos clientes é necessário o backend abaixo.

**Recomendação (antes de ir a produção):** adotar um backend de identidade que troque o ID token do Google por
uma sessão própria e seja a fonte da verdade de empresas/perfis:

| Opção | Prós | Contras |
|---|---|---|
| **Firebase Authentication (provedor Google) + Cloud Firestore/Functions** — recomendada para o estágio atual | Verificação de token pronta, SDK Android oficial, custo zero no início, regras de segurança por empresa, fácil evoluir para e-mail/senha e MFA | Vendor lock-in Google; modelagem de multiempresa via *custom claims*/Firestore |
| **Backend próprio (ex.: Kotlin/Ktor ou Node) verificando o ID token com `google-auth-library`** | Controle total, integra com futuros conectores de portais e auditoria central | Precisa operar infraestrutura, sessão/refresh, hardening |
| **Keycloak/Auth0/Cognito como IdP federando Google** | RBAC/multi-tenant maduros, SSO corporativo | Custo/complexidade maiores para o tamanho atual |

Com qualquer opção, o fluxo no app continua o mesmo: `GoogleCredentialClient` obtém o ID token → envia ao
backend → backend verifica assinatura/audiência, resolve empresas e perfil → devolve sessão. O ponto de troca
é `AuthRepository.loginWithGoogle`, que hoje faz a resolução local.

## Não implementado ainda (ver ROADMAP)


- Certificate pinning: `network_security_config.xml` já lista um `<domain-config>` HTTPS-only por destino (pncp.gov.br, api.github.com, api.openai.com, api.anthropic.com, generativelanguage.googleapis.com, oauth2.googleapis.com, www.googleapis.com), **sem `<pin-set>`** — pinning real exige gestão de rotação (pin atual + backup, data de expiração) para cada operador, sem a qual uma renovação de certificado derruba o app.
- Logout remoto, root detection, segunda confirmação por biometria em ações vinculantes reais.
- Isolamento de cookies da WebView em aparelhos sem suporte a `MULTI_PROFILE` (hoje: limitação documentada).

## Build de release assinável

O `buildType release` tem R8 (`minify` + `shrinkResources`) e regras em `app/proguard-rules.pro`.
Para assinar, crie um keystore fora do repositório e configure sem commitar segredos, por exemplo em
`~/.gradle/gradle.properties`:

```
LICITAIA_STORE_FILE=C:/chaves/licitaia-release.jks
LICITAIA_STORE_PASSWORD=...
LICITAIA_KEY_ALIAS=licitaia
LICITAIA_KEY_PASSWORD=...
```

e em `app/build.gradle.kts`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(providers.gradleProperty("LICITAIA_STORE_FILE").get())
        storePassword = providers.gradleProperty("LICITAIA_STORE_PASSWORD").get()
        keyAlias = providers.gradleProperty("LICITAIA_KEY_ALIAS").get()
        keyPassword = providers.gradleProperty("LICITAIA_KEY_PASSWORD").get()
    }
}
buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
```

`*.jks` e `*.keystore` já estão no `.gitignore`.

## Relato de vulnerabilidades

Abra um issue privado ou contate a equipe responsável pelo projeto. Não publique detalhes antes da correção.
