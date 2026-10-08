# CONTRATO_API — LicitaPRO (v0.1, F1)

Fonte: leitura do backend na VPS (`/opt/licitapro/backend/src/server.js`, `rotasRegistrarProposta.js`, `rotasVnc.js`, `executorAuth.js`, `executorGateway.js`, `prisma/schema.prisma`). Nada foi alterado na VPS. Nenhum segredo está neste documento.
Base URL: `https://<host-da-API>/api` (host exato a confirmar com o dono; o backend usa `FRONTEND_URL` default `https://licitapro.duckdns.org`; domínio final previsto `*.nexussystemtech.com.br`). Tudo JSON UTF-8. Erros: `{ "error": "mensagem" }` com HTTP 4xx/5xx.

## 1. Autenticação

### Fluxo
| Passo | Método e caminho | Auth | Corpo | Resposta |
|---|---|---|---|---|
| Cadastro | `POST /auth/register` | não | `{nome,email,senha,cnpj,razaoSocial}` | `{token,user}` |
| Login | `POST /auth/login` | não | `{email,senha}` | `{token,user}` |
| Logout | `POST /auth/logout` | Bearer | - | `{ok:true}` (apaga a sessão) |
| Esqueci senha | `POST /auth/forgot-password` | não | `{email}` | `{ok:true}` (sempre, não revela se existe); e-mail com link, token 1h |
| Redefinir | `POST /auth/reset-password` | não | `{token,senha(>=8)}` | `{ok:true}` |

Exemplo login:
```
POST /api/auth/login   {"email":"fulano@empresa.com","senha":"********"}
200 {"token":"eyJ...","user":{"id":"uuid","nome":"Fulano","email":"fulano@empresa.com","role":"admin",
     "empresa":{"id":"uuid","cnpj":"00000000000191","razaoSocial":"Empresa Exemplo LTDA"}}}
401 {"error":"Credenciais inválidas"}
```
Limite de tentativas: 50 por 15 min por IP em login/register; 2000 req/15 min por IP no geral.

### Token
- JWT HS256, validade **30 dias**, claims só `{ userId, iat, exp }`. **Não carrega empresaId nem role.**
- Além da assinatura, o token é guardado na tabela `Sessao` (token único, expiresAt, ip, userAgent). `logout` apaga a sessão; sem sessão = 401 "Sessão expirada". Não há refresh token do usuário: ao expirar (30 d), logar de novo.
- Cliente envia `Authorization: Bearer <token>` em toda chamada protegida. Empresa e role vêm do objeto `user` do login (guardar no cliente).

### Como a requisição é amarrada à empresa (tenant)
1. `authMiddleware`: valida JWT, busca `Sessao` -> `Usuario` -> `Empresa`; define `req.user` e `req.empresaId = usuario.empresaId`.
2. `monoempresaMw` (usado nas rotas de leitura): usa `empresaAtivaId` ou `empresaId` do usuário. Header `X-Empresa-Id` só é aceito se igual à empresa do usuário (autenticado).
3. Toda query de licitação filtra `where.empresaId = req.empresaId`. Hoje: **1 usuário = 1 empresa** (`Usuario.empresaId`); não existe tabela usuário-empresa.
4. Papéis: `admin`, `operador`, `viewer` (campo `role`). Praticamente **não é aplicado**: só `viewer` é barrado em `POST /ai/tenders/:id/analyze`. Registro público cria sempre `admin`.

### Pontos delicados de segurança (avisados ao orquestrador; corrigir em F3)
- CRÍTICO: `monoempresaMw` sem token cai na "empresa padrão" (primeira cadastrada) -> rotas `GET /licitacoes`, `/documentos`, `/notificacoes`, `/dashboard`, `/atas`, `/contratos` etc. respondem **sem login** com os dados da primeira empresa. Também aceita `X-Empresa-Id` sem token (qualquer empresa existente). Com 2+ empresas isso vaza dados entre tenants.
- CRÍTICO: `GET /empresas` e `POST /empresas` e `POST /empresas/trocar/:id` não exigem login (lista todas as empresas; POST faz upsert por CNPJ e pode alterar razão social).
- ALTO: `POST /auth/register` não verifica posse do CNPJ e, se o CNPJ já existe, o novo usuário entra como **admin da empresa existente** (tomada de empresa). Responde também erro cru (`err.message`).
- ALTO: rotas do executor sem filtro de tenant: `GET /executor/jobs`, `GET /executor/workers`, `POST /executor/workers/:id/revogar`, `POST /executor/registrar-proposta` (usa `findUnique` pela licitação sem checar empresa), `GET /robo-registro/jobs/:id`, `GET /logs/robo`. Basta um usuário logado qualquer.
- MÉDIO: `GET /robo-lances/ativas` busca configs de todas as empresas (filtra depois); `JWT_SECRET` tem fallback `"licitapro-default"` no código do executor (confirmar que o `.env` define o segredo real); `authMiddleware` não confere `usuario.ativo`; `.bak` de código no diretório.
- Nada de segredo foi lido ou copiado; recomenda-se rotacionar qualquer credencial que já tenha sido colada em chat/logs.

