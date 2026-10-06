package com.licitaia.connector.pncp

import com.licitaia.domain.model.Portal
import com.licitaia.domain.scoring.TextMatch

/**
 * Classificação da plataforma de origem de uma contratação do PNCP pelo campo `usuarioNome`
 * (sistema que publicou no PNCP) e, como apoio, pelo host de `linkSistemaOrigem`.
 *
 * Valores reais observados em `/v1/contratacoes/proposta` (06/10/2026, várias UFs): "Compras.gov.br",
 * "BLL Compras", "Licitanet Licitações Eletrônicas LTDA", "ECustomize Consultoria em Software S.A"
 * (empresa do Portal de Compras Públicas — seus registros apontam para portaldecompraspublicas.com.br),
 * "Licitar Digital - Plataforma de Licitações Online", "IPM Sistemas", "Bolsa Nacional De Compras - BNC",
 * "Novo BBMNET Licitações", "Licitações-E BB", "BR Conectado", "Fiorilli Software", "Betha Sistemas"...
 *
 * Regras (texto normalizado: minúsculo, sem acentos, pontuação vira espaço, casamento por palavra inteira):
 * - "compras gov", "comprasgov", "comprasnet" → [Portal.COMPRAS_GOV];
 * - "licitanet" → [Portal.LICITANET];
 * - "bll" ou "bolsa de licitacoes (e leiloes)" → [Portal.BLL] — "Bolsa Nacional de Compras" (BNC) NÃO é BLL;
 * - "portal de compras publicas", "portaldecompraspublicas" ou "ecustomize" → [Portal.PORTAL_COMPRAS_PUBLICAS];
 * - sem casamento pelo nome: host de linkSistemaOrigem (compras.gov.br, licitanet.com.br, bll.org.br,
 *   portaldecompraspublicas.com.br);
 * - demais (ou ausente) → [Portal.PNCP].
 */
object PncpPlatforms {

    /** Portais que a busca do PNCP consegue listar (após classificar `usuarioNome`). */
    val COVERED_PORTALS: Set<Portal> =
        setOf(Portal.PNCP, Portal.COMPRAS_GOV, Portal.LICITANET, Portal.BLL, Portal.PORTAL_COMPRAS_PUBLICAS)

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** Texto normalizado com palavras separadas por um espaço e bordas com espaço (para casar palavra inteira). */
    internal fun normalizeWords(text: String): String =
        " " + NON_ALNUM.replace(TextMatch.normalize(text), " ").trim() + " "

    fun classify(usuarioNome: String?, linkSistemaOrigem: String? = null): Portal {
        val raw = usuarioNome?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val t = normalizeWords(raw)
            fun has(phrase: String) = t.contains(" $phrase ")
            when {
                has("compras gov") || has("comprasgov") || has("comprasnet") -> return Portal.COMPRAS_GOV
                has("licitanet") -> return Portal.LICITANET
                has("bll") || has("bolsa de licitacoes") -> return Portal.BLL
                has("portal de compras publicas") || has("portaldecompraspublicas") || has("ecustomize") ->
                    return Portal.PORTAL_COMPRAS_PUBLICAS
            }
        }
        return classifyHost(linkSistemaOrigem)
    }

    private fun classifyHost(link: String?): Portal {
        val host = link?.trim()?.lowercase()?.substringAfter("://", "")?.substringBefore('/')?.substringBefore(':')
            ?.takeIf { it.isNotEmpty() } ?: return Portal.PNCP
        fun under(domain: String) = host == domain || host.endsWith(".$domain")
        return when {
            under("compras.gov.br") || under("comprasnet.gov.br") -> Portal.COMPRAS_GOV
            under("licitanet.com.br") -> Portal.LICITANET
            under("bll.org.br") || under("bllcompras.com") -> Portal.BLL
            under("portaldecompraspublicas.com.br") -> Portal.PORTAL_COMPRAS_PUBLICAS
            else -> Portal.PNCP
        }
    }

    /**
     * Nome exibível da plataforma: o nome do portal quando classificado (ex.: "Portal de Compras Públicas" para
     * ECustomize); senão o trecho antes de " - " de `usuarioNome` (ex.: "Licitar Digital"), até 40 caracteres.
     */
    fun displayName(usuarioNome: String?, classified: Portal = classify(usuarioNome)): String? {
        if (classified != Portal.PNCP) return classified.displayName
        val raw = usuarioNome?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        val head = raw.substringBefore(" - ").trim().takeIf { it.length >= 3 } ?: raw
        return if (head.length <= 40) head else head.take(39).trimEnd() + "…"
    }
}
