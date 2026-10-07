package com.licitaia.feature.live.automation

import com.licitaia.domain.portal.PortalItemReading
import com.licitaia.domain.portal.PortalItemState
import com.licitaia.domain.portal.PortalProposalReading
import com.licitaia.domain.portal.ProposalItemPlan
import com.licitaia.domain.util.Formatters
import java.math.BigDecimal

/*
 * LEITURA da situação da proposta no cadastro REAL do Compras.gov.br (Pregão 48/2026 UASG 160192), antes de qualquer
 * preenchimento: cada grupo é aberto (chevron v → ^), as páginas internas do grupo ("«  ‹  [1]  2  3  ›  »", 10 itens por
 * página) são percorridas e cada cartão de item vira lançado ("Meu valor (unitário)" + valor) ou não cadastrado
 * ("Proposta não cadastrada"). O robô processa só os não cadastrados selecionados; os já lançados são pulados.
 */

/** "R$ 209,25" com espaço comum (sem o espaço inseparável do formatador). */
internal fun brlText(v: Double?): String = Formatters.brl(v).replace(' ', ' ')

/**
 * Cartão de ITEM dentro do grupo (REAL). Lançado: "1 ACESSO A INTERNET VIA… < apelido > Quantidade solicitada 12
 * Unidade fornecimento Valor estimado (unitário) Meu valor (unitário) Meu valor (total) R$ 238,3300 R$ 209,2500
 * R$ 2.511,0000". Não lançado: "11 ACESSO A INTERNET VI… Quantidade solicitada 12 Unidade fornecimento Valor estimado
 * (unitário) R$ 238,3300 Proposta não cadastrada". Os rótulos vêm na ordem dos valores.
 */
data class PortalItemCard(
    val number: Int,
    val quantity: Double?,
    val estimatedUnit: BigDecimal?,
    val myUnit: BigDecimal?,
    val myTotal: BigDecimal?,
    val notRegistered: Boolean,
) {
    val state: PortalItemState
        get() = when {
            myUnit != null -> PortalItemState.LANCADO
            notRegistered -> PortalItemState.NAO_CADASTRADO
            else -> PortalItemState.DESCONHECIDO
        }

    companion object {
        private val money = Regex("""R\$\s*(\d[\d.]*,\d{2,4})""")
        private val qty = Regex("""(?i)Quantidade\s+solicitada\s*:?\s*(\d[\d.]*(?:,\d+)?)""")
        private val label = Regex("""valor estimado(?: \((unitario|total)\))?|meu valor(?: \((unitario|total)\))?""")

        fun parse(text: String): PortalItemCard? {
            val t = text.replace('|', ' ').replace(Regex("\\s+"), " ").trim()
            if (GroupCard.parse(t) != null) return null
            val n = ItemNumber.of(t) ?: return null
            val norm = TextNorm.norm(t)
            // Rótulos na ordem em que aparecem; os valores R$ seguem a mesma ordem.
            val labels = label.findAll(norm).map { m ->
                when {
                    m.value.startsWith("valor estimado") -> "est"
                    m.value == "meu valor (total)" -> "myTotal"
                    else -> "myUnit"
                }
            }.toList()
            val values = money.findAll(t).map { ProposalMoney.parse(it.groupValues[1]) }.toList()
            fun valueOf(key: String) = labels.indexOf(key).takeIf { it >= 0 }?.let { values.getOrNull(it) }
            return PortalItemCard(
                number = n,
                quantity = qty.find(t)?.groupValues?.get(1)?.replace(".", "")?.replace(',', '.')?.toDoubleOrNull(),
                estimatedUnit = valueOf("est"),
                myUnit = valueOf("myUnit"),
                myTotal = valueOf("myTotal"),
                notRegistered = norm.contains("proposta nao cadastrada"),
            )
        }
    }
}

/** Um grupo lido: cartão do grupo + os textos dos cartões de item de CADA página interna. */
data class GroupRead(val key: String, val card: GroupCard?, val pages: List<List<String>>, val warning: String? = null)

object ProposalReadingBuilder {
    /** Junta grupos (todas as páginas) e itens soltos (compra sem grupos) numa leitura com data/hora. */
    fun build(readAt: Long, groups: List<GroupRead>, flatItems: List<String> = emptyList()): PortalProposalReading {
        val out = LinkedHashMap<Int, PortalItemReading>()
        fun add(text: String, g: GroupRead?) {
            val c = PortalItemCard.parse(text) ?: return
            val r = PortalItemReading(
                c.number, c.state, c.myUnit?.toDouble(), g?.key ?: g?.card?.key, g?.card?.meEppExclusive ?: false,
            )
            val old = out[c.number]
            if (old == null || (old.state == PortalItemState.DESCONHECIDO && r.state != PortalItemState.DESCONHECIDO)) out[c.number] = r
        }
        groups.forEach { g -> g.pages.flatten().forEach { add(it, g) } }
        flatItems.forEach { add(it, null) }
        return PortalProposalReading(readAt, out.values.sortedBy { it.itemNumber })
    }