## 2. Endpoints por área
Legenda auth: **B** = exige Bearer; **B\*** = usa `monoempresaMw` (hoje aceita sem token -> ver seção 1; os clientes devem SEMPRE enviar Bearer, e F3 passará a exigir).
Listagem paginada padrão: `{ "data":[...], "total":N, "page":1, "totalPages":N }`.

### 2.1 Licitações / Radar
| Método e caminho | Auth | Parâmetros | Resposta |
|---|---|---|---|
| `GET /licitacoes` | B* | query: `fase, portal, estado, busca, incluirOcultas, incluirPassadas, dataAberturaInicio/Fim (YYYY-MM-DD), page, limit(<=200), ordenar(dataAbertura\|dataPublicacao\|dataEncerramento\|valorEstimado\|scoreRelevancia\|createdAt\|updatedAt\|numero), leve=true` | paginada de Licitacao (leve omite itens/tags/arquivos). Padrão esconde passadas e ocultas |
| `GET /licitacoes/minhas` | B* | - | `{data,total}` favoritas ou fase=participando (max 500) |
| `GET /licitacoes/arquivo` | B* | page, limit | arquivadas |
| `GET /licitacoes/:id` | B* | - | Licitacao + itens, tags, arquivos, propostas, lances(50), recursos, mensagens(100) |
| `GET /licitacoes/:id/itens` `/arquivos` `/avisos` | B* | - | arrays (busca lazy no PNCP se vazio) |
| `GET /licitacoes/:id/edital-pdf` | B* | - | PDF/redirect do edital |
| `GET /licitacoes/contadores/fases` | B* | - | contagem por fase |
| `POST /licitacoes` | B | campos: numero, orgao, objeto, modalidade, portal, fase, ... (whitelist) | Licitacao |
| `PUT /licitacoes/:id` | B | idem | Licitacao |
| `PUT /licitacoes/:id/fase` | B | `{fase}` | Licitacao |
| `PUT /licitacoes/:id/favoritar` `/ocultar` `/arquivar` | B* | - | Licitacao/ok |
| `PUT /licitacoes/:id/itens/:itemId` | B | valorProposto, marca, modelo... | item |
| `GET/POST /licitacoes/:id/tags`, `DELETE .../tags/:tagId` | B*/B | `{nome,cor}` | tags |
| `POST /licitacoes/importar-link` | B | `{url}` (PNCP) | Licitacao |
| `GET /radar/filtros` | B* | - | filtros salvos da empresa (+ contagem) |
| `POST /radar/filtros`, `PUT /radar/filtros/:id`, `DELETE /radar/filtros/:id` | B* | palavras-chave, UF, modalidade, valor min/max... | filtro |
| `GET /radar/filtros/:id/licitacoes` | B* | page, limit, ordenação | paginada (resultado do filtro; exclui "vistas") |
| `GET /radar/filtros/:id/licitacoes/export.csv` | B* | - | CSV |
| `POST /radar/sincronizar-agora` | B* | - | dispara coleta |
| `GET /radar/sugestoes/cidades\|ufs\|orgaos` | B* | `q` | listas |
| `GET /pesquisa-portal` | B* | busca no portal/PNCP | lista |
| `GET /dashboard`, `/agenda?mes&ano`, `/precos`, `/pca`, `/concorrente` | B* | - | agregados |

Exemplo item de lista (campos principais): `{id, numero:"PE 90003/2026", orgao, uasg, objeto, modalidade, modoDisputa, valorEstimado:"12345.67", dataPublicacao, dataAbertura, dataEncerramento, portal:"Comprasnet", portalUrl, urlProposta, estado, cidade, fase:"analise", status:"ativa", favorita:false, scoreRelevancia, scoreRisco, veredito, updatedAt, empresaId}`. Decimais vêm como string. Datas ISO-8601 UTC.
Fases: analise, recebendo_proposta, fase_lance, sessao_publica, ... Status: ativa, arquivada, oculta.

