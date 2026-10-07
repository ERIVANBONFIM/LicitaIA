# Integração operacional do LicitaIA com o Compras.gov.br

Pesquisa em fontes públicas em 6 de outubro de 2026. Este documento é uma recomendação técnica: não comprova integração implementada ou homologada, não autoriza operações em pregões reais e não altera o aplicativo.

## Decisão recomendada

**Atualização após comparação de mercado:** a documentação da Effecti descreve API de parceiros BLL com chave por CNPJ e automação por navegadores no Comprasnet. Isso exige caminhos diferentes por portal. Investigar API BLL e condições de habilitação do LicitaIA é uma prioridade adicional; uma chave isolada não comprova acesso de qualquer aplicativo. Ver [comparativo ampliado](COMPARATIVO_SISTEMAS_LICITACOES_IA.md) e [documentação Effecti da integração BLL](https://ajuda.effecti.com.br/ajuda-2/como-gerar-chave-de-integracao-pelo-portal-bll/).

Construir o LicitaIA como aplicativo de preparação e acompanhamento, acompanhado por uma extensão para navegador no computador. Usar APIs públicas para descoberta de oportunidades e a sessão oficial aberta pelo próprio fornecedor para acompanhamento assistido. Avaliar automação nativa do portal antes de desenvolver envio automático externo.

Essa abordagem permite avançar sem esperar uma API privada de lances. O acompanhamento pela extensão tem precedentes comerciais documentados. Preenchimento e transmissão exigem validação específica do portal e dos termos aplicáveis; ainda não foram testados pelo LicitaIA.

## Evidências e limites

1. **Dados públicos:** o Compras.gov.br publica API e documentação interativa. O PNCP também documenta serviços de consulta. Consulta pública não concede permissão para transmitir propostas ou lances.
2. **Operação oficial:** a página do aplicativo Compras.gov.br informa cadastro de propostas para dispensa, pregão, concorrência e concurso eletrônicos; envio de lances pelo app especificamente em dispensa eletrônica; interação no julgamento/habilitação. Não generalizar lances pelo app a todos os pregões.
3. **Automação nativa prevista:** art. 19 da IN SEGES/ME 73/2022 e art. 9 da IN 67/2021 preveem parametrização de limites e envio automático pelo sistema. O TCU explica essa previsão. Normativo não comprova disponibilidade da funcionalidade em uma disputa concreta. Editais recentes condicionam seu uso à disponibilização no sistema.
4. **Extensão existente:** a descrição publicada pelo desenvolvedor de Quero Licitação na Chrome Web Store explica leitura da página por content script e painel de acompanhamento. Isso é evidência do modelo anunciado, não auditoria independente nem autorização do Compras.gov.br.
5. **Robôs comerciais:** WaveCode e Licitei anunciam envio de lances; Licitei declara operação em servidores, sem depender do computador ligado. As páginas não estabelecem, por si, API oficial autorizada ou arquitetura interna integral.
6. **API operacional:** não foi encontrado contrato público suficiente para implementar envio de lances de fornecedor por aplicativo externo. Não concluir que a API inexiste ou que todo produto comercial usa a mesma técnica.
7. **Limites da pesquisa:** não houve teste de robôs comerciais, análise de tráfego privado, extração de credenciais, instalação de extensão ou operação em disputa real. O link do manual vigente de pregão para fornecedor redirecionou a uma página de autenticação durante a consulta.

## Fluxo do fornecedor e responsabilidade de cada componente

| Etapa | Papel do LicitaIA | Execução no portal |
|---|---|---|
| Cadastro | Checklist de empresa, representação e documentos | Credenciamento e complementação SICAF, conforme requisitos |
| Descoberta | APIs PNCP/Compras, filtros, cache e alertas | Publicação e documentos oficiais |
| Análise | Ler edital/anexos, destacar exigências, datas e incertezas | Esclarecimentos e alterações oficiais |
| Preço | Calcular custos, tributos, frete, margem e piso por item/lote | Critério de julgamento e regras do edital |
| Proposta | Preparar valores e arquivos; futuramente auxiliar preenchimento | Revisão das declarações e submissão pelo fornecedor |
| Disputa | Acompanhar dados apresentados na sessão; sugerir valores | Registrar lances e confirmar recebimento |
| Pós-disputa | Alertar sobre convocação e organizar proposta ajustada | Negociação, anexos, habilitação, recursos e resultado |

O menor preço isolado não garante adjudicação ou contratação. Requisitos, habilitação e etapas posteriores continuam relevantes. A pesquisa não pretende substituir a leitura de um edital específico.

## Arquitetura proposta

- **Android:** manter descoberta, edital, documentos, cálculo de margem e alertas. Não depender de manter o aplicativo em primeiro plano para executar uma disputa inteira.
- **Extensão no computador:** começar somente com leitura do conteúdo visível da sessão autorizada, identificação do processo/item e acompanhamento de chat/estado. Exigir ação do usuário para ativação por site. Não coletar senha, cookies ou tokens para transferi-los ao celular.
- **Comunicação app/extensão:** se necessária, projetar pareamento explícito, isolamento por empresa e transmissão somente dos dados operacionais escolhidos. Essa ponte ainda não existe; sincronização entre computador e celular exige desenvolvimento e definição de infraestrutura.
- **Regras:** valores monetários decimais, piso/margem, unidade versus total, item versus lote, modo de disputa e intervalo do edital. Não pressupor intervalo universal.
- **Operações:** sugestões e preenchimento assistido primeiro; confirmação humana no portal. Só anunciar envio concluído após confirmação observável do portal. Sem confirmação, estado pendente, nunca repetição cega.
- **Automático:** preferir a parametrização oficial quando realmente disponível. Automação externa depende de regras/termos, autorização aplicável e teste do adaptador; não prometer operação autônoma com base em anúncios.

Uma extensão comum de desktop não vira automaticamente um componente Android. Para uso somente no celular, a opção imediata é LicitaIA para preparar e aplicativo/site oficial para operar. Outra alternativa futura é servidor de execução, mas acrescenta infraestrutura, disponibilidade, custos e gestão de sessões.

## Entrega por etapas e critérios para chamar de funcional

1. **Compras.gov.br em leitura:** comprovar que processo, empresa, item, mensagens e estado observados correspondem ao portal; parar quando sessão expirar ou página mudar.
2. **Proposta assistida:** comparar todos os campos com o edital; usuário revisa declarações e conclui submissão; guardar confirmação verificável.
3. **Lance assistido:** mostrar item, unidade, valor e margem antes da confirmação; bloquear abaixo do piso; conferir registro no portal; não reenviar diante de resposta incerta.
4. **Automação elegível:** verificar função nativa e condições do processo; se necessário avaliar conector externo separado. Testar primeiro em ambiente de treinamento acessível e apropriado, sem usar disputa real como teste.
5. **Outros portais:** implementar e validar adaptadores separados para BLL, Licitanet e Portal de Compras Públicas. Funcionamento no Compras não comprova funcionamento nos demais.

Falhas a verificar: troca de empresa, token/sessão expirados, CAPTCHA/MFA, item suspenso/encerrado, evento duplicado, perda de rede após envio, diferença entre valor unitário/global e atualização da página. CAPTCHA/MFA permanecem com o usuário; não contornar autenticação, bloqueios, proteções ou regras competitivas.

## Estado encontrado no projeto

- `connector-pncp` contém implementação de consultas públicas reais.
- `AssistedBidding.kt` registra informações e calcula sugestões; não envia ao portal.
- `PortalConnector.kt` define interface de envio, mas interface não comprova conector operacional.
- `RobotScreen.kt` informa automação indisponível. O texto que afirma categoricamente que a automação do navegador violaria termos de todos os portais precisa de fundamentação específica ou redação mais precisa. A pesquisa não verificou todos esses termos.
- Não foram realizadas alterações nesses arquivos.

## Fontes primárias

- [Documentação interativa de dados abertos Compras.gov.br](https://www.gov.br/compras/pt-br/cidadao/portal-de-dados-abertos/documentacao-interativa-da-api-de-dados-abertos)
- [Swagger de dados abertos](https://dadosabertos.compras.gov.br/swagger-ui/index.html)
- [Manual de integração PNCP](https://pncp.gov.br/manual/pt-br/latest/singlehtml/)
- [Perguntas e respostas PNCP](https://www.gov.br/pncp/pt-br/pncp/perguntas-e-respostas/)
- [Funcionalidades oficiais do aplicativo Compras.gov.br](https://www.gov.br/compras/pt-br/acesso-a-informacao/sistemas/conheca-o-compras/aplicativo-compras)
- [Manuais vigentes Compras.gov.br](https://www.gov.br/compras/pt-br/acesso-a-informacao/manuais)
- [IN SEGES/ME 73/2022, atualizada](https://www.gov.br/compras/pt-br/acesso-a-informacao/legislacao/instrucoes-normativas/instrucao-normativa-seges-me-no-73-de-30-de-setembro-de-2022)
- [IN SEGES/ME 67/2021, atualizada](https://www.gov.br/compras/pt-br/acesso-a-informacao/legislacao/instrucoes-normativas/instrucao-normativa-seges-me-no-67-de-8-de-julho-de-2021)
- [TCU: envio de lances](https://licitacoesecontratos.tcu.gov.br/5-3-envio-de-lances/)
- [Aviso CGU 94/2026: funcionalidade condicionada à disponibilidade](https://www.gov.br/cgu/pt-br/acesso-a-informacao/licitacoes-e-contratos/licitacoes/cotacoes-eletronicas/2026/dispensa-eletronica-no-94-2026-1/1-aviso-de-dispensa-eletronica-n-94_2026.pdf)
- [Quero Licitação: descrição do desenvolvedor na Chrome Web Store](https://chromewebstore.google.com/detail/quero-licita%C3%A7%C3%A3o-%E2%80%94-copilot/lijjcneehbagflcmlppnplmomaeklhgf)
- [Chrome: documentação de content scripts](https://developer.chrome.com/docs/extensions/develop/concepts/content-scripts)
- [Licitei: oferta e operação declarada em nuvem](https://www.licitei.com.br/robo-de-lances)
- [WaveCode: funcionalidades declaradas pelo fornecedor](https://www.wavecode.com.br/)
- [gov.br: orientação contra WebView no fluxo de autenticação móvel](https://acesso.gov.br/roteiro-tecnico/erroscomuns.html)
- [Compras.gov.br: canais oficiais de atendimento](https://www.gov.br/compras/pt-br/canais_atendimento/central-de-atendimento)

As fontes comerciais comprovam o que seus autores anunciam, não equivalem a homologação, garantia de funcionamento ou parecer sobre permissões.
