# Sistemas de licitações, IA e automação: comparação para o LicitaIA

Pesquisa pública em 6 de outubro de 2026. Fontes: páginas dos fabricantes, documentação de suporte, Chrome Web Store e documentação oficial. Não foram contratados produtos, fornecidas credenciais, instalados robôs ou executados lances. Cobertura e resultados comerciais abaixo são declarações dos fornecedores, não auditoria independente.

## Conclusão técnica

A solução é modular: dados públicos para busca; IA para documentos; motor de regras para preço e estratégia; conector específico para operar cada portal. Não há evidência de uma IA única que faça todo o processo de forma autônoma e confiável em qualquer portal.

A afirmação anterior de que todos os portais não oferecem integração era ampla demais. A documentação da Effecti descreve API BLL com chave de integração por empresa e uso de navegadores na automação Comprasnet. Esses caminhos são diferentes. Ter chave BLL não prova que um aplicativo novo esteja habilitado como parceiro ou que a chave permita todas as operações; faltam documentação, escopos e condições de adesão para o LicitaIA.

## Produtos identificados

| Sistema | IA e operação anunciadas | Cobertura de disputa ou foco documentado | Evidência útil para o projeto |
|---|---|---|---|
| Effecti / Minha Effecti / Aimê | Busca, propostas, robô, chat; IA para cláusulas e exigências | Ajuda documenta Comprasnet, BLL, BNC, Compras Públicas, Licitanet e outros adaptadores | Base técnica detalhada: modalidades, limites, acesso, API BLL e automação por navegadores no Comprasnet |
| ConLicitação / ConLicita IA | Boletins, análise de mercado, resumo/perguntas ao edital e apoio jurídico; robô | Página do robô lista Licitanet, BLL e BNC | Não atribuir ao robô todos os portais pesquisados; IA e execução são módulos distintos |
| WaveCode | Busca, propostas, robô, chat; diagnóstico da disputa por IA | Site declara Comprasnet, Licitações-e, BLL, BNC, Compras Públicas e portais estaduais | Exemplo de fluxo completo e diagnóstico posterior; protocolo interno não publicado nas páginas consultadas |
| Lance Fácil | Busca, cadastro, robô, chat e documentos | Matriz inclui Comprasnet, BLL, BNC, PCP, Licitanet, Licitações-e e outros; Comprasnet Bahia aparece para busca | Evidência de automação por portal; não foi demonstrado módulo específico de IA generativa nas fontes analisadas |
| Licitei / Licitei IA | PNCP, análise de edital, documentos, propostas, chat e robô | Robô declara Comprasnet, PCP, BLL, BNC e Licitanet | Fabricante declara execução em nuvem, mesmo com computador desligado; mecanismo de conexão não integralmente divulgado |
| Licinexus / Soraia | Robô Comprasnet e alertas por assistente | Página específica distingue robô Comprasnet de monitoramento em outros portais | Modelo de notificações por WhatsApp, push, painel e email; arquitetura interna não comprovada |
| Licitah | Edital por IA, busca, documentos, prazos e robô | Robô anunciado para Compras.gov.br | Termos dizem executar ações em nome do usuário com sua conta; não comprovam API oficial ou desempenho independente |
| ContrataX | Busca PNCP, análise de aderência, documentos e referência de preços | Dados de oportunidades oriundos do PNCP | O próprio FAQ distingue o foco do produto de plataformas especializadas em robô; não tratá-lo como transmissor de lances |
| Quero Licitação — Copiloto de Pregão | Assistência e acompanhamento da sessão | Domínios Comprasnet/Compras.gov/Serpro indicados na extensão | Desenvolvedor descreve content script lendo a página e exibindo painel; não comprova envio automático de lances |
| LicitaAI, em licita.pro | PNCP, classificação por perfil/CNAE, análise e alertas | Descoberta e análise de oportunidades | Não encontrei base suficiente na página consultada para atribuir envio real de lances ao produto |

## Sistemas tradicionais e evolução para IA