### 2.2 Análise / IA
| Método e caminho | Auth | Notas |
|---|---|---|
| `POST /ia/extrair-edital/:id` | B | extrai dados/itens do edital |
| `POST /ia/checklist-habilitacao/:id` | B | grava `checklistHabilitacao` |
| `GET\|POST /ia/analise-tecnica/:id` | B | parecer do consultor (GET lê guardado; POST gera, ~2 min) |
| `POST /ia/chat-edital/:id` `{mensagem,historico[]}` / `GET .../historico` | B | chat sobre o edital |
| `POST /ia/veredicto`, `/ia/preditor-preco`, `/ia/ocr-edital`(multipart `arquivo`), `/ia/chat`, `/ia/extrair-decremento/:id` | B | utilidades |
| `POST /ai/tenders/:id/analyze` -> `{jobId}`; `GET /ai/jobs/:id`; `GET /ai/tenders/:id/analysis` | B (viewer bloqueado) | fluxo assíncrono (n8n); fazer polling do job |
| `POST /recursos/gerar`, `GET /recursos`, `PUT /recursos/:id/enviar` | B/B*/B | recursos administrativos |
| `GET\|POST\|PUT\|DELETE /ia/provedores[/:id]`, `POST .../ativar`, `/testar`, `GET /ia/failover-status` | B | IA por usuário/empresa (chaves; nunca devolver chave em claro) |

### 2.3 Propostas
Não há CRUD REST de `Proposta` (modelo existe: id, valorTotal, status rascunho/gerada_ia/revisada/enviada/aceita/recusada, arquivoPdf/Docx, itensProposta). O que existe:
| Método e caminho | Auth | Notas |
|---|---|---|
| `POST /propostas/gerar-ia` | B | `{licitacaoId}` -> JSON da proposta (capa, planilhaPrecos, declarações) |
| `PUT /licitacoes/:id/itens/:itemId` | B | edita preços/marca/modelo do item (valorProposto) |
| `POST /licitacoes/:id/importar-itens` | B | importa itens |
| `GET /licitacoes/:id/preparacao-registro` | B | checagem de pronto para registrar |
| `POST /licitacoes/:id/registration-eligibility/refresh` | B | revalida situação no portal |

### 2.4 Robô (proposta e lance)
| Método e caminho | Auth | Notas |
|---|---|---|
| `GET /robo-lances/config/:licitacaoId` | B* | `{ativo,modoExecucao:"dry_run"\|"auto",estrategia,valorMinimo,decremento,intervaloSegundos,itemAlvo,observacao}` |
| `PUT /robo-lances/config/:licitacaoId` | B* | obrigatórios `valorMinimo`,`decremento`; `modoExecucao:"auto"` exige `confirmarAuto:true`; `pisosItens:[{numero,valorLanceMinimo}]` |
| `GET /robo-lances/prontidao/:licitacaoId`, `POST /robo-lances/preparar/:licitacaoId` | B* | estado do robô de disputa |
| `POST /robo-lances/participar/:licitacaoId` | B* | marca favorita + fase + arma robô em dry_run |
| `GET /robo-lances/ativas`, `GET /robo-lances/historico/:licitacaoId` | B* | lista / últimos 100 lances |
| `POST /executor/registrar-proposta` | B | `{licitacaoId, termoDeclaracoesAutorizado:true, declaracoes, permitirAbaixo50, permitirSobrescrever, operatorIntentId}`; sem o termo = 400 `TERMO_NAO_AUTORIZADO`. Cria job |
| `GET /executor/jobs/:id`, `GET /robo-registro/jobs/:jobId`, `GET /robo-registro/status`, `GET /robo-registro/navegadores` | B | status/polling do job e saúde do robô |
| `GET /executor/jobs`, `GET /executor/workers`, `POST /executor/workers/:id/revogar` | B | gestão de workers (hoje sem filtro de tenant) |
| `POST /executor/token` | refresh | troca `refreshToken` por `accessToken` (15 min) |
| `POST /integracoes/portal/:portal/abrir-login\|fechar\|verificar-sessao`, `/integracoes/comprasnet/*` | B | sessão no portal (navegador do robô VPS) |
| `POST /vnc/session` | B | sessão VNC para login manual/captcha |

Controle de dispatch: o servidor escolhe um worker (WebSocket) por empresa; ver 2.8.

