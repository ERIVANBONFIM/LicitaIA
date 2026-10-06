package com.licitaia.domain.model

/** Portais suportados. As URLs são apenas as páginas públicas institucionais. */
enum class Portal(
    val displayName: String,
    val shortName: String,
    val host: String,
    val publicUrl: String,
) {
    /** Portal Nacional de Contratações Públicas — única fonte com API pública documentada de consulta. */
    PNCP("PNCP", "PNCP", "pncp.gov.br", "https://pncp.gov.br/app/editais"),
    COMPRAS_GOV("Compras.gov.br", "Compras.gov", "www.gov.br", "https://www.gov.br/compras/pt-br"),
    BLL("BLL Compras", "BLL", "bll.org.br", "https://bll.org.br"),
    LICITANET("Licitanet", "Licitanet", "licitanet.com.br", "https://licitanet.com.br"),
    PORTAL_COMPRAS_PUBLICAS(
        "Portal de Compras Públicas", "PCP",
        "www.portaldecompraspublicas.com.br", "https://www.portaldecompraspublicas.com.br",
    ),
}

enum class Segment(val label: String) {
    TELECOM_ISP("Telecom / ISP"),
    TI("TI"),
    SOFTWARE("Software"),
    EQUIPAMENTOS("Equipamentos"),
    SERVICOS("Serviços"),
    PERSONALIZADO("Personalizado"),
}

enum class Modality(val label: String) {
    PREGAO_ELETRONICO("Pregão Eletrônico"),
    DISPENSA_ELETRONICA("Dispensa Eletrônica"),
    CONCORRENCIA("Concorrência"),
    CREDENCIAMENTO("Credenciamento"),
}

/** Como o usuário foi identificado. A autorização (empresas/perfil) é sempre local, independente do provedor. */
enum class AuthProvider(val label: String) {
    LOCAL("Conta local"),
    GOOGLE("Google"),
}

enum class UserRole(val label: String, val description: String) {
    ADMIN("Administrador", "Acesso total ao sistema"),
    DIRETORIA("Diretoria", "Aprova piso e envio, altera regras, visualiza tudo"),
    LICITACOES("Licitações", "Busca, analisa, prepara, responde e opera sessões"),
    FINANCEIRO("Financeiro", "Custo, margem, preço mínimo e aprovação financeira"),
    TECNICO("Técnico", "Viabilidade, SLA, prazo e cobertura"),
}

enum class TenderStatus(val label: String) {
    INTERESSE("Interesse"),
    EM_ANALISE("Em análise"),
    ANALISADA("Analisada"),
    PROPOSTA_EM_ELABORACAO("Proposta em elaboração"),
    AGUARDANDO_APROVACAO("Aguardando aprovação"),
    APROVADA("Aprovada"),
    PRONTA_PARA_ENVIO("Pronta para envio"),
    ENVIADA_SIMULADA("Enviada (simulação)"),
    EM_DISPUTA("Em disputa"),
    VENCIDA("Vencida"),
    PERDIDA("Perdida"),
    DESCARTADA("Descartada"),
}

enum class Recommendation(val label: String) {
    PARTICIPAR("PARTICIPAR"),
    AVALIAR("AVALIAR"),
    NAO_PARTICIPAR("NÃO PARTICIPAR"),
}

enum class RiskLevel(val label: String) {
    BAIXO("Baixo"), MEDIO("Médio"), ALTO("Alto"), CRITICO("Crítico"),
}

enum class DocumentType(val label: String) {
    CONTRATO_SOCIAL("Contrato Social"),
    CNPJ("CNPJ"),
    CERTIDAO_FEDERAL("Certidão Federal"),
    CERTIDAO_ESTADUAL("Certidão Estadual"),
    CERTIDAO_MUNICIPAL("Certidão Municipal"),
    FGTS("FGTS"),
    TRABALHISTA("Trabalhista (CNDT)"),
    BALANCO("Balanço Patrimonial"),
    SCM("Licença SCM (Anatel)"),
    CREA_CRT("CREA/CRT"),
    ATESTADO("Atestado de Capacidade Técnica"),
    DECLARACAO("Declaração"),
    PROCURACAO("Procuração"),
    CERTIFICADO("Certificado"),
    OUTROS("Outros"),
}

