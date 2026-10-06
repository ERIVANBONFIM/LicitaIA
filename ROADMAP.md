# Roadmap — LicitaIA

Fases conforme SDD §31. Estado em 05/10/2026.

| Fase | Escopo | Status |
|---|---|---|
| 1 | UI + navegação + dados mock | ✅ Concluída (v0.1.0-mock) |
| 2 | Persistência local, documentos, PDF, IA mock | ✅ Concluída (v0.1.0-mock) |
| 2b | Uso pessoal: PNCP real, edital em PDF, backup, migrations, release assinável, atualização via GitHub | ✅ Concluída (v0.2.0-personal), validação no aparelho pendente |
| 3 | Integração real com provedores de IA | 🟡 Adapters prontos e recebendo o texto real do edital; falta validação com chaves reais e OCR |
| 4 | Conectores somente leitura para portais | 🟡 PNCP (API pública) concluído; Compras.gov.br dados abertos, BLL, Licitanet, PCP pendentes de API oficial |
| 5 | Sessões autenticadas e WebView real | ⬜ Pendente — isolamento de cookies por conta/portal, MFA manual, Custom Tabs autenticado |
| 6 | Automação supervisionada validada por portal | ⬜ Pendente — somente após autorização do portal; manter confirmação humana |
| 7 | Telemetria, auditoria avançada, hardening, testes | 🟡 Auditoria local e testes de domínio prontos; hardening parcial |

## Próximos passos sugeridos (ordem)

1. **Teste em dispositivo físico** com o roteiro do README; corrigir qualquer crash/UX.
2. **IA real**: configurar uma chave em *Configurações → Provedor de IA*, testar "Analisar edital",
   "Gerar proposta" e "Resumo da mensagem" com cada provedor; calibrar prompts (`core-ai/LlmBackedProvider`),
   `max_tokens` e tratamento de JSON inválido; adicionar cache de análise por edital.
3. **Entrada de edital real**: upload/leitura de PDF do edital (texto) para alimentar `TenderAnalysisRequest.editalText`;
   OCR opcional (ML Kit) para documentos do cofre.
4. **Portais — leitura pública**: para cada portal, verificar API oficial pública (ex.: dados abertos) e implementar
   conector somente leitura (`listOpportunities`, `getTenderDetails`) com Retrofit, rate limit e cache; manter os
   mocks como fallback. Nunca usar endpoints não documentados.
5. **Sessões autenticadas**: WebView autenticada por conector, cookie store por empresa+portal, detecção de
   expiração, fluxo de MFA manual; isolamento `MULTI_PROFILE` ou fallback por `WebViewDatabase`/limpeza.
6. **Robô real supervisionado**: só onde o portal permitir; começar em modo manual/supervisionado com
   `HumanConfirmation` obrigatória; foreground service com tipo adequado para manter sessões ativas.
7. **Notificações em background**: WorkManager para vencimento de documentos e radares periódicos; FCM opcional.
8. **Hardening**: certificate pinning, SQLCipher, root detection, logout remoto, política de senha, 2ª confirmação
   biométrica em ações vinculantes reais, revisão de permissões.
9. **Qualidade**: testes de UI Compose por tela, testes de integração Room, detekt/ktlint no CI, pipeline
   (GitHub Actions) gerando APK/AAB assinados, Crashlytics/telemetria.
10. **Produto**: exportação de auditoria em CSV/PDF, relatórios por empresa, modo tablet/landscape,
    acessibilidade (TalkBack, contraste), i18n.

## Fora do escopo (permanente)

Bypass de CAPTCHA/MFA, automação não autorizada, compartilhamento ilegal de credenciais, scraping agressivo,
submissão real sem confirmação humana, decisão jurídica autônoma.

## Atualização 06/10/2026 — próximos passos revisados
1. Validar no aparelho: PNCP, importação de PDF, login Google, backup/restauração, APK release.
2. Keystore fixo + primeira release no GitHub (`v0.2.0+3`) para ativar a atualização automática.
3. **Modo demonstração isolado** (reintroduzir o seed apenas em "modo DEMO", empresas e dados marcados `demo`,
   sem cruzar com dados reais) para apresentação a clientes.
4. Pregões ao vivo em **modo assistido**: você opera no portal (Custom Tabs/WebView), o app registra piso,
   margem e lances e alerta; foreground service para alertas com app em segundo plano.
5. OCR (ML Kit) para editais escaneados; concorrência alimentada por resultados reais.
6. Hardening: SQLCipher, auditoria encadeada, limite de tentativas de PIN, pinning por domínio.
7. Automação integrada só com API oficial autenticada/autorização do portal, sempre com confirmação humana.