    /** "Grupo 1 (24 itens, 3 páginas): 10 lançados · 14 não cadastrados". */
    fun groupLine(g: GroupRead, reading: PortalProposalReading): String {
        val mine = reading.items.filter { it.group == g.key }
        val lanc = mine.count { it.state == PortalItemState.LANCADO }
        val nao = mine.count { it.state == PortalItemState.NAO_CADASTRADO }
        val unk = mine.count { it.state == PortalItemState.DESCONHECIDO }
        val head = "${keyLabel(g.key)} (${g.card?.itemCount ?: mine.size} itens, ${g.pages.size} página(s)" +
            (if (g.card?.meEppExclusive == true) ", exclusivo ME/EPP" else "") + ")"
        return "$head: $lanc lançado(s) · $nao não cadastrado(s)" + (if (unk > 0) " · $unk sem leitura" else "") +
            (g.warning?.let { " · aviso: $it" } ?: "")
    }

    fun keyLabel(key: String): String = key.lowercase().replaceFirstChar { it.uppercase() }
}

/** O que fazer com UM item selecionado do plano, dada a leitura do portal. */
sealed interface ItemAction {
    /** "Proposta não cadastrada" (ou sem leitura): preencher e salvar. */
    data object Fill : ItemAction
    /** Já lançado com valor diferente e o usuário pediu para atualizar. */
    data class Update(val portal: Double, val plan: Double) : ItemAction
    /** Já lançado com o mesmo valor: pular. */
    data class SkipAlready(val portal: Double) : ItemAction
    /** Já lançado com valor diferente: NÃO sobrescreve (padrão). */
    data class SkipDifferent(val portal: Double, val plan: Double) : ItemAction
}

object ProposalItemDecision {
    fun samePrice(plan: Double, portal: Double): Boolean =
        ProposalMoney.matches(plan, BigDecimal(portal.toString()).toPlainString().replace('.', ','))

    fun decide(item: ProposalItemPlan, r: PortalItemReading?, updateDifferent: Boolean): ItemAction {
        val portal = r?.portalUnitPrice
        if (r == null || r.state != PortalItemState.LANCADO || portal == null) return ItemAction.Fill
        if (samePrice(item.unitPrice, portal)) return ItemAction.SkipAlready(portal)
        return if (updateDifferent) ItemAction.Update(portal, item.unitPrice) else ItemAction.SkipDifferent(portal, item.unitPrice)
    }

    /** Texto do relatório para o item [n]. */
    fun describe(n: Int, a: ItemAction): String = when (a) {
        ItemAction.Fill -> "Item $n: não cadastrado — será preenchido"
        is ItemAction.SkipAlready -> "Item $n: já lançado: ${brlText(a.portal)}"
        is ItemAction.SkipDifferent -> "Item $n: lançado com valor diferente (portal ${brlText(a.portal)} × plano ${brlText(a.plan)}) — não sobrescrito"
        is ItemAction.Update -> "Item $n: lançado com valor diferente (portal ${brlText(a.portal)} × plano ${brlText(a.plan)}) — será atualizado"
    }
}

/** Grupo "Exclusividade ME/EPP" com a empresa declarando "Não" para ME/EPP: não pode participar. */
object GroupEligibility {
    fun skipReason(key: String, card: GroupCard?, meEpp: Boolean?): String? =
        if (card?.meEppExclusive == true && meEpp == false) {
            "${ProposalReadingBuilder.keyLabel(key)} é “Exclusividade ME/EPP” e a declaração ME/EPP da empresa é “Não”: a empresa não pode participar — grupo pulado"
        } else {
            null
        }

    /** Pela leitura guardada (sem o cartão do grupo). */
    fun blocked(r: PortalItemReading?, meEpp: Boolean?): Boolean = r?.meEppExclusive == true && meEpp == false
}

/** Contagem final por grupo: "Grupo 1: 10 já lançados · 14 cadastrados agora · 0 faltando". */
data class GroupTally(
    val key: String,
    val already: Int,
    val savedNow: Int,
    val missing: Int,
    val different: Int = 0,
    val skipped: String? = null,
) {
    fun line(): String {
        val k = ProposalReadingBuilder.keyLabel(key)
        if (skipped != null) return "$k: pulado — $skipped"
        return "$k: $already já lançados · $savedNow cadastrados agora · $missing faltando" +
            if (different > 0) " · $different com valor diferente (não sobrescritos)" else ""
    }

    companion object {
        /**
         * [portalItems] = números dos itens do grupo vistos na leitura; [alreadyBefore] = lançados na leitura (antes);
         * [savedNow] = salvos nesta execução; [different] = lançados com valor diferente e não sobrescritos.
         */
        fun of(key: String, itemCount: Int?, portalItems: Set<Int>, alreadyBefore: Set<Int>, savedNow: Set<Int>, different: Set<Int>): GroupTally {
            val total = maxOf(itemCount ?: 0, portalItems.size)
            val now = savedNow.count { it in portalItems || portalItems.isEmpty() }
            val already = alreadyBefore.count { it !in savedNow }
            return GroupTally(key, already, now, (total - already - now).coerceAtLeast(0), different.size)
        }
    }
}