enum class DocumentStatus(val label: String) {
    VALIDO("Válido"),
    VENCE_EM_BREVE("Vence em breve"),
    VENCIDO("Vencido"),
    AUSENTE("Ausente"),
}

enum class ProposalStatus(val label: String) {
    RASCUNHO("Rascunho"),
    EM_REVISAO("Em revisão"),
    APROVADA("Aprovada"),
    REJEITADA("Rejeitada"),
    ENVIADA_SIMULADA("Enviada (simulação)"),
}

enum class PortalConnectionStatus(val label: String) {
    DESCONECTADO("Desconectado"),
    CONECTADO("Conectado"),
    SESSAO_EXPIRADA("Sessão expirada"),
    MFA_PENDENTE("MFA pendente"),
}

enum class LiveStatus(val label: String) {
    AGUARDANDO("Aguardando abertura"),
    EM_DISPUTA("Em disputa"),
    PAUSADA("Pausada"),
    CAPTCHA_PENDENTE("CAPTCHA pendente"),
    ERRO("Erro"),
    ENCERRADA("Encerrada"),
}

enum class RobotMode(val label: String, val description: String) {
    MANUAL("Manual", "O robô apenas sugere; todo lance é enviado pelo operador"),
    SUPERVISIONADO("Supervisionado", "O robô propõe cada lance e aguarda sua autorização"),
    AUTOMATICO_LIMITADO("Automático limitado", "O robô atua sozinho dentro do piso e pede autorização em condições críticas"),
}

enum class RobotStatus(val label: String) {
    INATIVO("Inativo"),
    ATIVO("Ativo"),
    PAUSADO("Pausado"),
    AGUARDANDO_AUTORIZACAO("Aguardando autorização"),
    BLOQUEADO_CAPTCHA("Bloqueado por CAPTCHA"),
    PARADO_NO_PISO("Parado no piso"),
    CONTROLE_MANUAL("Controle manual"),
    ERRO("Erro crítico"),
    ENCERRADO("Encerrado"),
}

enum class BidStrategy(val label: String, val description: String) {
    CONSERVADORA("Conservadora", "Reduções mínimas, só cobre quando perde a 1ª posição"),
    AGRESSIVA("Agressiva", "Reduções maiores para abrir distância do 2º colocado"),
    ACOMPANHAR_CONCORRENTE("Acompanhar concorrente", "Cobre o melhor lance pelo decremento mínimo"),
    PERSONALIZADA("Personalizada", "Usa exatamente a redução e o intervalo configurados"),
}

enum class BidEventType(val label: String) {
    SESSION_OPENED("Sessão aberta"),
    OUR_BID("Nosso lance"),
    COMPETITOR_BID("Lance concorrente"),
    BID_BLOCKED("Lance bloqueado"),
    POSITION_CHANGED("Mudança de posição"),
    ROBOT_STARTED("Robô ativado"),
    ROBOT_PAUSED("Robô pausado"),
    ROBOT_RESUMED("Robô retomado"),
    ROBOT_STOPPED("Robô encerrado"),
    MANUAL_TAKEOVER("Controle manual assumido"),
    CAPTCHA_DETECTED("CAPTCHA detectado"),
    CAPTCHA_RESOLVED("CAPTCHA resolvido"),
    AUTH_REQUESTED("Autorização solicitada"),
    AUTH_GRANTED("Autorização concedida"),
    AUTH_DENIED("Autorização negada"),
    FLOOR_REACHED("Piso atingido"),
    RULE_CHANGED("Regra alterada"),
    MESSAGE("Mensagem do pregoeiro"),
    ERROR("Erro"),
    SESSION_CLOSED("Sessão encerrada"),
}

