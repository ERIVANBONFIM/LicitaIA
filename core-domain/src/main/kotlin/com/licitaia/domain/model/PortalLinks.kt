package com.licitaia.domain.model

import java.net.URI

/**
 * Links "Abrir no portal" / "Ver no PNCP" de uma licitação. Puro e testável.
 * - Compras.gov.br: navegador interno do app (Comprasnet) + pesquisa da compra por UASG e número ([ComprasTarget]).
 * - BLL, Licitanet, Portal de Compras Públicas: a URL oficial da compra (`linkSistemaOrigem` do PNCP) no navegador
 *   interno quando o host é do próprio portal; senão no navegador externo.
 * - Sempre: a página pública da contratação no PNCP.
 */
object PortalLinks {

    /** Compra do Compras.gov.br a pesquisar em "Todas as compras" (UASG + número/ano) e abrir em "Acompanhar compra". */
    data class ComprasTarget(val uasg: String, val number: String, val year: Int, val modality: String) {
        /** "90012/2026" como o portal mostra. */
        val numberYear: String get() = "$number/$year"
    }

    sealed interface Target {
        /** Navegador interno do app no [portal], levando à [url] (null = página inicial) ou pesquisando [compras]. */
        data class Internal(val portal: Portal, val url: String? = null, val compras: ComprasTarget? = null) : Target

        /** Navegador externo (Custom Tab). */
        data class External(val url: String) : Target
    }

    /** Domínios de cada portal aceitos pelo navegador interno (mesma allowlist do app). */
    private val PORTAL_DOMAINS: Map<Portal, List<String>> = mapOf(
        Portal.PNCP to listOf("pncp.gov.br"),
        Portal.COMPRAS_GOV to listOf("gov.br"),
        Portal.BLL to listOf("bll.org.br", "bllcompras.com"),
        Portal.LICITANET to listOf("licitanet.com.br"),
        Portal.PORTAL_COMPRAS_PUBLICAS to listOf("portaldecompraspublicas.com.br"),
    )

    /** Página pública da contratação no PNCP: `https://pncp.gov.br/app/editais/{cnpj}/{ano}/{sequencial}`. */
    fun pncpPageUrl(opportunityId: String): String? {
        val raw = PncpControlNumbers.fromOpportunityId(opportunityId) ?: return null
        val m = Regex("""^(\d{14})-1-(\d{1,6})/(\d{4})$""").matchEntire(raw) ?: return null
        val seq = m.groupValues[2].trimStart('0').ifEmpty { "0" }
        return "https://pncp.gov.br/app/editais/${m.groupValues[1]}/${m.groupValues[3]}/$seq"
    }

    fun host(url: String?): String? = runCatching { URI(url!!.trim()).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** A URL é HTTPS e o host pertence ao [portal] (o próprio domínio ou subdomínio). */
    fun belongsTo(portal: Portal, url: String?): Boolean {
        val u = url?.trim() ?: return false
        if (!u.startsWith("https://", ignoreCase = true)) return false
        val h = host(u) ?: return false
        return PORTAL_DOMAINS[portal].orEmpty().any { d -> h == d || h.endsWith(".$d") }
    }

    /** "90012/2026" → ("90012", 2026); null sem ano de 4 dígitos. */
    fun splitNumber(number: String): Pair<String, Int>? {
        val m = Regex("""(\d{1,6})\s*/\s*(\d{4})""").find(number) ?: return null
        val n = m.groupValues[1].trimStart('0').ifEmpty { "0" }
        return n to m.groupValues[2].toInt()
    }

    /** Alvo de pesquisa no Comprasnet; null quando falta UASG ou número/ano. */
    fun comprasTarget(portal: Portal, uasg: String?, number: String, modality: Modality): ComprasTarget? {
        if (portal != Portal.COMPRAS_GOV) return null
        val u = uasg?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() && it.length <= 6 } ?: return null
        val (n, y) = splitNumber(number) ?: return null
        return ComprasTarget(u.padStart(6, '0'), n, y, modality.label)
    }

    /**
     * Para onde vai o "Abrir no portal": Compras.gov.br sempre no navegador interno (com pesquisa da compra quando há
     * UASG/número); demais portais suportados pela URL oficial da compra ([originUrl]) no navegador interno quando o host é
     * deles, senão no externo; sem link, a página inicial do portal (interno). PNCP/outros: a página do PNCP.
     */
    fun openTarget(portal: Portal, originUrl: String?, compras: ComprasTarget?, pncpUrl: String?): Target? {
        val origin = originUrl?.trim()?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
        return when (portal) {
            Portal.COMPRAS_GOV -> Target.Internal(Portal.COMPRAS_GOV, url = null, compras = compras)
            Portal.BLL, Portal.LICITANET, Portal.PORTAL_COMPRAS_PUBLICAS -> when {
                origin != null && belongsTo(portal, origin) -> Target.Internal(portal, url = origin)
                origin != null -> Target.External(origin)
                else -> Target.Internal(portal)
            }
            Portal.PNCP -> origin?.let { Target.External(it) } ?: pncpUrl?.let { Target.External(it) }
        }
    }

    /** Texto do botão: "Abrir no Compras.gov.br · UASG 123456 · nº 90012/2026". */
    fun buttonLabel(portal: Portal, uasg: String?, number: String): String = buildString {
        append(if (portal == Portal.PNCP) "Abrir no sistema de origem" else "Abrir no ${portal.displayName}")
        if (portal == Portal.COMPRAS_GOV) {
            uasg?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }?.let { append(" · UASG ").append(it.padStart(6, '0')) }
            append(" · nº ").append(number)
        }
    }
}