/** Marca de cada item na lista do plano/confirmação depois da leitura. */
data class PortalMarker(val symbol: String, val text: String, val kind: Kind) {
    enum class Kind { LANCADO, DIFERENTE, NAO_CADASTRADO, AUSENTE, BLOQUEADO }
    val label: String get() = "$symbol $text"
}

object ProposalReadingRules {
    fun marker(item: ProposalItemPlan, reading: PortalProposalReading?, meEpp: Boolean?): PortalMarker? {
        reading ?: return null
        val r = reading.of(item.itemNumber) ?: return PortalMarker("?", "não apareceu no portal", PortalMarker.Kind.AUSENTE)
        if (GroupEligibility.blocked(r, meEpp)) return PortalMarker("⛔", "${r.group ?: "grupo"} exclusivo ME/EPP (empresa declarou Não)", PortalMarker.Kind.BLOQUEADO)
        return when (r.state) {
            PortalItemState.LANCADO -> {
                val p = r.portalUnitPrice ?: return PortalMarker("✓", "lançado", PortalMarker.Kind.LANCADO)
                if (ProposalItemDecision.samePrice(item.unitPrice, p)) PortalMarker("✓", "lançado (${brlText(p)})", PortalMarker.Kind.LANCADO)
                else PortalMarker("⚠", "diferente (portal ${brlText(p)} × plano ${brlText(item.unitPrice)})", PortalMarker.Kind.DIFERENTE)
            }
            PortalItemState.NAO_CADASTRADO -> PortalMarker("○", "não cadastrado", PortalMarker.Kind.NAO_CADASTRADO)
            PortalItemState.DESCONHECIDO -> PortalMarker("?", "sem leitura", PortalMarker.Kind.AUSENTE)
        }
    }

    /**
     * Depois da leitura: seleciona SÓ os itens "Proposta não cadastrada" com preço (e, com [updateDifferent], os
     * lançados com valor diferente). Itens já lançados, ausentes ou de grupo exclusivo ME/EPP vedado ficam desmarcados.
     */
    fun preselect(items: List<ProposalItemPlan>, reading: PortalProposalReading, meEpp: Boolean?, updateDifferent: Boolean = false): List<ProposalItemPlan> =
        items.map { i ->
            val kind = marker(i, reading, meEpp)?.kind
            val sel = i.hasPrice && (kind == PortalMarker.Kind.NAO_CADASTRADO || (updateDifferent && kind == PortalMarker.Kind.DIFERENTE))
            i.copy(selected = sel)
        }

    /** "Leitura de 07/10/2026 14:32: 10 lançados · 1 diferente · 14 não cadastrados". */
    fun summary(items: List<ProposalItemPlan>, reading: PortalProposalReading, meEpp: Boolean?): String {
        val kinds = items.mapNotNull { marker(it, reading, meEpp)?.kind }
        val parts = listOfNotNull(
            kinds.count { it == PortalMarker.Kind.LANCADO }.takeIf { it > 0 }?.let { "$it lançado(s)" },
            kinds.count { it == PortalMarker.Kind.DIFERENTE }.takeIf { it > 0 }?.let { "$it com valor diferente" },
            kinds.count { it == PortalMarker.Kind.NAO_CADASTRADO }.takeIf { it > 0 }?.let { "$it não cadastrado(s)" },
            kinds.count { it == PortalMarker.Kind.BLOQUEADO }.takeIf { it > 0 }?.let { "$it em grupo ME/EPP vedado" },
            kinds.count { it == PortalMarker.Kind.AUSENTE }.takeIf { it > 0 }?.let { "$it fora do portal" },
        )
        return "Leitura de ${Formatters.dateTime(reading.readAt)}: " + parts.ifEmpty { listOf("nenhum item do plano no portal") }.joinToString(" · ")
    }
}

/**
 * Percorre TODAS as páginas internas de um grupo: vai para a página 1, lê, avança enquanto o paginador do grupo tiver
 * "›" habilitado (conferindo que a página mudou). second = aviso (página não abriu / repetida) ou null.
 */
object GroupPageWalk {
    data class View(val pager: GroupPager?, val items: List<String>)

    suspend fun walk(goTo: suspend (Int) -> String?, read: suspend () -> View, maxPages: Int = 30): Pair<List<List<String>>, String?> {
        val pages = mutableListOf<List<String>>()
        var page = 1
        while (page <= maxPages) {
            goTo(page)?.let { return pages to "página $page: $it" }
            val v = read()
            val current = v.pager?.current ?: 1
            if (v.pager?.hasPager == true && current != page) return pages to "o grupo ficou na página $current em vez da $page"
            if (pages.isNotEmpty() && v.items.isNotEmpty() && v.items == pages.last()) return pages to "a página $page repetiu os itens da anterior"
            pages += v.items
            if (v.pager?.hasNext != true) return pages to null
            page++
        }
        return pages to "parei em $maxPages páginas"
    }
}