- A história publicada pela Effecti registra início em 2013 com monitoramento de chat e evolução das etapas operacionais. A IA Aimê aparece como um módulo posterior de leitura de documentos.
- O ConLicitação informa fundação em 1999. Sua oferta atual adiciona IA às ferramentas de busca, gestão e robô.
- A WaveCode declara mais de 12 anos de mercado e hoje anuncia diagnóstico da disputa por IA.
- Não foi estabelecida a data de fundação de todos os demais produtos. Site moderno ou uso da palavra IA não comprova que a empresa seja nova, nem que tenha maior eficácia.

## O que se sabe sobre funcionamento

### Busca

PNCP e Compras.gov.br fornecem interfaces públicas de consulta. Fabricantes organizam esses dados em filtros, alertas e inteligência de mercado. Quantidade de portais cobertos na busca não equivale à quantidade de conectores de envio de propostas/lances.

### IA documental

Os produtos descrevem funções como resumir edital, encontrar cláusulas, responder perguntas, destacar riscos e comparar exigências com a empresa. O desenho recomendado para o LicitaIA é mostrar a passagem/página usada para cada conclusão e considerar anexos e retificações. Essa recomendação é nossa, não uma arquitetura interna confirmada de todos os concorrentes.

### Motor de disputa

Robôs recebem limites e estratégias do operador. Essas regras podem ser determinísticas: piso, desconto, intervalo, posição, fase e encerramento. A existência de robô não comprova uso de modelo generativo para decidir cada lance. A IA deve explicar e auxiliar; os valores finais precisam de controles explícitos.

### Conexão aos portais

- **API de parceiro:** a Effecti documenta chave BLL gerada no portal e configurada na integração. É um caminho prioritário para investigar autorização e documentação BLL para o LicitaIA.
- **Navegador:** a ajuda Effecti para Comprasnet declara uso de navegadores diferentes na automação. A extensão Quero Licitação descreve leitura da página. São evidências concretas de duas formas de usar o navegador, sem comprovar que suas implementações sejam iguais.
- **Nuvem:** a Licitei declara execução em seus servidores. Isso esclarece onde a execução ocorre, não se cada adaptador usa API, navegador ou outra técnica.
- **Automação do próprio portal:** normas federais preveem envio parametrizado pelo sistema. É necessário verificar disponibilidade e condições na disputa escolhida. Essa previsão não autoriza automaticamente todo robô externo.

## Onde há evidência mais forte de operação

Effecti tem documentação de suporte específica por portal e modalidade, inclusive limitações; isso é evidência mais útil de implementação do que somente uma página promocional. ConLicitação e Licitei publicam cobertura explícita de seus robôs. WaveCode publica fluxo operacional e casos/depoimentos.

Effecti declara mais de 3.000 empresas usuárias; ConLicitação declara mais de 20.000 empresas atendidas; WaveCode publica indicadores e depoimentos. Esses números não demonstram, sozinhos, estabilidade atual, taxa de vitórias, lucro líquido ou homologação. Não foi encontrado nesta pesquisa um teste independente comparável entre todos os produtos. Assim, a classificação é por qualidade da evidência e clareza do recurso, não por suposta taxa de sucesso.

## O que reproduzir no LicitaIA, com implementação própria

1. **Busca e seleção:** PNCP mais dados públicos Compras, favoritos, anexos/retificações, motivo da relevância e referências dos dados.
2. **Edital com evidências:** respostas da IA vinculadas à página/cláusula; checklist e avisos de incerteza; OCR para PDF escaneado como etapa separada.
3. **Proposta:** catálogo da empresa, preço por item/lote, custos, frete, tributos, margem, arquivos e checklist de declarações.
4. **Acompanhamento conectado:** extensão desktop para ler a sessão que o fornecedor abriu, identificar empresa/processo/item e gerar alertas; comunicação com Android por pareamento explícito, sem transferir senhas/cookies/tokens.
5. **Envio:** investigar primeiro o canal de parceiros BLL; para Compras, validar integração/automação assistida e condições do portal. Começar com revisão e confirmação no portal. Não anunciar envio real antes de receber confirmação verificável.
6. **Automação:** usar parametrização nativa quando disponível; avaliar conector externo somente com condições claras. Motor determinístico, piso inviolável, parada, auditoria e tratamento de estado incerto. Não contornar CAPTCHA, MFA, controles de acesso ou limites competitivos.