### 2.5 Documentos e certificado
| Método e caminho | Auth | Notas |
|---|---|---|
| `GET /documentos` | B* | lista da empresa (id,categoria,nome,status,validade,tamanho) |
| `POST /documentos` | B | multipart: `arquivo` (<=50 MB), `nome`, `categoria`, `validade` |
| `GET /documentos/:id/download` | B* | arquivo |
| `DELETE /documentos/:id` | B | |
| `GET /certidoes` | B* | certidões |
| `GET\|POST\|DELETE /empresa/certificado` | B | A1 (multipart `arquivo`+senha). Atenção LGPD: com robô local o certificado não deve subir |
| `GET /licitacoes/:id/arquivos`, `/edital-pdf` | B* | arquivos do edital |
| `POST /integracoes/gdrive/upload` | B | |

### 2.6 Empresa / usuários
Hoje: `GET/POST /empresas` (sem auth, ver seção 1), `POST /empresas/trocar/:id`, `GET /user/export` (B*). **Não existem** rotas para listar/criar/desativar usuários, convite, nem editar perfil da empresa. -> Lacuna.

### 2.7 Notificações / agenda / mensagens
| `GET /notificacoes` | B* | `{data:[{id,tipo,titulo,mensagem,lida,canal,createdAt}], naoLidas:N}` (últimas 50, cache 30 s) |
| `PUT /notificacoes/ler-todas` | B* | `{ok:true}` |
| `GET /agenda?mes=&ano=` | B* | eventos |
| `GET /mensagens` | B* | mensagens do chat de disputa |
Não há "marcar uma como lida", paginação, nem push (FCM).

### 2.8 Tempo real
- Único WebSocket: **`wss://<host>/executor`**, para **workers do robô** (não para app/site). Auth: access token do executor (`Authorization: Bearer` ou `?token=`), escopo `executor`, JWT com `{sub:workerId, empresaId, portais[], tokenVersion, scope}`; refresh de 180 dias é trocado em `POST /executor/token` por access de 15 min.
- Mensagens servidor->worker: `welcome{heartbeatMs}`, `ping`, jobs. Worker->servidor: `register{versao,portais,sessoes}`, `pong`, `sessao_status{portal,logado,motivo}`, `job_ack{jobId}`, `job_result{jobId,status,resultado,erro}`, `job_query_response`.
- App/site: **sem canal em tempo real**; usar polling (job/notificações/lances).
- Outros: `GET /health` (`{status:"ok"}`), `/_metrics`, `POST /automation/n8n/callback` (HMAC, interno).

## 3. Lacunas (a criar no F3/F4)
| # | Lacuna | Fase |
|---|---|---|
| L1 | Sincronização incremental: nenhum endpoint "mudanças desde X". Só `updatedAt` existe nos modelos; sem filtro `updatedSince`, sem tombstones de exclusão. Criar `GET /sync?since=<ISO>&cursor=` (licitações, itens, favoritos, configs do robô, notificações, documentos-metadados) com `deleted[]` | F3 |
| L2 | "Pacote do dia por empresa": criar `GET /sync/pacote-dia` (licitações abertas relevantes aos filtros do radar da empresa + itens + avisos, tamanho limitado, com `etag`) | F3 |
| L3 | Escolha de local do robô (local x nuvem) por empresa/usuário: campo em Empresa/Usuario/Configuracao + `GET/PUT /empresa/robo-local`; trava "um robô por licitação" (lease com TTL por licitacaoId entre aparelhos e VPS) e rotas `POST /robo/lease`, `/robo/lease/renovar`, `/robo/lease/liberar` | F4 |
| L4 | Cliente local do robô (app/PC) precisa de credencial de worker com escopo por empresa: hoje o refresh do executor é provisionado manualmente (`ExecutorWorker`); criar `POST /executor/workers/registrar` (admin) e fluxo de pareamento | F4 |
| L5 | Filtro de tenant nas rotas do executor/robô (jobs, workers, revogar, registrar-proposta, logs) e checagem de role | F3/F4 |
| L6 | Mutações offline: fila do app precisa de idempotência. Adicionar header `Idempotency-Key` e `clientUpdatedAt` nos PUT de favoritar/ocultar/fase/itens/config robô + resolução de conflito (last-write-wins por campo) | F3 |
| L7 | Auth: refresh/renovação de token do usuário, `GET /auth/me` (role/empresa atuais), listar/encerrar sessões, `empresaId`+`role` como claims, 401 padronizado `{error,code}` (`TOKEN_EXPIRED`, `SESSAO_REVOGADA`) | F3 |
| L8 | Cadastro: convite (`POST /usuarios/convites`, `POST /auth/aceitar-convite`), auto-cadastro com verificação de CNPJ (código e-mail da Receita/certificado), fim da entrada como admin em empresa existente, CRUD de usuários e papéis | F3 |
| L9 | Proposta: CRUD (`GET/POST/PUT /licitacoes/:id/propostas`, itens da proposta, gerar PDF/DOCX, status), hoje só existe o modelo | F3 |
| L10 | Notificações: marcar uma como lida, paginação por cursor, registro de token FCM (`POST /dispositivos`) para push; e canal tempo real (SSE ou WebSocket de usuário) para lances/status do robô | F3 |
| L11 | Documentos: upload com `Idempotency-Key`, download por URL assinada curta para o app, listagem com `updatedSince` | F3 |
| L12 | Versão mínima do cliente: `GET /app/versao` (força atualização) e política de CORS para o app do Windows/Android (hoje `CORS` por env) | F3 |
| L13 | Contrato de erros e paginação unificados (hoje algumas rotas usam `error`, outras `erro`; paginação `data/total/page/totalPages` vs arrays soltos) | F3 |
| L14 | Corrigir os pontos críticos da seção 1 antes de abrir a uma segunda empresa, com teste multiempresa | F3 |

