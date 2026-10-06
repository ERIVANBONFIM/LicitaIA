# LICITAIA — Software Design Document (SDD)
Versão: 1.0
Plataforma alvo: Android APK
Arquitetura: Mobile-first, com suporte opcional a backend/IA externa e conectores por portal
Idioma inicial: pt-BR

---

## 1. Visão do Produto

LicitaIA é um aplicativo Android para centralizar o ciclo operacional de licitações públicas e pregões eletrônicos.

O aplicativo deve permitir:
- localizar oportunidades;
- criar radares personalizados;
- analisar editais com IA;
- classificar aderência da empresa;
- organizar documentos;
- gerar propostas;
- revisar proposta em PDF;
- aprovar participação;
- preparar envio ao portal correspondente;
- acompanhar pregões em tempo real;
- executar robô de lances supervisionado;
- operar múltiplos pregões simultaneamente em sessões isoladas;
- exibir o portal em WebView quando permitido;
- alertar CAPTCHA/MFA e exigir resolução manual;
- centralizar mensagens do pregoeiro;
- registrar auditoria;
- controlar risco, piso, margem e estratégia;
- operar múltiplas empresas e perfis de acesso.

O sistema NÃO deve contornar CAPTCHA, MFA ou mecanismos de segurança do portal.
Quando houver CAPTCHA/MFA, a sessão deve pausar e exigir intervenção humana.

---

## 2. Objetivo do MVP

Entregar um APK Android instalável, funcional e navegável, com:
1. autenticação local do LicitaIA;
2. dashboard premium;
3. menu lateral;
4. sino de notificações;
5. Radar de Licitações;
6. Licitações de Interesse;
7. análise IA;
8. “Vale a pena participar?”;
9. documentos;
10. proposta em PDF;
11. aprovação humana;
12. Pregões ao Vivo;
13. WebView por sessão;
14. robô de lances supervisionado em modo de simulação inicialmente;
15. múltiplas sessões paralelas;
16. CAPTCHA workflow;
17. Sala de Guerra;
18. estratégias de lance;
19. simulador;
20. mensagens do pregoeiro;
21. concorrência;
22. auditoria;
23. multiempresa e perfis.

A primeira entrega deve funcionar sem depender de um backend próprio obrigatório.
Integrações reais com portais e provedores de IA devem ser desacopladas por interfaces.

---

## 3. Escopo Funcional

### 3.1 Tela de abertura / Login
Campos:
- e-mail;
- senha;
- empresa ativa;
- biometria/PIN opcional.

Ações:
- entrar;
- criar conta local;
- lembrar sessão;
- escolher empresa ativa.

Após login:
- carregar dashboard;
- verificar notificações pendentes;
- restaurar sessões de pregões ativos.

---

### 3.2 Dashboard inicial

Visual premium, escuro, moderno, com alto contraste e cartões de status.

Indicadores:
- Pregões ao vivo;
- Robôs ativos;
- Alertas;
- Radares encontrados;
- Licitações de interesse;
- Documentos vencendo;
- Autorizações pendentes.

Atalhos:
- Buscar licitações;
- Radar;
- Licitações de interesse;
- Analisar edital;
- Pregões ao vivo;
- Robô;
- Sala de Guerra;
- Documentos;
- Portais;
- Configurações.

---

## 4. Navegação

### 4.1 Menu lateral
Itens:
- Início
- Buscar Licitações
- Radar de Licitações
- Licitações de Interesse
- Analisar Edital
- Minhas Participações
- Pregões ao Vivo
- Sala de Guerra
- Estratégia
- Simulador
- Mensagens do Pregoeiro
- Concorrência
- Robô de Lances
- Documentos
- Portais Conectados
- Auditoria
- Empresas e Perfis
- Configurações
- Segurança
- Sair

### 4.2 Bottom navigation
Opcional:
- Início
- Radar
- Pregões
- Robô
- Mais

---

## 5. Radar de Licitações

Usuário deve poder criar múltiplos radares.

Campos:
- nome do radar;
- segmento;
- palavras-chave;
- palavras proibidas;
- portal;
- todos os portais conectados;
- UF;
- região;
- órgão;
- modalidade;
- valor mínimo;
- valor máximo;
- data inicial/final;
- score mínimo da IA;
- CNAE opcional;
- objeto preferencial;
- exigir ou não atendimento técnico local.

Exemplos de segmentos:
- Telecom / ISP
- TI
- Software
- Equipamentos
- Serviços
- Personalizado

Resultado:
- lista de oportunidades;
- score;
- portal;
- órgão;
- objeto;
- valor;
- data;
- botão “Tenho Interesse”;
- botão “Analisar”.

