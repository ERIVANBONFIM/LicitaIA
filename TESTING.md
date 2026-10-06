# Testes — LicitaIA

## Resumo (v0.1.0-mock)

| Tipo | Onde | Quantidade | Como rodar | Estado |
|---|---|---|---|---|
| Unitários JVM | `core-domain`, `core-data`, `core-ai`, `ai-provider-mock`, `connector-mock`, `connector-pncp`, `core-security`, `feature-auth`, `app`, `connector-comprasgov`, `feature-live`, `feature-bidding` | 246 testes | `.\gradlew.bat test` (a partir de `L:\`, ver abaixo) | ✅ 0 falhas |
| Lint Android | todos os módulos | — | `.\gradlew.bat lintDebug` | ✅ 0 erros (warnings restantes não críticos) |
| UI Compose (instrumentado) | `app/src/androidTest/.../LicitaScaffoldTest.kt` | 3 testes | `.\gradlew.bat connectedDebugAndroidTest` (exige aparelho/emulador) | ✅ compila; ⬜ não executado (sem dispositivo no ambiente de build) |
| Build | `assembleDebug`, `assembleRelease` (não assinado, R8) | — | ver README | ✅ passam (06/10/2026) |
| Migração Room no aparelho | banco real v2 → v3 → v4 → v5 | — | instalar por cima | ✅ validada no Xiaomi (dados preservados, sem crash) |
| Smoke em aparelho físico | roteiro abaixo | — | manual | ⬜ pendente |

> **Windows + caminho com "ç"**: o task `test` falha com `ClassNotFoundException` quando o projeto está em
> `C:\Users\DESKTOP\Documents\app licitaçao`. Mapeie a pasta para uma unidade e rode a partir dela:
> ```powershell
> subst L: "C:\Users\DESKTOP\Documents\app licitaçao"
> L:
> .\gradlew.bat test
> ```
> O `assembleDebug` funciona em qualquer um dos dois caminhos.

## O que os testes unitários cobrem

- **Motor de lances** (`BidRuleEngineTest`): piso jamais violado em 20 mil contextos aleatórios; parada no piso;
  bloqueio com CAPTCHA pendente; respeito ao intervalo mínimo; comportamento por modo (manual sugere,
  supervisionado pede autorização, automático limitado pede autorização perto do piso); validação de regra.
- **Simulador** (`AuctionSimulatorTest`): determinismo por semente; 480 simulações sem violar piso; saída completa.
- **Scoring de oportunidades** (`OpportunityScoringTest`): casamento de radar (palavras, proibidas, UF, valor,
  portal), score 0..100, afinidade de segmento.
- **RBAC** (`RbacTest`): matriz perfil → permissões, Admin tem tudo, Técnico não aprova envio etc.
- **Modelos** (`ModelsTest`): `CompanyDocument.status/daysToExpire` (válido, vence em breve, vencido, ausente),
  `BidRule.marginPct`.
- **Formatação pt-BR** (`FormattersTest`): moeda, compacto, percentual, datas, contagem regressiva, CNPJ.
- **IA mock** (`MockAIProviderTest`): análise coerente (recomendação, score, faixa de preço, checklist),
  rascunho de proposta, comparação de documentos, determinismo.
- **Conectores mock** (`MockConnectorTest`): filtros reais no catálogo, capacidades, sessão isolada.
- **Segurança** (`PasswordHasherTest`): hash/verificação PBKDF2, salt distinto, rejeição de senha errada.
- **ViewModel** (`LoginViewModelTest`): validação de e-mail/senha/CNPJ, login/erro, conta demo, e fluxo Google
  (não configurado, sucesso, identificado sem empresa, cancelado, sair da conta) (MockK + coroutines-test).
- **ID token do Google** (`GoogleIdTokenClaimsTest`): parse das claims, audiência errada, nonce diferente (replay),
  expiração, `aud` em array, token malformado.

### Login Google — o que exige configuração externa
Os testes acima não cobrem a chamada real ao Credential Manager (UI do sistema). Para validar de ponta a ponta:
configure `LICITAIA_GOOGLE_SERVER_CLIENT_ID` e os clientes OAuth Android (README), recompile, e no celular:
- [ ] botão "Entrar com Google" habilitado; toque abre o seletor de contas do Google;
- [ ] fechar o seletor → aviso "Entrada com Google cancelada", sem erro;
- [ ] escolher conta nova → card "Conta identificada, acesso pendente" (sem acesso a empresa);
- [ ] com a conta demo logada em outro aparelho/sessão: Empresas e Perfis → "Aguardando vínculo" → Vincular → perfil;
- [ ] "Verificar acesso" → entra na empresa vinculada com o perfil definido (não é ADMIN);
- [ ] Auditoria mostra LOGIN (pendente), CADASTRO e LOGIN (sucesso);
- [ ] "Sair" e entrar de novo com Google → seletor de contas aparece novamente.

## Roteiro de smoke test em aparelho físico (critérios do SDD §30)

Marque cada item ao executar:

- [ ] APK instala (`adb install -r ...`) e abre sem crash (primeira abertura semeia dados: 2–4 s).
- [ ] Login com conta demo; "lembrar sessão" restaura ao reabrir.
- [ ] Dashboard mostra 7 indicadores, atalhos, "Pregões agora"; sino abre notificações.
- [ ] Menu lateral: todos os 20 itens navegam; barra inferior Início/Radar/Pregões/Robô/Mais.
- [ ] Radar: criar, editar, ativar/desativar, excluir; "Ver resultados" lista oportunidades com score.
- [ ] Busca: filtros por portal/UF/segmento/valor; "Tenho Interesse" marca e persiste após reabrir o app.
- [ ] Licitação: resumo; Análise (estado "IA analisando…" → resultado); "Vale a pena participar?" com veredito.
- [ ] Proposta: gerar com IA, editar, salvar nova versão, comparar versões; gerar PDF e visualizar; compartilhar.
- [ ] Aprovação: enviar para revisão → aprovar (perfil com permissão) → "Preparar envio" exige confirmação dupla;
      aparece em Minhas Participações e na Auditoria como ENVIO (simulado).
- [ ] Pregões ao Vivo: 3 sessões rodando ao mesmo tempo com lances de concorrentes; abrir cada uma.
- [ ] Robô: iniciar em uma sessão (SIMULAÇÃO); lances respeitam piso; pausar/retomar; "PARAR E ASSUMIR";
      lance manual abaixo do piso é recusado.
- [ ] CAPTCHA: "Simular CAPTCHA" pausa só aquela sessão; sino fica vermelho; toast; push local; o alerta repete no
      intervalo configurado (teste com 1 min) e para ao "Já resolvi — retomar".
- [ ] WebView: domínio e cadeado visíveis; página mock; abrir site público; voltar/avançar; link externo → Custom Tabs.
- [ ] Sala de Guerra: contadores corretos; "PAUSAR TODOS OS ROBÔS" pede confirmação e pausa todos.
- [ ] Simulador: rodar com parâmetros diferentes; gráfico; Estratégia compara as 4 estratégias.
- [ ] Mensagens: resumo/resposta da IA; editar; aprovar; envio simulado com confirmação.
- [ ] Documentos: status e vencimentos; criar com anexo (seletor de arquivos); abrir anexo.
- [ ] Empresas e Perfis: trocar empresa (topo e dashboard mudam; pregões da outra empresa não aparecem).
- [ ] Configurações: intervalo de CAPTCHA, provedor de IA (selecionar; salvar chave fictícia mostra "chave
      configurada ✓" e nunca a exibe); Segurança: definir PIN → sair do app > timeout → volta bloqueado.
- [ ] Auditoria: eventos das ações acima com usuário/empresa/valores; exportar.
- [ ] Logout e login com outro perfil (ex.: Técnico) → ações restritas aparecem desabilitadas com motivo.

Registre falhas com: modelo do aparelho, versão do Android, passo, resultado esperado × obtido, e o log:
`adb logcat -d *:E > erros.txt`.

## Como depurar um crash

```powershell
C:\Android\platform-tools\adb.exe logcat -c
C:\Android\platform-tools\adb.exe logcat *:E | Select-String -Pattern "licitaia|AndroidRuntime"
```

## Próximos passos de qualidade

Testes Compose por tela (semântica), testes de integração Room (in-memory), testes do `LiveSessionManagerImpl`
com `TestDispatcher`, detekt/ktlint, CI (GitHub Actions) com `test` + `lintDebug` + `assembleDebug`.

## v0.2.0 — cobertura nova e o que validar no aparelho

Testes novos: `PncpConnectorTest`/`PncpMapperTest` (fixtures reais da API, paginação, 204, erro HTTP),
`EditalTextPreparerTest`, `DatabaseMigrationsTest`, `ManualTenderDraftTest`, `DocumentTypeMappingTest`,
`GoogleTokenVerifierTest` (assinatura válida, adulterada, chave desconhecida), `PortableBackupCipherTest`,
`RepositoryAccessTest`/`CompanyIsolationTest`/`CompanyValidationTest`, `ReleaseParserTest`/`UpdateCheckerEvaluateTest`.

Validar manualmente (ainda não exercitado):
- [ ] Busca PNCP no celular (latência, 429, offline → cache); radar em segundo plano notifica.
- [ ] Importar PDF de edital real (texto) e um escaneado (deve pedir texto colado); abrir o PDF; analisar com
      provedor real e sem chave (banner heurístico).
- [ ] Login Google ponta a ponta (assinatura verificada online; sem rede deve falhar com mensagem).
- [ ] Backup: exportar com senha, restaurar em instalação limpa com o mesmo CNPJ; senha errada; arquivo adulterado.
- [ ] Atualização: publicar release `vX.Y.Z+N` com `app-release.apk` assinado → diálogo → instalar.
- [ ] APK release (R8) em aparelho: fluxo completo (ainda não executado).
- [ ] Portais: "Entrar no portal" → login manual no gov.br/BLL → badge "Sessão aberta"; fechar e reabrir o app mantém a
      sessão; "Sair do portal" limpa; CAPTCHA/MFA manuais.
- [ ] IA: Gemini "Entrar com conta Google" (requer Generative Language API ativa e escopo `cloud-platform` no consentimento)
      → "Testar conexão"; OpenAI/Claude "Obter chave de API" abre o navegador.