Uma extensão desktop não resolve sozinha uso exclusivo no celular. Para autonomia sem computador ligado, seria necessária infraestrutura de execução e sincronização, com custos e gestão de sessões. Para começar sem essa infraestrutura, usar computador para acompanhamento e Android para análise/gestão.

## Validação necessária

Validar um portal de cada vez. Em ambiente apropriado de treinamento/homologação, confirmar: isolamento de empresa, processo/item corretos, regra monetária, expiração de sessão, chat, suspensão/fechamento, recebimento do envio, ausência de duplicidade quando a rede cai e recuperação sem reenvio cego. Testes reais de concorrentes ou da nossa integração não foram realizados nesta pesquisa.

O projeto atual já implementa busca PNCP e modo assistido local. Falta conexão operacional validada; telas, interfaces e simulação não comprovam transmissão. Nenhum arquivo do aplicativo foi alterado durante esta pesquisa.

## Fontes

- [Effecti — produto](https://effecti.com.br/)
- [Effecti — histórico](https://effecti.com.br/sobre-nos/)
- [Effecti — central de ajuda](https://ajuda.effecti.com.br/)
- [Effecti — API BLL](https://ajuda.effecti.com.br/ajuda-2/como-gerar-chave-de-integracao-pelo-portal-bll/)
- [Effecti — acesso Comprasnet e declaração de automação por navegadores](https://ajuda.effecti.com.br/ajuda-2/disputar-como-adicionar-acesso-para-o-portal-comprasnet/)
- [Effecti — modalidades e limitações Comprasnet](https://ajuda.effecti.com.br/ajuda-2/portal-comprasnet/)
- [ConLicitação — IA](https://conlicitacao.com.br/ia/)
- [ConLicitação — robô e portais](https://conlicitacao.com.br/robo-de-lances/)
- [ConLicitação — histórico e descrição de operação](https://conlicitacao.com.br/robo-de-lances-para-licitacao-pode-usar-e-como-funciona/)
- [WaveCode — produto](https://www.wavecode.com.br/)
- [WaveCode — robô e diagnóstico IA](https://oferta.wavecode.com.br/robo-de-lances-licitacoes-g)
- [Lance Fácil — matriz por portal](https://www.lancefacil.com/portais-de-licitacao/)
- [Licitei — IA e plataforma](https://www.licitei.com.br/)
- [Licitei — execução em nuvem](https://www.licitei.com.br/robo-de-lances)
- [Licitei — cobertura operacional](https://www.licitei.com.br/portais-robo-de-disputa)
- [Licinexus — robô e alertas](https://licinexus.com.br/robo-de-lances)
- [Licitah — produto](https://www.licitah.com.br/)
- [Licitah — termos e descrição da execução](https://www.licitah.com.br/termos)
- [ContrataX — produto e FAQ](https://www.contratax.com.br/)
- [Quero Licitação — descrição da extensão](https://chromewebstore.google.com/detail/quero-licita%C3%A7%C3%A3o-%E2%80%94-copilot/lijjcneehbagflcmlppnplmomaeklhgf)
- [LicitaAI — produto](https://www.licita.pro/)
- [PNCP — documentação de integração](https://pncp.gov.br/manual/pt-br/latest/singlehtml/)
- [Compras — documentação de dados abertos](https://www.gov.br/compras/pt-br/cidadao/portal-de-dados-abertos/documentacao-interativa-da-api-de-dados-abertos)
- [TCU — automação prevista e envio de lances](https://licitacoesecontratos.tcu.gov.br/5-3-envio-de-lances/)
