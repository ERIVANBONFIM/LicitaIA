# SDD — Plataforma LicitaPRO (conciso)

Documento de projeto. Base: sistema LicitaPRO já rodando na VPS (177.7.45.152) + app Kotlin atual (renomear para LicitaPRO). Atualizado 08/10/2026.

## 1. Decisões do dono (Erivan)
- Marca única **LicitaPRO** (app, site, programa, e-mails, PDFs). App Kotlin muda o nome de exibição.
- Pode trabalhar direto na VPS (está no ar, sem uso real; só 1 empresa, 0 propostas). **Backup antes de cada mudança.**
- Cadastro: **convite + auto-cadastro**, os dois prontos para uso.
- Robô: **opção por empresa/usuário** — roda local (aparelho/PC) ou na nuvem (VPS).
- Programa Windows: 1 agente dedicado em paralelo.
- Domínio: usar os atuais (`*.nexussystemtech.com.br`).

## 2. Arquitetura (resumo; diagrama no HTML)
Clientes (App Android, Site, Programa Windows, Painel do dono) → HTTPS → VPS (nginx, API 140 rotas, PostgreSQL por CNPJ, Redis, arquivos, robô coletor, robô VPS) → Portais (PNCP, Compras.gov.br, Comprasnet, IA). Um login serve os clientes. App/site baixam só o pacote da empresa; offline-first com fila e ressincronização.

## 3. Base já pronta (não refazer)
Login usuário/senha (bcrypt+JWT: register/login/logout/forgot/reset), modelo multiempresa (Empresa cnpj único → Usuarios com role admin/operador/viewer), 140 rotas, coletor diário (144k licitações), robô de proposta/lance (navegador+A1+anti-captcha+controle de disputa), site React no ar, 50+ tabelas.

## 4. Fusões (módulos) — ver orquestração (§7) para agente/modelo/prazo
- **F1 Contrato de integração (API+Auth):** especificar o contrato que app/site/programa usam. Bloqueia F2,F4. *Só leitura na VPS.*
- **F2 App Android ↔ VPS:** login + sincronização incremental + offline + rebrand no app. Depende de F1.
- **F3 Multiempresa & Cadastro:** convite + auto-cadastro + verificação de CNPJ (código e-mail Receita / certificado) + painel do dono + reforço de isolamento. Backend VPS.
- **F4 Robô local-ou-nuvem:** escolha por empresa/usuário; trava "um robô por licitação" entre aparelhos e VPS. Depende de F1; coordena com F2 e robô VPS.
- **F5 Programa Windows:** shell desktop do site + ponte do robô com o certificado do PC. Paralelo; integra pelo contrato F1.
- **F6 Rebrand LicitaPRO:** nome/logo/textos no app, site, e-mails, PDFs.
- **F7 Higiene & Deploy:** limpar `.bak`, pipeline de publicação, retenção de tabelas que incham, conferência de backup.

## 5. Cadastro e papéis
- **Convite:** dono cria empresa (CNPJ→Receita) + admin; admin cria colaboradores.
- **Auto-cadastro:** empresa digita CNPJ, verifica posse (código no e-mail da Receita OU certificado), vira admin; dono ativa plano.
- Regra: 1 CNPJ = 1 empresa; quem verifica primeiro é admin.
- Papéis: admin / operador / visualizador (detalhar Financeiro/Técnico depois).

## 6. Isolamento, segurança, dados
- Todo registro com CNPJ dono; API filtra pelo tenant do token; **teste multiempresa obrigatório** antes de abrir.
- Certificado/senha do portal nunca saem do aparelho quando o robô é local; na opção VPS, só com consentimento e guarda explícita.
- IA: cada usuário conecta a sua; opcional uma chave da empresa.
- Backup do banco antes de cada mudança + diário 03:00 (já existe).

## 7. Orquestração
- **Orquestrador (coordenador):** acompanha todos, corrige quem sai do escopo ("broca"), sequencia dependências, junta aprovações para o fim.
- **Regra:** agentes não param por decisão humana; só pausam em algo **delicado** (mutação destrutiva em produção, jurídico/LGPD, cobrança). Nesse caso 1 agente aguarda e os outros seguem; aprovações não urgentes vão para o fim.
- **Comunicação:** quem constrói uma dependência avisa o dependente quando terminar (ex.: F1→F2). Todos reportam progresso ao orquestrador.
- **Modelos (econômico/rápido):** F1 sonnet · F2 opus · F3 sonnet · F4 sonnet · F5 sonnet · F6 haiku · F7 haiku.
- **Prazos (estimativa de trabalho):** F1 ~1 dia · F2 ~4–6 dias · F3 ~3–4 dias · F4 ~2–3 dias · F5 ~4–5 dias (paralelo) · F6 ~1 dia · F7 ~2 dias. Total ~2–3 semanas com paralelismo.
- **Gate de produção:** F3, F4-backend e F7 mutam a VPS → começam após 1 OK do dono (único ponto delicado). F1, F5, F6 e o preparo de F2 começam já (não mutam produção).

## 8. Riscos
Produção com dados reais (backup sempre); credenciais centralizadas (política de guarda); código com muitos `.bak` (pôr em pipeline); tabelas que incham (retenção); isolamento multiempresa (testar a fundo).
