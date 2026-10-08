# Rebrand LicitaIA → LicitaPRO — Inventário de mudanças

**Data:** 2026-10-07  
**Escopo:** Mapeamento completo para renomear o app Kotlin de "LicitaIA" para "LicitaPRO"  
**Total de pontos a alterar:** 31 arquivos, ~45 referências diretas

---

## Resumo executivo

- **Muda:** Nome de exibição (app name), textos, títulos de tela, assinatura em notificação, documentação
- **NÃO muda:** `applicationId` (`com.licitaia.app`), pacote Kotlin (`com.licitaia.*`), certificado de assinatura, UPDATE_REPO GitHub
- **Risco:** Nenhum de quebra de atualização ou dados, pois o applicationId permanece

---

## Inventário por tipo de mudança

### 1. EXIBIÇÃO (Strings visíveis ao usuário — MUDAR)

| Arquivo | Linha | Texto Atual | Texto Novo | Categoria |
|---------|-------|-------------|-----------|-----------|
| `app/build.gradle.kts` | 60 | `"LicitaIA Dev"` | `"LicitaPRO Dev"` | App name (debug) |
| `app/build.gradle.kts` | 63 | `"LicitaIA"` | `"LicitaPRO"` | App name (release) |
| `app/src/main/kotlin/com/licitaia/app/shell/AppRoot.kt` | 145 | `Text("LicitaIA", ...)` | `Text("LicitaPRO", ...)` | Splash screen |
| `app/src/main/kotlin/com/licitaia/app/shell/AppRoot.kt` | 431 | `"Sair do LicitaIA?"` | `"Sair do LicitaPRO?"` | Diálogo logout |
| `app/src/main/kotlin/com/licitaia/app/shell/DrawerContent.kt` | 209 | `Text("LicitaIA", ...)` | `Text("LicitaPRO", ...)` | Drawer header |
| `app/src/main/kotlin/com/licitaia/app/shell/LockScreen.kt` | 112 | `.setTitle("Desbloquear LicitaIA")` | `.setTitle("Desbloquear LicitaPRO")` | Biometric prompt |
| `app/src/main/kotlin/com/licitaia/app/shell/LockScreen.kt` | 139 | `Text("LicitaIA bloqueado", ...)` | `Text("LicitaPRO bloqueado", ...)` | Lock screen title |
| `app/src/main/kotlin/com/licitaia/app/update/UpdateDialog.kt` | 132 | `"Como o LicitaIA não vem da loja..."` | `"Como o LicitaPRO não vem da loja..."` | Update help text |
| `app/src/main/kotlin/com/licitaia/app/update/UpdateViewModel.kt` | 104 | `"...LicitaIA."` (em mensagem de permissão) | `"...LicitaPRO."` | Permissão error message |
| `feature-live/src/main/kotlin/com/licitaia/feature/live/notify/AppNotifierImpl.kt` | 191 | `"Avisos gerais do LicitaIA"` | `"Avisos gerais do LicitaPRO"` | Notification category |

### 2. INTERNO (Headers, user agents — MUDAR, mas baixa visibilidade)

| Arquivo | Linha | Texto Atual | Texto Novo | Categoria |
|---------|-------|-------------|-----------|-----------|
| `app/src/main/kotlin/com/licitaia/app/update/UpdateChecker.kt` | 127 | `"User-Agent", "LicitaIA/${...}"` | `"User-Agent", "LicitaPRO/${...}"` | GitHub API header |
| `app/src/main/kotlin/com/licitaia/app/update/ApkDownloader.kt` | 52 | `"User-Agent", "LicitaIA (Android)"` | `"User-Agent", "LicitaPRO (Android)"` | APK download header |

### 3. TEMAS & ESTILOS (XML — MUDAR)

| Arquivo | Linha | Texto Atual | Texto Novo | Categoria |
|---------|-------|-------------|-----------|-----------|
| `app/src/main/res/values/themes.xml` | 5 | `<style name="Theme.LicitaIA" ...>` | `<style name="Theme.LicitaPRO" ...>` | Theme name |
| `app/src/main/AndroidManifest.xml` | 28 | `android:theme="@style/Theme.LicitaIA"` | `android:theme="@style/Theme.LicitaPRO"` | Theme reference |

### 4. ÍCONES & DRAWABLES (Renomear — MUDAR)

| Arquivo Original | Arquivo Novo | Motivo | Categoria |
|------------------|-------------|--------|-----------|
| `feature-live/src/main/res/drawable/ic_stat_licitaia.xml` | `feature-live/src/main/res/drawable/ic_stat_licitapro.xml` | Nome do drawable | Notification icon |
| `app/src/main/kotlin/com/licitaia/feature/live/notify/AppNotifierImpl.kt:97` | Atualizar ref. | `R.drawable.ic_stat_licitaia` → `R.drawable.ic_stat_licitapro` | Notification icon ref |
| `app/src/main/res/mipmap-*/ic_launcher.*` | (manter nome) | Visual redesign (fora do escopo de rebranding de strings) | App icon |

