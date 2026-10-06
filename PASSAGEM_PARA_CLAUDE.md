# Passagem de trabalho ao Claude Code — 6 de outubro de 2026

O usuário decidiu manter o Claude como único executor da implementação. O Codex interrompeu as edições de código e seus subagentes. Este arquivo é um resumo para revisão, não uma declaração de entrega concluída. Não descarte alterações existentes sem revisar.

## Estado e validação

- O projeto estava sem `.git` ao iniciar. Não foi possível produzir diff confiável contra a versão anterior. Há processos Claude abertos; preserve alterações concorrentes. Leia SDD_LicitaIA.md, PROMPT_MESTRE_CLAUDE.md e AVALIACAO_PRONTIDAO.md.
- Uma compilação intermediária `:app:assembleDebug` passou e gerou `app/build/outputs/apk/debug/app-debug.apk`. Houve alterações depois dessa compilação: o APK não representa integralmente o código atual. Não foi instalado pelo Codex.
- Testes executados após os ajustes: core-domain 46, core-security 5, core-data 5 e feature-auth 19, total 75, sem falhas. Evidência: `tests-personal.log` e XMLs de `build/test-results` nos respectivos módulos.
- Os testes falharam inicialmente com ClassNotFoundException no caminho com acento. Funcionaram criando `subst L:` e entrando em `L:\` dentro do MESMO processo PowerShell antes de chamar Gradle, usando cache existente e propriedades citadas: `'-Pkotlin.incremental=false' '-Pksp.incremental=false'`. O mapeamento é por sessão de logon; chamar outro processo pode não enxergá-lo. Sem instalação de software.
- Migração foi testada por chamadas SQL simuladas; NÃO houve teste instrumental com banco legado real. Backup/restauração não foi exercitado no aparelho. Login Google e portais também precisam smoke test manual.
- `adb devices` falhou no ambiente isolado tentando criar `\\.android`; isso NÃO comprova ausência física do aparelho. O scrcpy apareceu aberto ao inspecionar janelas depois. Não desinstale/limpe o app para contornar assinatura.

## Alterações efetivamente salvas

### Identidade, demo e autorização

- DatabaseSeeder virou no-op; conta demo legada é recusada no login e restauração. Botão demo removido de LoginScreen; registros legados não foram apagados.
- AuthRepositoryImpl, CompanyRepositoryImpl, AuditRepositoryImpl, CompanyValidation e feature-auth alterados. GoogleTokenVerifier verifica RS256 com JWKS HTTPS oficial Google, falha sem verificação online, além dos checks de issuer/audience/nonce/expiração. Testes verificam assinatura, adulteração e chave desconhecida.
- Vínculo automático Google a conta local pelo mesmo e-mail foi bloqueado. Não foi implementado fluxo completo de vínculo com senha local; documente a limitação.
- Novo contrato AuthRepository.createGoogleCompany e formulário para usuário Google pendente criar empresa e virar ADMIN dela. Criação Google transacional; CNPJ validado por dígitos. CompanyRepository restringe acesso aos vínculos, exige ADMIN nas alterações e bloqueia edição cruzada. Modelo ainda possui papel global por usuário; vínculos multiempresa de usuários existentes são limitados para evitar elevação cruzada.
- RepositoryAccess verifica empresa ativa, vínculo e não-demo, com RBAC opcional. Foi aplicado em SimpleRepositories, ProposalRepositoryImpl e PortalAndMessageRepositories. Há consultas getById novas em NotificationDao e LiveSessionDao.
- Configuração de IA recebeu checks CONFIGURAR_IA no repositório. UI esconde provedor MOCK e deixou de prometer fallback de demonstração. Verifique todos os caminhos de IA, pois os provedores e TenderRepository anteriores ainda têm fallback mock.

### Portais e sessões

- PortalsScreen abre URLs oficiais do PNCP público, Compras.gov.br/Comprasnet e BLL. PNC foi interpretado como provável PNCP. Login/senha, CAPTCHA e MFA são digitados/resolvidos pelo usuário no portal, sem coleta pelo app.
- WebViewScreen foi substituída por acesso assistido via Custom Tabs. Não há confirmação fictícia de login no repositório. Navegador externo compartilha a sessão do navegador: NÃO alegue isolamento de contas por empresa nesse navegador.
- Motor LiveSessionManagerImpl: seed/stream de sessões fictícias retirados, robô/lances bloqueados, encerramento de runtimes ao trocar empresa/logout, alterações de regras/piso com RBAC. Botões de criação demo retirados das telas live/warroom. Envio simulado de proposta/mensagem passa a informar indisponibilidade em vez de produzir protocolo fictício.
- Sessões assistidas e suas telas precisam revisão funcional. Não há integração autenticada oficial para enviar lances/propostas; não anunciar robô integrado.

### Preservação, backup, assinatura e alertas

- LicitaDatabase continua versão 2, agora exportSchema=true; schema exportado em `core-data/schemas/.../2.json`. DataModule remove fallbackToDestructiveMigration e registra DatabaseMigrations.FROM_1_TO_2, adicionando apenas colunas de identidade ausentes e marcando e-mails demo conhecidos. Confirme compatibilidade real da versão 1, pois não havia schema v1 exportado.
- PortableBackupCipher: PBKDF2-HMAC-SHA256, 210 mil iterações, salt aleatório, AES-256-GCM com cabeçalho autenticado; senha portátil mínimo 12 caracteres. Testes cobrem roundtrip, aleatoriedade, senha errada e adulteração.
- CompanyBackupRepository e contrato BackupRepository: exportação cifrada SOMENTE da empresa ativa por ADMIN, incluindo radares, licitações, análises, documentos/anexos, propostas/PDFs, concorrência e auditoria. Sem identidades, senhas, chaves de IA ou sessões externas. Limite 64 MB.
- Restauração adiciona registros, exige mesmo CNPJ, remapeia IDs e referências, mantém dados atuais, retorna propostas a rascunho e grava anexos privados. Confirmar FileProvider e preservar extensão/MIME dos anexos restaurados: o nome aleatório atual pode perder o tipo. Repetir restauração pode duplicar dados. Backup contém estrutura para editais em `filesDir/editais/{companyId}/{tenderId}.pdf/.txt`, mas a importação de editais NÃO foi implementada.
- BackupCard foi adicionado a SettingsScreen. Revisar no aparelho falha de senha, URI sem acesso, restore transacional e registros existentes.
- Versão mudou para versionCode 3 / 0.2.0-personal. SigningConfig release lê propriedades/variáveis LICITAIA_RELEASE_STORE_FILE, STORE_PASSWORD, KEY_ALIAS e KEY_PASSWORD (prefixo LICITAIA_RELEASE_ em todas). Sem keystore fornecido, release permanece unsigned. Não foi gerado keystore nem APK release. Debug e release têm applicationIds distintos; migração exige backup, não desinstalação.
- PersonalAlertsWorker agenda radares/documentos a cada 6 horas com rede, sessão lembrada, checks de empresa e deduplicação. ATENÇÃO: OpportunityRepositoryImpl ainda usa mocks; NÃO entregar esse Worker ativo antes de substituir a busca por PNCP real. WorkManager é best-effort, não serviço de pregão/cronômetro contínuo. Documentos também aguardam rede nessa implementação.
- .gitignore ampliado para excluir APKs, backups, logs, keystores, .env e signing properties. Revise antes de versionar; isso não remove arquivos já rastreados.

## Trabalho ainda necessário antes de uma entrega para uso real

1. Implementar busca PNCP pública documentada, paginação, limites, erros/offline e testes. OpportunityRepositoryImpl e TenderRepositoryImpl AINDA mantêm caminhos mock anteriores. O subagente dessa frente foi interrompido sem salvar a implementação.
2. Cadastro manual de licitação e importação PDF real, com extração de texto/OCR ou alternativa explícita para PDF escaneado. Análise deve receber texto real e não template fictício. Bloquear fallback silencioso; rotular heurísticas e não fabricar documentos exigidos, riscos ou preços.
3. Concluir revisão de isolamento/RBAC em TODOS os repositórios e observers, incluindo troca de empresa durante operação assíncrona, sessões legadas, IA e banco com mistura antiga demo/real. Credenciais IA ainda são por aparelho, não por empresa.
4. Validar migrations em SQLite/Room real e backup/restauração de dados/anexos reais controlados. Não apagar registros reais nem limpar banco em falha.
5. Atualizar README, SECURITY, TESTING, ROADMAP e CHANGELOG: ainda descrevem demo/fallback antigo. Não usar textos intermediários da UI como prova de PNCP/importação funcionando.
6. Compilar o código final, testes pertinentes, lint, release R8/assinatura externa e smoke test no Xiaomi Android 15. APK intermediário NÃO é entrega final.

## Limites de autorização

Destino de código informado: https://github.com/ERIVANBONFIM/LicitaIA.git . Visibilidade não verificada pelo Codex. Nenhum push/publicação foi realizado por ele. Conferir estado atual, pois o usuário pode ter feito algo separadamente.

Sem autorização para enviar propostas/lances reais, publicar na loja, cadastrar serviços externos, gerar cobranças ou adicionar backend central automaticamente. Sem coleta de senha gov.br, páginas falsas, bypass de CAPTCHA/MFA ou APIs privadas. Preservar dados, credenciais e alterações atuais; não imprimir segredos.

Fontes da frente de portais: https://pncp.gov.br/app/editais , https://www.gov.br/compras/pt-br/login e https://bllcompras.com/home/login . Documentação PNCP a consultar: https://pncp.gov.br/api/consulta/v3/api-docs .

## Encaminhamento

Mensagem sugerida para o chat do Claude Code:

> O Codex parou de editar e você será o único executor. Leia PASSAGEM_PARA_CLAUDE.md na raiz do projeto antes de continuar. Revise e preserve as alterações existentes. Há 75 testes passando, mas o APK é intermediário e PNCP/importação de edital real ainda não estão implementados. Conclua o escopo autorizado, valide migrations e backup no aparelho, faça a compilação final e relate limitações honestamente, sem publicar nem executar ações vinculantes externas sem autorização.
