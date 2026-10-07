package com.licitaia.feature.live.automation

import com.licitaia.domain.model.PortalDeclarations
import com.licitaia.domain.util.Formatters

/*
 * Decisões PURAS (testáveis na JVM) do robô de proposta no cadastro de proposta REAL do Compras.gov.br:
 * página/compra certa, disponibilidade, Termo de aceitação (modal "Termo de aceitação das declarações"), rádios das
 * declarações da empresa, número exato do item e estratégia de salvamento em compras com GRUPOS/LOTES.
 */

/** Leitura crua da página atual (URL de `location.href`, texto visível do início do corpo, lista de compras?). */
data class PurchasePage(val url: String, val text: String, val isList: Boolean = false, val modalOpen: Boolean = false)

object PurchasePageCheck {
    /** Cabeçalho da compra: o texto antes do bloco "Termo/declarações" / "Itens" (onde ficam UASG, N° e prazo). */
    fun header(text: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        val cut = listOf("Termo/declara", "Endereço do fornecedor", " Itens ").map { t.indexOf(it) }.filter { it > 0 }.minOrNull() ?: t.length
        return t.substring(0, cut).take(2_000)
    }

    /**
     * null = é o cadastro de proposta da compra [p]: URL `cadastro-propostas?compra=<código esperado>` E cabeçalho com
     * "UASG <uasg>" e "N° <número>/<ano>". Senão o motivo (lista de compras, outra compra, cabeçalho divergente).
     */
    fun verify(page: PurchasePage, p: SpaNavigator.Purchase, requireProposalPage: Boolean = true): String? {
        val url = page.url
        // "Abrir no portal": a página "Acompanhar Contratação" (compra suspensa etc.) da compra certa também vale.
        if (!requireProposalPage && !page.isList && isPurchasePage(page) && !CompraCode.isProposalPageFor(url, p.uasg, p.modality, p.number, p.year)) {
            mismatch(page, p)?.let { return it }
            val head = header(page.text)
            return if (uasgIn(head, p.uasgShown) && numberIn(head, p.number, p.year)) null else "o cabeçalho da página ainda não mostra a compra UASG ${p.uasgShown} · N° ${p.numberYear}"
        }
        if (!CompraCode.isProposalPageFor(url, p.uasg, p.modality, p.number, p.year)) {
            mismatch(page, p)?.let { return it }
            return when {
                url.contains("/fornecedor/cadastro-propostas") ->
                    "a página aberta é o cadastro de OUTRA compra (compra=${CompraCode.fromUrl(url) ?: "?"}; esperado ${CompraCode.of(p.uasg, p.modality, p.number, p.year) ?: "UASG ${p.uasgShown} ${p.numberYear}"})"
                page.isList -> "o portal ficou na lista de compras (Minhas participações/Todas as compras), não no cadastro de proposta"
                else -> "a página do cadastro de proposta da compra não está aberta"
            }
        }
        val head = header(page.text)
        if (head.isBlank()) return "o cabeçalho da compra ainda não carregou"
        if (!uasgIn(head, p.uasgShown)) return "o cabeçalho da página não mostra “UASG ${p.uasgShown}”"
        if (!numberIn(head, p.number, p.year)) return "o cabeçalho da página não mostra “N° ${p.numberYear}”"
        return null
    }

    /** Página de UMA compra (cadastro de proposta, "Acompanhar Contratação", qualquer `?compra=`) — não a lista. */
    fun isPurchasePage(page: PurchasePage): Boolean {
        if (page.isList) return false
        val u = page.url
        if (u.contains("/fornecedor/cadastro-propostas") || u.contains("compra=") || u.contains("acompanha", ignoreCase = true)) return true
        val n = TextNorm.norm(page.text.take(600))
        return n.contains("acompanhar contratacao") || n.contains("cadastrar propostas")
    }

    /** UASG + número + ano mostrados no cabeçalho da compra aberta (null = não é página de compra / não lido). */
    data class Ref(val uasg: String, val number: String, val year: Int)

    private val refUasg = Regex("""(?i)\bUASG\s*:?\s*0*(\d{4,6})(?!\d)""")
    private val refNumber = Regex("""(?i)\bN\s*[°ºo.]*\s*0*(\d{1,6})\s*/\s*(\d{4})(?!\d)""")