### 5. DOCUMENTAÇÃO MARKDOWN (MUDAR)

#### Arquivos a renomear:
- `SDD_LicitaIA.md` → `SDD_LicitaPRO.md` (documento de especificação)
- Atualizar referência em `README.md` (linha 15): `[SDD_LicitaIA.md](SDD_LicitaIA.md)` → `[SDD_LicitaPRO.md](SDD_LicitaPRO.md)`

#### Arquivos a atualizar (conteúdo):
| Arquivo | Referências a atualizar |
|---------|------------------------|
| `README.md` | Título, descrição, mentions de "LicitaIA" (vários locais) |
| `CHANGELOG.md` | Linha 1: "# Changelog — LicitaIA" → "# Changelog — LicitaPRO"; histórico permanece como está |
| `ROADMAP.md` | Título e referências |
| `ARCHITECTURE.md` | Título e referências |
| `SECURITY.md` | Título e referências |
| `TESTING.md` | Título e referências |
| `AVALIACAO_PRONTIDAO.md` | Título e mentions |
| `PESQUISA_INTEGRACAO_COMPRAS_LICITAIA.md` | Título e mentions; considerar renomear para `PESQUISA_INTEGRACAO_COMPRAS_LICITAPRO.md` |
| `COMPARATIVO_SISTEMAS_LICITACOES_IA.md` | Mentions de "LicitaIA" → "LicitaPRO" |
| `docs/projeto-plataforma/README.md` | "Plataforma LicitaIA multiempresa" → "Plataforma LicitaPRO multiempresa" |
| `.md` files in `docs/projeto-plataforma/*.html` | HTML files com referências (verificar dentro dos arquivos) |

### 6. TESTES (MUDAR — atualizações mínimas)

| Arquivo | Linha | Mudança | Categoria |
|---------|-------|---------|-----------|
| `app/src/test/kotlin/com/licitaia/app/update/ReleaseParserTest.kt` | 14-15, 86 | URLs mock: `ERIVANBONFIM/LicitaIA` → `ERIVANBONFIM/LicitaPRO` (se o repo mudar) | Test fixtures |

---

## O que NÃO muda (crítico para manter compatibilidade)

| Item | Razão |
|------|-------|
| **applicationId:** `com.licitaia.app` | Quebra atualização automática, perde dados de usuário se alterar |
| **Package namespace:** `com.licitaia.*` em todos os arquivos Kotlin | Quebra classpath, imports, reflection |
| **Certificado de assinatura (keystore)** | Usuários não conseguem instalar update de uma versão assinada com cert diferente |
| **UPDATE_REPO** em `build.gradle.kts` (linha 21) | Padrão `ERIVANBONFIM/LicitaIA` é configurável; mudar aqui só faz sentido se o repo mudar de dono |
| **Icon files visuals** (`mipmap/ic_launcher*`) | Redesign visual é fora do escopo; aqui apenas renomeamos *strings*, não imagens |

---

## Sumário de ações

### Fase 1: Strings visíveis (10 arquivos .kt)
- [ ] 10 referências diretas em textos de interface, notificações e diálogos

### Fase 2: Internals (2 arquivos .kt)
- [ ] 2 referências em headers HTTP (baixo impacto)

### Fase 3: Temas & XML (2 arquivos .xml)
- [ ] 2 referências em tema e manifest

### Fase 4: Ícones & Drawables (2 arquivos)
- [ ] Renomear `ic_stat_licitaia.xml` → `ic_stat_licitapro.xml`
- [ ] Atualizar referência em `AppNotifierImpl.kt:97`

### Fase 5: Documentação (~15 arquivos .md)
- [ ] Renomear `SDD_LicitaIA.md` → `SDD_LicitaPRO.md`
- [ ] Atualizar referências cruzadas

### Fase 6: Testes (1 arquivo)
- [ ] Atualizar fixtures se repo mudar

---

## Observações finais

1. **Assinatura e distribuição:** O certificado de assinatura do release (`LICITAIA_RELEASE_*`) não muda; apenas o nome exibido muda.

2. **Compatibilidade:** Usuários de versão anterior (`com.licitaia.app`) continuam recebendo updates normalmente porque o `applicationId` não muda.

3. **GitHub:** Repo padrão é `ERIVANBONFIM/LicitaIA`. Se o projeto for movido para outra organização ou renomeado, atualizar `build.gradle.kts:21` e testes.

4. **Ícone de notificação:** O arquivo `ic_stat_licitaia.xml` é um SVG genérico; renomear mantém consistência, mas impacto visual é zero.

5. **Documentação interna:** PDFs de proposta NÃO mencionam "LicitaIA" em cabeçalho/rodapé; assinatura permanece como está (dados da empresa fornecida pelo usuário).

---

**Total estimado:** ~45 mudanças em ~31 arquivos, sem efeitos colaterais de quebra de atualização ou perda de dados.