enum class ReplyStatus(val label: String) {
    NENHUMA("Sem resposta"),
    RASCUNHO("Rascunho"),
    APROVADA("Aprovada"),
    ENVIADA_SIMULADA("Enviada (simulação)"),
}

/** Categorias = canais Android. `priority` menor = mais importante. */
enum class NotificationCategory(val label: String, val priority: Int) {
    CAPTCHA("CAPTCHA", 0),
    CRITICA("Críticas", 1),
    LANCES("Lances", 2),
    MENSAGENS("Mensagens", 3),
    DOCUMENTOS("Documentos", 4),
    SESSOES("Sessões", 5),
    RADAR("Radar", 6),
    GERAL("Geral", 7),
}

enum class AuditAction(val label: String) {
    LOGIN("Login"),
    LOGOUT("Logout"),
    TROCA_EMPRESA("Troca de empresa"),
    CONEXAO_PORTAL("Conexão de portal"),
    ANALISE("Análise"),
    GERACAO_DOCUMENTO("Geração de documento"),
    APROVACAO("Aprovação"),
    REJEICAO("Rejeição"),
    MUDANCA_PISO("Mudança de piso"),
    MUDANCA_REGRA("Mudança de regra"),
    ROBO_ATIVADO("Robô ativado"),
    ROBO_PAUSADO("Robô pausado"),
    ROBO_ENCERRADO("Robô encerrado"),
    CONTROLE_MANUAL("Controle manual"),
    LANCE("Lance"),
    CAPTCHA("CAPTCHA"),
    MENSAGEM("Mensagem"),
    ENVIO("Envio"),
    CONFIGURACAO("Configuração"),
    CADASTRO("Cadastro"),
    EMERGENCIA("Parada de emergência"),
    ERRO("Erro"),
}

enum class AuditOrigin(val label: String) {
    USUARIO("Usuário"), ROBO("Robô"), SISTEMA("Sistema"), IA("IA"),
}

enum class AuditResult(val label: String) {
    SUCESSO("Sucesso"), FALHA("Falha"), BLOQUEADO("Bloqueado"), PENDENTE("Pendente"),
}

/**
 * Como o app se autentica no provedor de IA.
 * - [API_KEY]: chave colada pelo usuário, cifrada no Keystore.
 * - [OAUTH]: "Entrar com conta" (hoje só Google/Gemini — token de acesso OAuth 2.0 com escopo
 *   `cloud-platform`, renovado silenciosamente; cota e cobrança no projeto Google Cloud do usuário).
 */
enum class AiAuthMode(val label: String) {
    API_KEY("Chave de API"),
    OAUTH("Conta Google"),
}

/**
 * @property supportsOAuth true só para provedores com OAuth público para apps de terceiros (Gemini).
 *   OpenAI ("Sign in with ChatGPT") está em beta restrito a parceiros e Anthropic só oferece chave de API.
 * @property apiKeyUrl página oficial onde o usuário gera a própria chave (abre no navegador).
 */
enum class AiProviderType(
    val label: String,
    val defaultModel: String,
    val defaultBaseUrl: String,
    val supportsOAuth: Boolean = false,
    val apiKeyUrl: String? = null,
) {
    MOCK("IA Demonstração (offline)", "mock-1", ""),
    OPENAI("OpenAI / ChatGPT", "gpt-6-luna", "https://api.openai.com/", apiKeyUrl = "https://platform.openai.com/api-keys"),
    ANTHROPIC("Anthropic / Claude", "claude-sonnet-5-5", "https://api.anthropic.com/", apiKeyUrl = "https://console.anthropic.com/settings/keys"),
    GEMINI(
        "Google Gemini", "gemini-3.8-flash", "https://generativelanguage.googleapis.com/",
        supportsOAuth = true, apiKeyUrl = "https://aistudio.google.com/apikey",
    ),
    CUSTOM("API personalizada", "", ""),
}