---

## 6. Fluxo “Tenho Interesse”

Ao clicar em “Tenho Interesse”:
1. salvar a licitação em “Licitações de Interesse”;
2. baixar/registrar edital quando disponível;
3. iniciar análise IA;
4. extrair:
   - objeto;
   - órgão;
   - portal;
   - número;
   - modalidade;
   - valor estimado;
   - data limite;
   - abertura;
   - sessão;
   - prazo de instalação;
   - SLA;
   - exigências técnicas;
   - documentos;
   - garantias;
   - penalidades;
   - exigências de atestado;
   - exigências de certificação;
5. comparar com perfil/documentos da empresa;
6. gerar score de aderência;
7. gerar recomendação:
   - PARTICIPAR;
   - AVALIAR;
   - NÃO PARTICIPAR;
8. sugerir faixa de proposta;
9. gerar checklist de pendências;
10. permitir gerar proposta PDF;
11. permitir revisão;
12. permitir aprovação;
13. preparar submissão ao portal;
14. exigir confirmação final humana.

---

## 7. “Vale a pena participar?”

Tela executiva.

Indicadores:
- Score geral;
- Aderência técnica;
- Aderência documental;
- Aderência financeira;
- Margem estimada;
- Risco operacional;
- Risco documental;
- Prazo;
- Concorrência histórica;
- Compatibilidade geográfica;
- Necessidade de investimento;
- Risco contratual.

Saída:
- PARTICIPAR;
- AVALIAR;
- NÃO PARTICIPAR.

Deve mostrar justificativa objetiva e pontos críticos.

---

## 8. Proposta Comercial

Permitir:
- gerar proposta;
- editar valores;
- editar prazo;
- editar observações;
- salvar versão;
- comparar versões;
- visualizar PDF no celular;
- compartilhar;
- aprovar;
- rejeitar;
- enviar para nova revisão.

Antes de qualquer envio real:
- mostrar portal;
- valor;
- lote/item;
- empresa;
- usuário responsável;
- timestamp;
- exigir confirmação final.

---

## 9. Documentos / Cofre

Tipos:
- Contrato Social
- CNPJ
- Certidão Federal
- Certidão Estadual
- Certidão Municipal
- FGTS
- Trabalhista
- Balanço
- SCM
- CREA/CRT quando aplicável
- Atestados
- Declarações
- Procurações
- Certificados
- Outros

Funcionalidades:
- validade;
- alertas de vencimento;
- anexos;
- tags;
- empresa proprietária;
- OCR opcional;
- comparação com exigências do edital;
- status:
  - válido;
  - vence em breve;
  - vencido;
  - ausente.

---

## 10. Portais

Arquitetura por conectores.

Interface conceitual:
PortalConnector

Métodos:
- authenticate()
- restoreSession()
- listOpportunities()
- getTenderDetails()
- getMessages()
- prepareProposal()
- submitProposal()
- openLiveSession()
- readCurrentBidState()
- submitBid()
- pauseAutomation()
- logout()

Cada conector deve declarar:
- suporte a WebView;
- suporte a API oficial;
- suporte a automação de navegador;
- suporte a sessão persistente;
- MFA;
- CAPTCHA;
- limitações.

Portais iniciais:
- Compras.gov.br
- BLL
- Licitanet
- Portal de Compras Públicas

Nunca presumir que há API oficial para ações autenticadas.
Se não houver API, implementar apenas adaptador mock até validação técnica/contratual.

---

## 11. CAPTCHA / MFA

Regra obrigatória:
- nunca tentar quebrar, resolver ou contornar CAPTCHA;
- nunca tentar burlar MFA;
- pausar somente a sessão afetada;
- notificar usuário;
- abrir fluxo seguro;
- permitir resolução manual;
- retomar somente após confirmação.

Notificação:
“CAPTCHA aguardando — sessão pausada.”

Configuração de repetição:
- 1 minuto;
- 3 minutos;
- 5 minutos;
- 10 minutos;
- 15 minutos;
- desativado.

Enquanto pendente:
- sino vermelho;
- toast na tela;
- vibração opcional;
- push local;
- repetição até resolução ou pausa manual.

---

## 12. Pregões ao Vivo

Cada pregão deve rodar em uma sessão independente.

Exibir:
- portal;
- pregão;
- item;
- status;
- posição;
- nosso último lance;
- melhor lance;
- piso;
- margem;
- estratégia;
- tempo restante quando disponível;
- status do robô.

Ações:
- abrir WebView;
- pausar;
- retomar;
- assumir manualmente;
- alterar estratégia;
- encerrar robô.

---

