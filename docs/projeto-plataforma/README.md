# Projeto futuro — Plataforma LicitaIA multiempresa

Registrado em 07/10/2026. **Nada aplicado ainda.** Abra os `.html` no navegador para ver as páginas completas.

| Arquivo | Conteúdo |
|---|---|
| `01-plataforma-multiempresa.html` | Projeto principal: VPS + APK + programa Windows, multiempresa por CNPJ |
| `02-equipe-mesma-conta.html` | Equipe usando a mesma conta da empresa (perfis, robô, certificado) |
| `03-play-store.html` | Alternativa de distribuição pela Play Store (cadastro por CNPJ, planos) |
| `04-ideias-aprovadas.html` | As 12 funções futuras aprovadas |

## Decisões do dono (Erivan)

- Distribuição por link/e-mail: APK no Android e programa no PC, sem depender da loja.
- APK e programa ligados por API a uma VPS (já existe: **VPS "licitapro"**).
- Milhares de aparelhos e empresas no mesmo servidor; nenhuma empresa enxerga outra (tudo por CNPJ).
- O administrador da empresa cria **usuário e senha** de cada colaborador.
- Cada colaborador configura o próprio radar, autentica o **próprio provedor de IA**, instala o **certificado A1 no próprio aparelho** (o certificado nunca vai para o servidor) e loga nos portais.
- Tudo o que a empresa faz é visto por todos que têm acesso àquele CNPJ, com comunicação entre eles.
- Qualquer colaborador pode soltar o robô de proposta ou de lances (trava: um robô por licitação).
- Robô coletor na VPS busca licitações e dispensas novas todo dia; aparelhos só sincronizam o pacote da própria empresa.
- Funciona sem internet e sincroniza quando a conexão volta.

## Proposta técnica

- **Servidor (VPS):** Linux + Docker; API em Kotlin reaproveitando `core-domain` e os conectores (PNCP, Compras.gov.br); PostgreSQL com todas as linhas marcadas pela empresa; arquivos por empresa; robô coletor da madrugada; painel do dono da plataforma; backup diário; HTTPS com domínio próprio.
- **APK:** mantém o banco local como cópia; ganha login da plataforma, fila de envio offline e sincronização incremental; atualização pelo próprio servidor.
- **PC:** Compose Desktop (mesmas telas do app) com navegador do portal embutido para o robô e certificado do Windows.
- **Sincronização:** offline-first (fila local + "mudanças desde a versão X"), tempo real com conexão aberta, conflitos resolvidos por versão; robô e aprovação usam trava.

## Fases

1. Servidor e login (usuários e senhas, admin da empresa) — primeira empresa: ME Telecom.
2. Sincronização (licitações, análises, perguntas, propostas, documentos, planos do robô).
3. Robô coletor na VPS (busca central, radar por empresa, monitor de fase).
4. Programa no PC (Windows).
5. Equipe e comunicação (conversa por licitação, trava de robô, Sala de Guerra compartilhada, push).
6. Abrir para outras empresas (painel do dono, planos, página de download, termos/LGPD).

## Próximo passo

O dono vai passar o acesso à VPS "licitapro" para verificarmos o que já existe lá e decidir como continuar.
