package com.licitaia.core.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf

/** Rotas de navegação de todo o app. Use os helpers para rotas com argumento. */
object Routes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val NOTIFICATIONS = "notifications"

    const val SEARCH = "search"
    const val RADAR = "radar"
    /** radarId = -1 → novo radar. */
    const val RADAR_EDIT = "radar/edit/{radarId}"
    const val RADAR_RESULTS = "radar/results/{radarId}"

    const val INTERESTS = "interests"
    const val ANALYZE = "analyze"
    const val PARTICIPATIONS = "participations"
    /** "Licitações arquivadas" (oportunidades e licitações de interesse arquivadas). */
    const val ARCHIVED = "archived"
    /** Cadastro manual de licitação (edital real obtido fora do app). Rota literal: vence o padrão tender/{tenderId}. */
    const val TENDER_NEW = "tender/new"
    const val TENDER = "tender/{tenderId}"
    const val TENDER_ANALYSIS = "tender/{tenderId}/analysis"
    const val TENDER_WORTH = "tender/{tenderId}/worth"
    const val TENDER_PROPOSAL = "tender/{tenderId}/proposal"
    const val PROPOSAL_PDF = "proposal/{proposalId}/pdf"

    const val DOCUMENTS = "documents"
    /** documentId = -1 → novo documento. */
    const val DOCUMENT_EDIT = "documents/edit/{documentId}"

    const val LIVE = "live"
    /** Formulário "Acompanhar pregão" (sessão assistida). Rota literal: vence o padrão live/{sessionId}. */
    const val LIVE_NEW = "live/new"
    const val LIVE_SESSION = "live/{sessionId}"
    const val WEBVIEW = "webview/{sessionId}"
    /** Navegador interno do portal (login manual, sessão por cookies). Argumento: nome do enum Portal. */
    const val PORTAL_WEB = "portal/{portal}"
    const val ROBOT = "robot"
    const val ROBOT_CONFIG = "robot/{sessionId}"
    const val STRATEGY = "strategy"
    /** Simulador removido (app 100% operacional): a rota antiga aponta para Estratégias. */
    @Deprecated("Simulador removido; use STRATEGY.", ReplaceWith("STRATEGY"))
    const val SIMULATOR = STRATEGY
    const val WARROOM = "warroom"

    const val MESSAGES = "messages"
    const val MESSAGE = "messages/{messageId}"
    const val COMPETITION = "competition"

    const val PORTALS = "portals"
    const val AUDIT = "audit"
    const val COMPANIES = "companies"
    const val SETTINGS = "settings"
    const val AI_SETTINGS = "settings/ai"
    const val SECURITY = "security"

    /** Plataforma LicitaPRO (modo online opcional): login e lista de licitações sincronizadas da VPS. */
    const val PLATFORM_LOGIN = "platform/login"
    const val PLATFORM_TENDERS = "platform/licitacoes"
    /** Detalhe de uma licitação da plataforma (`GET /licitacoes/:id`); id é UUID (String). */
    const val PLATFORM_TENDER = "platform/licitacao/{platformId}"
    /** "Pergunte ao edital" da plataforma (chat IA: `/ia/chat-edital/:id`). */
    const val PLATFORM_TENDER_QA = "platform/licitacao/{platformId}/perguntas"
    fun platformTenderQa(id: String) = "platform/licitacao/$id/perguntas"
    /** Empresas e Perfis da plataforma (`GET /empresas` + `GET /usuarios`). */
    const val PLATFORM_DIRECTORY = "platform/diretorio"
    /** Hub "Mais" do modo plataforma: acesso às seções lidas da VPS. */
    const val PLATFORM_HUB = "platform/mais"
    /** Documentos da empresa (`GET /documentos`). */
    const val PLATFORM_DOCS = "platform/documentos"
    /** Radares salvos (`GET /radar/filtros`). */
    const val PLATFORM_RADAR = "platform/radar"
    /** Concorrentes mapeados (`GET /concorrente`). */
    const val PLATFORM_COMPETITORS = "platform/concorrencia"
    /** Mensagens do pregoeiro (`GET /mensagens`). */
    const val PLATFORM_MESSAGES = "platform/mensagens"
    /** Auditoria da empresa (`GET /auditoria`). */
    const val PLATFORM_AUDIT = "platform/auditoria"
    /** Robôs de lance ativos (`GET /robo-lances/ativas`). */
    const val PLATFORM_ROBOT = "platform/robo"
    /** Pregões ao vivo — lista das disputas ativas (`GET /robo-lances/ativas`). */
    const val PLATFORM_LIVE = "platform/pregoes"
    /** Acompanhamento ao vivo de uma licitação (`GET /licitacoes/:id/ao-vivo`, polling). */
    const val PLATFORM_LIVE_TENDER = "platform/ao-vivo/{platformId}"
    fun platformLive(id: String) = "platform/ao-vivo/$id"
    /** Estado gracioso para funções ainda sem endpoint de nuvem (em modo plataforma). */
    const val PLATFORM_SOON = "platform/em-breve"

    fun platformTender(id: String) = "platform/licitacao/$id"

    fun radarEdit(radarId: Long = -1) = "radar/edit/$radarId"
    fun radarResults(radarId: Long) = "radar/results/$radarId"
    fun tender(tenderId: Long) = "tender/$tenderId"
    fun tenderAnalysis(tenderId: Long) = "tender/$tenderId/analysis"

    /** Abas da tela de análise do edital (argumento opcional `?tab=`). */
    const val TAB_ANALYSIS = "analise"
    const val TAB_QUESTIONS = "perguntas"
    const val TAB_ITEMS = "itens"

    /** Análise do edital abrindo direto na aba [tab] ([TAB_ANALYSIS], [TAB_QUESTIONS] ou [TAB_ITEMS]). */
    fun tenderAnalysis(tenderId: Long, tab: String) = "tender/$tenderId/analysis?tab=$tab"
    /** "Pergunte ao edital". */
    fun tenderQuestions(tenderId: Long) = tenderAnalysis(tenderId, TAB_QUESTIONS)
    /** "Ver itens". */
    fun tenderItems(tenderId: Long) = tenderAnalysis(tenderId, TAB_ITEMS)
    fun tenderWorth(tenderId: Long) = "tender/$tenderId/worth"
    fun tenderProposal(tenderId: Long) = "tender/$tenderId/proposal"
    fun proposalPdf(proposalId: Long) = "proposal/$proposalId/pdf"
    fun documentEdit(documentId: Long = -1) = "documents/edit/$documentId"
    fun liveSession(sessionId: String) = "live/$sessionId"
    /** Abre o formulário já vinculado a uma licitação de interesse (tenderId opcional). */
    fun liveNew(tenderId: Long? = null) = if (tenderId == null) LIVE_NEW else "$LIVE_NEW?tenderId=$tenderId"
    fun webView(sessionId: String) = "webview/$sessionId"
    fun portalWeb(portal: com.licitaia.domain.model.Portal) = "portal/${portal.name}"

    /**
     * Navegador interno do portal levando até a compra: [url] (página oficial da compra, portais que abrem por link) ou,
     * no Compras.gov.br, [uasg] + [numero] ("90012/2026") + [modalidade] para o app pesquisar e abrir "Acompanhar compra".
     */
    fun portalWebTarget(
        portal: com.licitaia.domain.model.Portal,
        url: String? = null,
        uasg: String? = null,
        numero: String? = null,
        modalidade: String? = null,
    ): String {
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        val params = listOfNotNull(
            url?.let { "url=${enc(it)}" }, uasg?.let { "uasg=${enc(it)}" },
            numero?.let { "numero=${enc(it)}" }, modalidade?.let { "modalidade=${enc(it)}" },
        )
        return if (params.isEmpty()) portalWeb(portal) else "portal/${portal.name}?" + params.joinToString("&")
    }

    /** Padrão registrado no grafo para [portalWebTarget] (argumentos opcionais). */
    const val PORTAL_WEB_TARGET = "portal/{portal}?url={url}&uasg={uasg}&numero={numero}&modalidade={modalidade}"

    const val TENDER_COMPETITORS = "tender/{tenderId}/competitors"
    fun tenderCompetitors(tenderId: Long) = "tender/$tenderId/competitors"
    fun robotConfig(sessionId: String) = "robot/$sessionId"
    fun message(messageId: Long) = "messages/$messageId"

    /** Destinos de topo (abrem pelo menu lateral; mostram o ícone de menu em vez de "voltar"). */
    val topLevel = setOf(
        DASHBOARD, SEARCH, RADAR, INTERESTS, ANALYZE, PARTICIPATIONS, ARCHIVED, LIVE, WARROOM, STRATEGY,
        MESSAGES, COMPETITION, ROBOT, DOCUMENTS, PORTALS, AUDIT, COMPANIES, SETTINGS, SECURITY,
    )
}

/** Navegação exposta às features — implementada no módulo app. */
interface AppNavigator {
    fun navigate(route: String)
    /** Navega para um destino de topo limpando a pilha até o dashboard. */
    fun navigateTop(route: String)
    fun back()
    fun openDrawer()
    /** Pós-login: vai ao dashboard removendo o login da pilha. */
    fun onLoggedIn()
    fun showMessage(message: String)
}

/** Estado global exibido na barra superior de todas as telas. */
data class ShellState(
    val companyName: String = "",
    val userName: String = "",
    val unreadNotifications: Int = 0,
    /** true = há CAPTCHA/alerta crítico pendente → sino vermelho pulsante. */
    val criticalPending: Boolean = false,
    /** true = sessão no espaço de demonstração isolado → selo "DEMONSTRAÇÃO" persistente no topo. */
    val demo: Boolean = false,
)

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> {
    error("AppNavigator não fornecido")
}

val LocalShellState = staticCompositionLocalOf { ShellState() }