    fun currentRef(page: PurchasePage): Ref? {
        if (!isPurchasePage(page)) return null
        val head = header(page.text)
        val u = refUasg.find(head)?.groupValues?.get(1) ?: return null
        val m = refNumber.find(head) ?: return null
        return Ref(u, m.groupValues[1], m.groupValues[2].toInt())
    }

    /** A página aberta é de OUTRA compra (UASG/número/ano diferentes do alvo)? Motivo ou null. */
    fun mismatch(page: PurchasePage, p: SpaNavigator.Purchase): String? {
        val r = currentRef(page) ?: return null
        val same = r.uasg.trimStart('0') == p.uasgShown && r.number.trimStart('0').ifEmpty { "0" } == p.number.filter(Char::isDigit).trimStart('0').ifEmpty { "0" } && r.year == p.year
        return if (same) null else "a página mostra a compra UASG ${r.uasg} · N° ${r.number}/${r.year}, não a pedida (UASG ${p.uasgShown} · N° ${p.numberYear})"
    }

    fun uasgIn(text: String, uasg: String): Boolean {
        val u = uasg.filter(Char::isDigit).trimStart('0').ifEmpty { return false }
        return Regex("""(?i)\bUASG\s*:?\s*0*$u(?!\d)""").containsMatchIn(text)
    }

    fun numberIn(text: String, number: String, year: Int): Boolean {
        val n = number.filter(Char::isDigit).trimStart('0').ifEmpty { "0" }
        return Regex("""(?i)\bN\s*[°ºo.]*\s*0*$n\s*/\s*$year(?!\d)""").containsMatchIn(text)
    }

    /** Disponibilidade: prazo ("Data limite de entrega de propostas") e aviso de compra suspensa/encerrada. */
    data class Availability(val deadline: Long?, val blocked: String?) {
        val ok: Boolean get() = blocked == null
        fun describe(): String = when {
            blocked != null -> blocked
            deadline != null -> "esta compra aceita proposta agora · prazo ${Formatters.dateTime(deadline)}"
            else -> "a página não mostra o prazo de entrega de propostas"
        }
    }

    private val deadlineRe = Regex("""(?i)Data\s+limite\s+de\s+entrega\s+de\s+propostas\s*:?\s*(\d{2}/\d{2}/\d{4}(?:\s*(?:às|as|-)?\s*\d{1,2}[:h]\d{2})?)""")
    private val statusRe = Regex("""(?i)\bCOMPRA\s+(SUSPENSA|ANULADA|REVOGADA|CANCELADA|DESERTA|FRACASSADA|ENCERRADA|HOMOLOGADA)\b""")
    private val closedRe = Regex("""(?i)(prazo\s+(de\s+)?(entrega|envio|cadastr\w*)\s+(de\s+)?propostas?\s+(foi\s+)?(encerrado|expirado|finalizado)|fase\s+de\s+propostas\s+encerrada)""")

    fun availability(text: String, now: Long): Availability {
        val head = header(text)
        val deadline = deadlineRe.find(text)?.groupValues?.get(1)?.let { PurchaseText.dateTime(it) }
        statusRe.find(head)?.let { return Availability(deadline, "o portal mostra “${it.value.uppercase()}”: o robô não cadastra proposta") }
        closedRe.find(text)?.let { return Availability(deadline, "o portal avisa “${it.value}”") }
        if (deadline != null && deadline <= now) return Availability(deadline, "o prazo de entrega de propostas terminou em ${Formatters.dateTime(deadline)}")
        return Availability(deadline, null)
    }
}

/** Número do item no início do cartão ("77 ACESSO…" → 77; nunca casa 7 com 77). */
object ItemNumber {
    private val lead = Regex("""^\s*(\d{1,4})(?!\d)\b""")
    fun of(cardText: String): Int? = lead.find(cardText.replace('|', ' '))?.groupValues?.get(1)?.toIntOrNull()
    fun matches(cardText: String, n: Int): Boolean = of(cardText) == n
}

