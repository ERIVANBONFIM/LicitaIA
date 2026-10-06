# PROMPT MESTRE PARA CLAUDE / AGENTE DE DESENVOLVIMENTO

Você é o responsável técnico por construir um aplicativo Android chamado LICITAIA.

Leia primeiro o arquivo SDD_LicitaIA.md inteiro.
Ele é a especificação oficial.

Sua missão é entregar um projeto Android completo, compilável, instalável e testável em dispositivo físico.

## REGRA DE EXECUÇÃO

Se o ambiente suportar subagentes, equipes ou agentes paralelos, use 4 agentes:

AGENTE 1 — ARQUITETURA
Responsável por:
- estrutura Gradle;
- módulos;
- Clean Architecture;
- Hilt;
- modelos;
- interfaces;
- banco Room;
- DataStore;
- segurança;
- contratos PortalConnector e AIProvider.

AGENTE 2 — ANDROID / UI-UX
Responsável por:
- Jetpack Compose;
- Material 3;
- tema premium;
- dashboard;
- menu lateral;
- sino;
- notificações;
- Radar;
- Interesse;
- Pregões ao Vivo;
- WebView;
- Sala de Guerra;
- telas restantes;
- responsividade.

AGENTE 3 — INTEGRAÇÕES / AUTOMAÇÃO SEGURA
Responsável por:
- interfaces de portais;
- conectores mock;
- WebView;
- sessão;
- gerenciamento de cookies;
- CAPTCHA workflow;
- múltiplas sessões;
- motor de regras;
- robô em SIMULAÇÃO;
- telemetria;
- eventos.

AGENTE 4 — QA / BUILD / RELEASE
Responsável por:
- testes unitários;
- testes Compose;
- smoke tests;
- detekt/lint;
- build debug;
- preparação release;
- README;
- instruções adb;
- lista de pendências.

Se subagentes não estiverem disponíveis, execute exatamente essas quatro frentes sequencialmente.

## REGRAS IMPORTANTES

1. NÃO contorne CAPTCHA.
2. NÃO contorne MFA.
3. NÃO implemente bypass de segurança.
4. Qualquer CAPTCHA deve:
   - pausar somente a sessão afetada;
   - alertar;
   - exigir resolução manual;
   - permitir retomada.
5. Qualquer envio vinculante deve exigir confirmação humana.
6. Não use APIs privadas ou endpoints adivinhados.
7. Integrações de portal sem documentação oficial devem ficar atrás de mock/stub.
8. Não hardcode senhas, tokens ou chaves.
9. Use Android Keystore para segredos.
10. Cada pregão deve ter sessão isolada.
11. Múltiplos pregões devem funcionar em paralelo em modo mock.
12. O robô deve começar em modo SIMULAÇÃO.
13. O código precisa ser compilável a cada etapa.
14. Não deixar TODO crítico quebrando build.

## STACK

Use:
- Kotlin
- Jetpack Compose
- Material 3
- MVVM
- Clean Architecture
- Hilt
- Room
- DataStore
- Coroutines / Flow
- WorkManager
- Retrofit / OkHttp
- WebView / Custom Tabs
- Android Keystore
- Coil
- JUnit
- MockK
- Compose UI tests

Use versões estáveis compatíveis entre si no momento da execução.
Não fixe versões antigas sem necessidade.

## ETAPAS

### ETAPA 1 — Bootstrap
Crie o projeto.
Confirme:
- gradle sync;
- build debug;
- app abre.

### ETAPA 2 — Arquitetura
Crie:
- módulos;
- entidades;
- repositories;
- use cases;
- interfaces PortalConnector e AIProvider.

### ETAPA 3 — Design System
Crie tema premium:
- dark;
- green/blue accents;
- danger red;
- warning yellow;
- typography;
- cards;
- buttons;
- badges;
- alerts;
- dialogs.

