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
    const val SIMULATOR = "simulator"
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

    fun radarEdit(radarId: Long = -1) = "radar/edit/$radarId"
    fun radarResults(radarId: Long) = "radar/results/$radarId"
    fun tender(tenderId: Long) = "tender/$tenderId"
    fun tenderAnalysis(tenderId: Long) = "tender/$tenderId/analysis"
    fun tenderWorth(tenderId: Long) = "tender/$tenderId/worth"
    fun tenderProposal(tenderId: Long) = "tender/$tenderId/proposal"
    fun proposalPdf(proposalId: Long) = "proposal/$proposalId/pdf"
    fun documentEdit(documentId: Long = -1) = "documents/edit/$documentId"
    fun liveSession(sessionId: String) = "live/$sessionId"
    /** Abre o formulário já vinculado a uma licitação de interesse (tenderId opcional). */
    fun liveNew(tenderId: Long? = null) = if (tenderId == null) LIVE_NEW else "$LIVE_NEW?tenderId=$tenderId"
    fun webView(sessionId: String) = "webview/$sessionId"
    fun portalWeb(portal: com.licitaia.domain.model.Portal) = "portal/${portal.name}"
    fun robotConfig(sessionId: String) = "robot/$sessionId"
    fun message(messageId: Long) = "messages/$messageId"

    /** Destinos de topo (abrem pelo menu lateral; mostram o ícone de menu em vez de "voltar"). */
    val topLevel = setOf(
        DASHBOARD, SEARCH, RADAR, INTERESTS, ANALYZE, PARTICIPATIONS, LIVE, WARROOM, STRATEGY,
        SIMULATOR, MESSAGES, COMPETITION, ROBOT, DOCUMENTS, PORTALS, AUDIT, COMPANIES, SETTINGS, SECURITY,
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
)

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> {
    error("AppNavigator não fornecido")
}

val LocalShellState = staticCompositionLocalOf { ShellState() }