## 13. Múltiplos Pregões Simultâneos

Requisito crítico.

Modelo:
- uma instância lógica por sessão;
- isolamento de estado;
- isolamento de credencial;
- isolamento de item/lote;
- fila própria de eventos;
- log próprio;
- controle próprio de CAPTCHA;
- controle próprio de estratégia.

As sessões não podem compartilhar:
- valores;
- seletores;
- cookies sem intenção explícita;
- contexto de item;
- credenciais de outra empresa.

---

## 14. WebView

Objetivo:
permitir que o usuário veja e, quando permitido, interaja com o portal dentro do app.

Requisitos:
- cookie/session store por portal;
- isolamento por conta;
- controles voltar/avançar;
- indicador de domínio;
- indicador de conexão segura;
- bloquear navegação para domínios não autorizados;
- opção “Assumir manualmente”;
- tela de erro;
- tratamento de download/upload;
- suporte a file picker;
- suporte a MFA/CAPTCHA manual.

Se o portal bloquear WebView:
- abrir Custom Tabs / navegador externo autenticado quando possível;
- manter painel de telemetria no app.

---

## 15. Robô de Lances

Modo inicial:
SIMULAÇÃO.

Modos futuros:
- manual;
- supervisionado;
- automático limitado.

Configurações:
- preço inicial;
- piso;
- redução;
- margem mínima;
- limite de perda;
- intervalo mínimo;
- item/lote;
- estratégia;
- pedir autorização em condições específicas.

Estratégias:
- Conservadora
- Agressiva
- Acompanhar concorrente
- Personalizada

Regras:
- nunca ultrapassar piso;
- nunca atuar em item incorreto;
- nunca enviar lance em sessão diferente;
- nunca operar com CAPTCHA pendente;
- nunca continuar após erro crítico;
- registrar cada ação;
- permitir “PARAR E ASSUMIR”.

---

## 16. Sala de Guerra

Tela executiva de operações simultâneas.

Mostrar:
- pregões ativos;
- robôs ativos;
- CAPTCHAs;
- autorizações pendentes;
- mensagens;
- margem;
- risco;
- alertas críticos;
- status de cada sessão.

Botão:
“PAUSAR TODOS OS ROBÔS”

Ação deve:
- pedir confirmação;
- pausar todas as automações;
- manter sessões abertas;
- registrar auditoria.

---

## 17. Simulador

Permitir testar estratégia sem portal real.

Parâmetros:
- número de concorrentes;
- preço inicial;
- piso;
- comportamento dos concorrentes;
- redução;
- tempo;
- estratégia.

Saída:
- preço final;
- posição;
- margem;
- número de lances;
- motivo da parada;
- recomendação.

---

## 18. Mensagens do Pregoeiro

Central:
- novas mensagens;
- não lidas;
- urgentes;
- prazo de resposta;
- IA resume;
- IA sugere resposta;
- usuário revisa;
- usuário aprova;
- envio somente com confirmação.

---

## 19. Concorrência

Histórico:
- quantidade de concorrentes;
- faixas de fechamento;
- média por objeto;
- comportamento de lances;
- vitórias/derrotas internas;
- evolução da margem.

Não expor dados não públicos.

---

## 20. Auditoria

Registrar:
- login;
- troca de empresa;
- conexão de portal;
- análise;
- geração de documento;
- aprovação;
- mudança de piso;
- ativação/pausa;
- lance;
- CAPTCHA;
- mensagem;
- envio;
- erro.

Campos:
- timestamp;
- usuário;
- empresa;
- portal;
- pregão;
- item;
- ação;
- valor anterior;
- valor novo;
- motivo;
- origem;
- resultado.

---

## 21. Multiempresa

Suportar várias empresas/CNPJs.

Cada empresa:
- documentos;
- portais;
- usuários;
- permissões;
- radares;
- estratégias;
- histórico;
- IA preferida;
- regras.

Empresa ativa deve ficar sempre visível no topo.

---

## 22. Perfis / RBAC

Perfis:
Diretoria:
- aprovar piso;
- aprovar envio;
- alterar regras;
- visualizar tudo.

Licitações:
- buscar;
- analisar;
- preparar;
- responder;
- operar sessões conforme permissão.

Financeiro:
- custo;
- margem;
- preço mínimo;
- aprovação financeira.

Técnico:
- viabilidade;
- SLA;
- prazo;
- cobertura.

Administrador:
- tudo.

---

## 23. Provedores de IA

Arquitetura desacoplada por AIProvider.

Provedores possíveis:
- OpenAI
- Anthropic
- Google Gemini
- API customizada
- modelo local futuro