/** Modal "Termo de aceitação das declarações" (checkboxes + "Marcar todas" + "Confirmar"). */
object TermsModal {
    data class State(
        val open: Boolean,
        /** Declarações do modal (sem o "Marcar todas"). */
        val total: Int,
        val checked: Int,
        val confirmPresent: Boolean = true,
        val confirmDisabled: Boolean = false,
    ) {
        val missing: Int get() = (total - checked).coerceAtLeast(0)
    }

    sealed interface Decision {
        data object Confirm : Decision
        data object MarkAll : Decision
        data object MarkEach : Decision
        data class Stop(val reason: String) : Decision
    }

    /**
     * Todas marcadas → Confirmar. Faltando: 1º "Marcar todas"; depois uma a uma; ainda faltando → PARA sem confirmar.
     */
    fun decide(s: State, triedMarkAll: Boolean, triedEach: Boolean): Decision {
        if (!s.open) return Decision.Stop("o modal do termo não está aberto")
        if (s.total == 0) return Decision.Stop("o modal do termo não mostra nenhuma declaração para conferir")
        if (s.checked >= s.total) {
            if (!s.confirmPresent) return Decision.Stop("botão “Confirmar” do termo não encontrado")
            if (s.confirmDisabled) return Decision.Stop("o “Confirmar” do termo continua desabilitado")
            return Decision.Confirm
        }
        if (!triedMarkAll) return Decision.MarkAll
        if (!triedEach) return Decision.MarkEach
        return Decision.Stop("${s.missing} de ${s.total} declaração(ões) do termo continuam desmarcadas — o robô não confirma")
    }

    fun parse(raw: String?): State? {
        val o = AutomationJson.obj(raw) ?: return null
        return with(AutomationJson) {
            State(o.bool("open"), o.int("total") ?: 0, o.int("checked") ?: 0, o.bool("confirm"), o.bool("confirmDisabled"))
        }
    }
}

/** Rádios Sim/Não das declarações do cadastro de proposta e as respostas da empresa. */
object DeclarationRadios {
    data class Target(val key: String, val label: String, val id: String, val yes: Boolean)

    private val ids = mapOf(
        "meepp" to ("labelSimMeepp" to "labelNaoMeepp"),
        "equidade" to ("declaracaoEquidadeGeneroSim" to "declaracaoEquidadeGeneroNao"),
        "integridade" to ("declaracaoProgramasIntegridadeSim" to "declaracaoProgramasIntegridadeNao"),
    )

    fun answerFor(key: String, d: PortalDeclarations): Boolean? = when (key) {
        "meepp" -> d.meEpp
        "equidade" -> d.genderEquity
        "integridade" -> d.integrity
        else -> null
    }

    /**
     * Rádios a marcar: grupos presentes na página cujo estado difere da resposta da empresa. second = grupos presentes
     * sem resposta da empresa (o robô não escolhe por ela: para).
     */
    fun plan(state: DeclarationsState, d: PortalDeclarations): Pair<List<Target>, List<String>> {
        val todo = mutableListOf<Target>()
        val unanswered = mutableListOf<String>()
        state.groups.forEach { g ->
            val want = answerFor(g.key, d)
            val (yesId, noId) = ids[g.key] ?: return@forEach
            when {
                want == null -> unanswered += g.label
                want && !(g.yes && !g.no) -> todo += Target(g.key, g.label, yesId, true)
                !want && !(g.no && !g.yes) -> todo += Target(g.key, g.label, noId, false)
            }
        }
        return todo to unanswered
    }
}

/**
 * Compras com GRUPOS/LOTES (PROVISÓRIO: ainda não mapeado no portal real). A página lê a estrutura de cada grupo
 * aberto ([GroupStructure]) e o robô escolhe: salvar item a item (cada bloco de item tem o próprio "Salvar") ou
 * preencher todos os itens do grupo e tocar UMA vez no "Salvar" do grupo. Qualquer outra forma → para com segurança.
 */