## 4. Guia para o F2 (app Android)
1. Tela de login: `POST /auth/login`. Guardar `token` em EncryptedSharedPreferences/Keystore e `user` (id, role, empresa.id/cnpj) localmente. Enviar `Authorization: Bearer` em tudo, **inclusive nas rotas de leitura** (não depender da "empresa padrão"). Não enviar `X-Empresa-Id`.
2. 401 em qualquer chamada: apagar token e voltar ao login (não há refresh; token dura 30 d). Se o login falhar com 429, aguardar (rate limit).
3. Offline-first (Room): tabelas locais licitação, item, notificação, config robô, fila de mutações. Enquanto L1/L2 não existem, sincronizar assim:
   - Carga inicial/pull periódico (WorkManager, ex.: a cada 15-30 min e ao abrir): `GET /licitacoes?leve=true&limit=200&page=N&ordenar=updatedAt` até acabar; comparar `updatedAt` e fazer upsert por `id`. Ordenar por `updatedAt` desc permite parar na primeira página cujo `updatedAt` já é conhecido (aprox. incremental). Exclusão/ocultação: pedir também `incluirOcultas=true` para refletir `status`.
   - Detalhe sob demanda: `GET /licitacoes/:id` (+ `/itens`, `/arquivos`, `/avisos`); baixar editais/arquivos só quando o usuário abrir ou marcar favorito.
   - `GET /notificacoes` a cada ciclo; badge = `naoLidas`.
   - Quando F3 entregar `GET /sync?since=`, trocar o pull acima por ele, guardando o `cursor` retornado.
4. Escritas offline: gravar na fila (id local, tipo, payload, hora); enviar em ordem quando houver rede: `PUT /licitacoes/:id/favoritar|ocultar|arquivar|fase`, `PUT /licitacoes/:id/itens/:itemId`, `PUT /robo-lances/config/:id`. Reaplicar com segurança: essas chamadas são idempotentes por estado final. Em 404 descartar; em 5xx/timeout tentar de novo com backoff.
5. Robô: ao armar, usar `POST /robo-lances/participar/:id` (cria em `dry_run`); para lances reais `PUT /robo-lances/config/:id` com `modoExecucao:"auto"` e `confirmarAuto:true` (confirmação explícita do usuário na UI). Acompanhar com polling de `GET /robo-lances/historico/:id` e `GET /robo-lances/prontidao/:id`, e jobs com `GET /executor/jobs/:id`. Sem websocket para o app. Respeitar a trava "um robô por licitação" (L3) quando existir.
6. Papéis: esconder ações de escrita para `viewer`; o servidor ainda não garante isso (L5/L7).
7. Valores decimais chegam como string: converter com BigDecimal. Datas ISO-8601 UTC; mostrar em America/Sao_Paulo. Respeitar `Cache-Control: no-store` (a API não cacheia no cliente).
8. Atualização do app: manter o requisito de checagem de versão (GitHub Releases) já existente; `GET /app/versao` virá em L12.
9. Segredos: o certificado A1 e a senha do portal ficam no aparelho quando o robô é local; não subir via `POST /empresa/certificado` nesse modo.

## 5. Guia curto para F4 (robô) e F5 (Windows)
- Worker local (app/PC) deve usar o protocolo de `/executor` (seção 2.8): receber refresh provisionado, trocar por access a cada (re)conexão, `register` com portais e sessões, responder `ping/pong`, processar jobs com `job_ack` e `job_result`. Falta o pareamento/registro self-service e escopo por usuário (L4) e o lease (L3).
- Cliente Windows/site usam o mesmo `/auth/*` e as mesmas rotas das seções 2.1-2.7.