Interface:
- analyzeTender()
- summarize()
- extractRequirements()
- scoreFit()
- suggestProposalRange()
- draftProposal()
- draftMessage()
- draftAppeal()
- compareDocuments()

Nunca armazenar chave em texto puro.

Usar:
- Android Keystore;
- EncryptedSharedPreferences/DataStore seguro.

---

## 24. UI/UX

Direção visual:
- premium;
- dark mode;
- cards;
- gradientes discretos;
- verde para sucesso;
- amarelo para atenção;
- vermelho para crítico;
- azul para ações;
- tipografia limpa;
- ícones consistentes;
- microanimações;
- feedback tátil opcional;
- skeleton loading;
- empty states;
- erros amigáveis;
- botões grandes em ações críticas.

Status devem ser sempre visíveis.

---

## 25. Arquitetura Android Recomendada

Kotlin
Jetpack Compose
Material 3
MVVM
Clean Architecture
Navigation Compose
Hilt
Room
DataStore
WorkManager
Coroutines / Flow
Retrofit / OkHttp
WebView / Custom Tabs
Android Keystore
PDF renderer/viewer
Coil
JUnit
MockK
Compose UI tests

Módulos sugeridos:
- app
- core-ui
- core-data
- core-network
- core-security
- core-ai
- feature-auth
- feature-dashboard
- feature-radar
- feature-tender
- feature-documents
- feature-live
- feature-bidding
- feature-warroom
- feature-settings
- feature-audit
- connector-api
- connector-mock
- ai-provider-api
- ai-provider-mock

---

## 26. Persistência

Room:
- Company
- UserProfile
- Radar
- Opportunity
- Tender
- TenderAnalysis
- Document
- Proposal
- PortalSession
- LiveSession
- BidRule
- BidEvent
- Notification
- AuditEvent
- AIConfig

Segredos:
- Keystore.

---

## 27. Notificações

Canais Android:
- Críticas
- CAPTCHA
- Lances
- Mensagens
- Documentos
- Radar
- Sessões
- Geral

Prioridade:
CAPTCHA > autorização de lance > mensagem do pregoeiro > prazo > radar.

---

## 28. Segurança

Obrigatório:
- HTTPS;
- certificate pinning quando aplicável;
- Keystore;
- não logar senha/token;
- logout remoto futuro;
- lock por biometria;
- timeout;
- proteção de screenshot em telas sensíveis opcional;
- root detection opcional;
- auditoria;
- confirmação dupla em ação vinculante.

---

## 29. Modo Offline

Disponível offline:
- documentos cacheados;
- propostas;
- análises já geradas;
- histórico;
- radares;
- auditoria local.

Não disponível offline:
- portal;
- pregão;
- lance;
- autenticação externa;
- busca online.

---

## 30. Critérios de Aceite do MVP

O APK deve:
- compilar;
- instalar em Android físico;
- abrir sem crash;
- navegar por todas as telas;
- persistir configurações;
- criar radar;
- marcar interesse;
- abrir resumo;
- exibir “Vale a pena participar?”;
- gerar/abrir PDF de proposta mock;
- simular aprovação;
- simular pregão;
- rodar 3 sessões mock paralelas;
- exibir WebView mock;
- pausar sessão por CAPTCHA;
- repetir alerta conforme intervalo;
- mostrar Sala de Guerra;
- salvar logs;
- trocar empresa;
- permitir escolher provedor de IA;
- ter testes mínimos.

---

## 31. Fases de Implementação

Fase 1:
UI + navegação + dados mock.

Fase 2:
persistência local + documentos + PDF + IA mock.

Fase 3:
integração real com provedor de IA.

Fase 4:
conectores somente leitura para portais.

Fase 5:
sessões autenticadas e WebView.

Fase 6:
automação supervisionada validada por portal.

Fase 7:
telemetria, auditoria, hardening e testes.

---

## 32. Fora do Escopo Inicial

- bypass de CAPTCHA;
- bypass de MFA;
- automação não autorizada por portal;
- compartilhamento ilegal de credenciais;
- scraping agressivo;
- armazenamento inseguro de senha;
- submissão real sem confirmação humana;
- decisão jurídica autônoma.

---

## 33. Definição de Pronto

Projeto pronto quando:
1. APK debug instala;
2. APK release assinável;
3. build reproduzível;
4. README completo;
5. arquitetura modular;
6. testes principais passando;
7. telas completas;
8. dados mock realistas;
9. logs;
10. segurança básica implementada;
11. sem segredos hardcoded;
12. sem bypass de segurança;
13. projeto importável no Android Studio;
14. instruções claras para instalar via adb e manualmente.