data class GroupStructure(
    val index: Int,
    val label: String,
    /** Texto do cartão/cabeçalho do grupo ("GRUPO 1 | 24 itens … Proposta incompleta"). */
    val text: String = label,
    /** Itens (número) dentro do grupo. */
    val items: List<Int>,
    /** Campos de valor visíveis no grupo. */
    val inputs: Int,
    /** Campos de valor cujo bloco tem o próprio "Salvar". */
    val perItemSave: Int,
    /** Textos dos botões de salvar do GRUPO (fora dos blocos de item). */
    val groupSaves: List<String>,
    /** O grupo pede "Valor do grupo"/"valor global". */
    val groupValueField: Boolean,
)

sealed interface GroupStrategy {
    data object PerItem : GroupStrategy
    data class GroupSave(val button: String) : GroupStrategy
    data class Stop(val reason: String) : GroupStrategy
}

object GroupPlanner {
    fun decide(s: GroupStructure, plannedGroupTotal: Double? = null): GroupStrategy {
        if (s.groupValueField && plannedGroupTotal == null) {
            return GroupStrategy.Stop("o ${s.label} pede o valor do grupo e o plano não define esse valor: preencha no portal ou ajuste o plano")
        }
        if (s.inputs == 0) {
            // Real (GRUPO 1 | 24 itens): cada item do grupo é um cartão com seta; o campo aparece ao abrir o item.
            if (s.items.isNotEmpty() && s.groupSaves.isEmpty()) return GroupStrategy.PerItem
            if (s.items.isNotEmpty() && s.groupSaves.size == 1) return GroupStrategy.GroupSave(s.groupSaves.first())
            return GroupStrategy.Stop("o ${s.label} aberto não mostra itens nem campos de valor")
        }
        if (s.perItemSave > 0 && s.perItemSave >= s.inputs) return GroupStrategy.PerItem
        if (s.perItemSave == 0 && s.groupSaves.size == 1) return GroupStrategy.GroupSave(s.groupSaves.first())
        if (s.perItemSave == 0 && s.groupSaves.isEmpty()) return GroupStrategy.Stop("não achei botão de salvar no ${s.label}")
        return GroupStrategy.Stop(
            "não identifiquei com segurança como salvar o ${s.label} (${s.perItemSave} de ${s.inputs} item(ns) com Salvar próprio, " +
                "${s.groupSaves.size} botão(ões) do grupo)",
        )
    }