### ETAPA 4 — Navegação
Implemente:
- login;
- dashboard;
- drawer;
- bottom nav opcional;
- rotas de todas as telas.

### ETAPA 5 — Radar
Implementar:
- criação;
- edição;
- filtros;
- lista mock;
- salvar em Room;
- “Tenho interesse”.

### ETAPA 6 — Interesse
Implementar:
- resumo;
- score;
- documentos;
- datas;
- recomendação;
- faixa de preço;
- proposta;
- PDF;
- aprovação.

### ETAPA 7 — IA
Criar AIProvider.
Primeiro:
- MockAIProvider.

Depois preparar adapters para:
- OpenAI;
- Anthropic;
- Gemini;
- Custom.

Não exigir credenciais para rodar demo.

### ETAPA 8 — Pregões ao Vivo
Criar 3 sessões mock:
- Compras.gov;
- BLL;
- Licitanet.

Cada sessão:
- isolada;
- posição;
- lance;
- piso;
- estratégia;
- eventos;
- CAPTCHA.

### ETAPA 9 — WebView
Implementar:
- WebView screen;
- domínio visível;
- loading;
- erro;
- file chooser;
- Custom Tabs fallback;
- sessão separada por conector.

### ETAPA 10 — CAPTCHA
Implementar:
- detecção por evento do mock;
- pausa;
- notificação;
- sino vermelho;
- toast;
- repetição configurável;
- 1,3,5,10,15 minutos;
- resolver manualmente;
- retomar.

### ETAPA 11 — Robô
Implementar em SIMULAÇÃO:
- manual;
- supervisionado;
- automático limitado;
- piso;
- redução;
- margem;
- stop;
- pause;
- assume manual;
- logs.

### ETAPA 12 — Sala de Guerra
Implementar:
- sessões;
- robôs;
- alertas;
- CAPTCHA;
- autorizações;
- mensagens;
- emergência.

### ETAPA 13 — Mensagens
Implementar:
- pregoeiro;
- urgência;
- resumo IA mock;
- resposta sugerida;
- aprovação.

### ETAPA 14 — Auditoria
Registrar:
- ação;
- data;
- usuário;
- sessão;
- valor;
- resultado.

### ETAPA 15 — Multiempresa / Perfis
Criar:
- empresa;
- diretoria;
- licitações;
- financeiro;
- técnico;
- admin.

### ETAPA 16 — QA
Executar:
- unit tests;
- UI tests;
- lint;
- build.

## CRITÉRIOS DE ENTREGA

Você só deve considerar a entrega concluída quando:

- ./gradlew assembleDebug funciona;
- APK é gerado;
- app instala;
- não crasha na abertura;
- todas as telas principais são navegáveis;
- Radar funciona;
- Interesse funciona;
- PDF abre;
- 3 pregões mock rodam em paralelo;
- CAPTCHA pausa só uma sessão;
- alertas repetem;
- Sala de Guerra funciona;
- logs aparecem;
- multiempresa funciona;
- AI Provider é intercambiável;
- não existe segredo hardcoded;
- README explica instalação.

## ARQUIVOS DE SAÍDA

Entregar:
1. código-fonte completo;
2. app-debug.apk;
3. README.md;
4. ARCHITECTURE.md;
5. SECURITY.md;
6. TESTING.md;
7. ROADMAP.md;
8. CHANGELOG.md;
9. lista de integrações ainda mock;
10. instrução exata para instalar:

adb install -r app-debug.apk

## FORMA DE TRABALHO

Não me peça confirmação a cada etapa.
Avance até completar o máximo possível.

Ao encontrar bloqueio:
- documente;
- implemente fallback;
- continue.

Após cada bloco importante:
- rode build;
- corrija erros;
- não acumule falhas.

Não substitua implementação por pseudocódigo quando for possível implementar.

O resultado final deve parecer um produto real e premium, e não um protótipo acadêmico.

Comece agora lendo SDD_LicitaIA.md e executando a ETAPA 1.