    fun parse(raw: String?): List<GroupStructure> {
        val o = AutomationJson.obj(raw) ?: return emptyList()
        return with(AutomationJson) {
            (o["groups"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
                val g = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                GroupStructure(
                    index = g.int("i") ?: return@mapNotNull null,
                    label = g.str("label").orEmpty().ifBlank { "grupo" },
                    text = g.str("text").orEmpty().ifBlank { g.str("label").orEmpty() },
                    items = (g["items"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                        .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() },
                    inputs = g.int("inputs") ?: 0,
                    perItemSave = g.int("perItemSave") ?: 0,
                    groupSaves = g.strings("saves"),
                    groupValueField = g.bool("groupValue"),
                )
            }
        }
    }
}

/**
 * Cartão de GRUPO do cadastro de proposta (REAL, Pregão 48/2026 UASG 160192): "GRUPO 1 | 24 itens < apelido >
 * Valor estimado (total) R$ 90.523,1600 Meu valor (total) R$ 25.110,0000 Proposta incompleta". Enquanto mostrar
 * "Proposta incompleta" o robô não passa para outro grupo.
 */
data class GroupCard(
    /** "GRUPO 1" / "LOTE 2" / "G3" normalizado em maiúsculas. */
    val key: String,
    val number: Int?,
    val itemCount: Int?,
    val estimatedTotal: java.math.BigDecimal?,
    val myTotal: java.math.BigDecimal?,
    val incomplete: Boolean,
    /** "Proposta não cadastrada" no cartão do grupo (nenhum item salvo). */
    val notRegistered: Boolean = false,
    /** "Exclusividade ME/EPP". */
    val meEppExclusive: Boolean = false,
) {
    fun progress(savedInGroup: Int): String = "${key.lowercase().replaceFirstChar { it.uppercase() }}: $savedInGroup/${itemCount ?: "?"}"

    companion object {
        private val head = Regex("""(?i)^\s*(grupo|lote)\s*(\d+)|^\s*g\s?(\d+)\b""")
        private val count = Regex("""(?i)\b(\d{1,4})\s+itens?\b""")
        private val money = Regex("""R\$\s*(\d[\d.]*,\d{2,4})""")

        fun parse(text: String): GroupCard? {
            val full = text.replace('|', ' ').replace(Regex("\\s+"), " ").trim()
            // Só o cabeçalho do grupo: se os itens vierem dentro do cartão, corta antes do 2º "Valor estimado".
            val firstEst = full.indexOf("Valor estimado", ignoreCase = true)
            val secondEst = if (firstEst >= 0) full.indexOf("Valor estimado", firstEst + 1, ignoreCase = true) else -1
            val t = if (secondEst > 0) full.substring(0, secondEst) else full
            val m = head.find(t) ?: return null
            val word = m.groupValues[1].ifBlank { "G" }.uppercase()
            val num = (m.groupValues[2].ifBlank { m.groupValues[3] }).toIntOrNull()
            val key = if (word == "G") "G$num" else "$word $num"
            val norm = TextNorm.norm(t)
            val labels = listOf("valor estimado" to norm.indexOf("valor estimado"), "meu valor" to norm.indexOf("meu valor"))
                .filter { it.second >= 0 }.sortedBy { it.second }.map { it.first }
            val values = money.findAll(t).map { ProposalMoney.parse(it.groupValues[1]) }.toList()
            fun valueOf(label: String) = labels.indexOf(label).takeIf { it >= 0 }?.let { values.getOrNull(it) }
            return GroupCard(
                key = key, number = num,
                itemCount = count.find(t)?.groupValues?.get(1)?.toIntOrNull(),
                estimatedTotal = valueOf("valor estimado"), myTotal = valueOf("meu valor"),
                incomplete = norm.contains("proposta incompleta"),
                notRegistered = norm.contains("proposta nao cadastrada"),
                meEppExclusive = norm.contains("exclusividade me/epp"),
            )
        }
    }
}

/** Paginador PrimeNG DENTRO de um grupo ("«  ‹  1  [2]  3  ›  »"). */
data class GroupPager(val hasPager: Boolean, val pages: List<Int>, val current: Int, val hasNext: Boolean) {
    val lastKnownPage: Int get() = (pages.maxOrNull() ?: 1).coerceAtLeast(current)

    companion object {
        fun parse(raw: String?): GroupPager? {
            val o = AutomationJson.obj(raw) ?: return null
            return with(AutomationJson) {
                if (!o.bool("found")) return null
                GroupPager(o.bool("pager"), o.strings("pages").mapNotNull { it.trim().toIntOrNull() }, o.int("current") ?: 1, o.bool("next"))
            }
        }
    }
}

/**
 * Percurso de um grupo paginado: "Grupo 1 tem 24 itens, a página mostra 10": preenche a página 1, vai para a 2, …,
 * até a última; depois o próximo grupo. Depois de cada Salvar o portal pode voltar para a página 1: o robô volta para a
 * página em processamento antes do próximo item.
 */
object GroupTraversal {
    sealed interface Action {
        data object Stay : Action
        data class GoTo(val page: Int) : Action
        data class Fail(val reason: String) : Action
    }

    /** Estando em [pager], o que fazer para processar a página [target]. */
    fun decide(pager: GroupPager, target: Int): Action = when {
        pager.current == target -> Action.Stay
        !pager.hasPager && target == 1 -> Action.Stay
        !pager.hasPager -> Action.Fail("o grupo não tem paginação e a página $target foi pedida")
        target < 1 -> Action.Fail("página inválida")
        else -> Action.GoTo(target)
    }

    /** Páginas esperadas pelo total de itens do cartão ("24 itens") e o tamanho da página visto. */
    fun expectedPages(itemCount: Int?, perPage: Int, pager: GroupPager?): Int {
        val byCount = if (itemCount != null && perPage > 0) (itemCount + perPage - 1) / perPage else 1
        return maxOf(byCount, pager?.lastKnownPage ?: 1, 1)
    }

    /** "Grupo 1 · página 2/3 · 14/24". */
    fun progress(key: String, page: Int, pages: Int, done: Int, itemCount: Int?): String =
        "${key.lowercase().replaceFirstChar { it.uppercase() }} · página $page/$pages · $done/${itemCount ?: "?"}"
}