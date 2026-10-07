package com.licitaia.feature.live.automation

import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalTenderMatching
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Leitura (pura, testável na JVM) das telas REAIS do Compras.gov.br — SPA Angular + PrimeNG em
 * `cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/...`, mapeadas via DevTools no aparelho logado:
 *
 * - `/fornecedor/compras?compra=` ("Compras eletrônicas"): abas PrimeNG "Minhas participações" / "Todas as compras",
 *   filtro "Em andamento" / "Propostas" / "Disputa" / "Seleção de fornecedores" / "Finalizadas", um `div.cp-itens-card`
 *   por compra ("PREGÃO ELETRÔNICO N° 90046/2025 980786 - PREFEITURA ... Etapa: Seleção de fornecedores"), coração
 *   `fa-heart fas` = favorita, link "COMPRA SUSPENSA/ANULADA/REVOGADA", paginação PrimeNG.
 * - `/fornecedor/cadastro-propostas?compra=<UASG 6><modalidade 2><número 5><ano 4>`: termo/declarações e um
 *   `div.cp-itens-card` por item ("1 ACESSO A INTERNET ... Valor estimado (unitário) R$ 151.000,0000 Proposta não
 *   cadastrada"); valor em `input[data-test="input-valor-proposta"]` (máscara de moeda, 4 casas).
 */

/** Compra lida de um cartão da lista "Compras eletrônicas". */
data class SpaPurchase(
    /** Modalidade como o app escreve ("Pregão Eletrônico", "Dispensa Eletrônica"...). */
    val modality: String,
    val number: String,
    val year: Int,
    /** UASG como o portal mostra (5 ou 6 dígitos, sem zeros à esquerda). */
    val uasg: String,
    val agency: String,
    /** "Menor Preço / Maior Desconto" etc. (vazio se o cartão não mostra). */
    val criterion: String,
    /** Exclusiva/preferencial ME/EPP. */
    val meEpp: Boolean,
    /** Texto depois de "Etapa:" ("Seleção de fornecedores", "Até: Proposta 13/10/2026 08:59"...). */
    val stage: String,
    /** "COMPRA SUSPENSA" / "ANULADA" / "REVOGADA"... (null = sem aviso). */
    val status: String?,
    val favorite: Boolean,
) {
    val numberYear: String get() = "$number/$year"
    val uasg6: String get() = uasg.padStart(6, '0')
}

/** Cartão cru lido pela página (texto visível + coração preenchido). */
data class SpaCard(val text: String, val favorite: Boolean)

object SpaPurchaseParser {
    private val header = Regex("""^(.*?)\s+N\s*[°ºo.]*\s*(\d{1,6})\s*/\s*(\d{4})\s+(ME/EPP\s+)?(\d{4,6})\s+-\s+(.*)$""", RegexOption.IGNORE_CASE)
    private val status = Regex("""\bCOMPRA\s+(SUSPENSA|ANULADA|REVOGADA|CANCELADA|DESERTA|FRACASSADA|ENCERRADA|HOMOLOGADA)\b""")
    private val criterion = Regex("""\b(Menor Pre[çc]o|Maior Desconto|Melhor T[ée]cnica|T[ée]cnica e Pre[çc]o|Maior Lance|Maior Retorno|Conte[úu]do Art[íi]stico)""")
    private val stageMark = Regex("""\bEtapa:\s*""")

    /** Um cartão → compra; null se o texto não tem "MODALIDADE N° n/aaaa UASG - órgão". */
    fun parse(card: SpaCard): SpaPurchase? {
        val text = card.text.replace('|', ' ').replace(Regex("\\s+"), " ").trim()
        val m = header.find(text) ?: return null
        val number = m.groupValues[2].trimStart('0').ifEmpty { "0" }
        val year = m.groupValues[3].toIntOrNull()?.takeIf { it in 1990..2100 } ?: return null
        val uasg = m.groupValues[5].trimStart('0').ifEmpty { return null }
        val rest = m.groupValues[6]
        val st = status.find(rest)
        val cr = criterion.find(rest)
        val et = stageMark.find(rest)
        val cut = listOfNotNull(st?.range?.first, cr?.range?.first, et?.range?.first).minOrNull() ?: rest.length
        val agency = rest.substring(0, cut).trim().trimEnd('-').trim()
        val criterionText = cr?.let { c ->
            val end = listOfNotNull(st?.range?.first, et?.range?.first).filter { it > c.range.first }.minOrNull() ?: rest.length
            rest.substring(c.range.first, end).trim()
        }.orEmpty()
        val stageText = et?.let { e ->
            val end = st?.range?.first?.takeIf { it > e.range.last } ?: rest.length
            rest.substring(e.range.last + 1, end).trim()
        }.orEmpty()
        return SpaPurchase(
            modality = PurchaseText.modality(m.groupValues[1]).ifEmpty { m.groupValues[1].trim() },
            number = number, year = year, uasg = uasg, agency = agency, criterion = criterionText,
            meEpp = m.groupValues[4].isNotBlank(), stage = stageText,
            status = st?.value?.replace(Regex("\\s+"), " "), favorite = card.favorite,
        )
    }

    /** "Até: Proposta 13/10/2026 08:59" → "Proposta até 13/10/2026 08:59"; "Início: Disputa agendada Sem prazo definido" → "Disputa agendada · sem prazo definido". */
    fun stageLabel(stage: String): String {
        var s = stage.trim()
        Regex("""^At[ée]:\s*(\S+)\s+(.*)$""", RegexOption.IGNORE_CASE).find(s)?.let { s = "${it.groupValues[1]} até ${it.groupValues[2]}" }
        s = s.replace(Regex("""^In[íi]cio:\s*""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\s+Sem prazo definido""", RegexOption.IGNORE_CASE), " · sem prazo definido")
        return s.trim()
    }

    /** Situação gravada em `portal_my_tenders.situation`: aviso da compra OU etapa, + "Favorita" (coração do portal). */
    fun situation(p: SpaPurchase): String = listOfNotNull(
        p.status?.lowercase()?.replaceFirstChar { it.uppercase() } ?: stageLabel(p.stage).takeIf { it.isNotBlank() },
        "Favorita".takeIf { p.favorite },
    ).joinToString(" · ")

    /**
     * Cartões → "minhas licitações". [participation] = vieram de "Minhas participações" (a empresa participa da
     * compra → `hasProposal`). Favorita e etapa vão em `situation`; o órgão (o cartão não mostra o objeto) vai em
     * `objectDescription`; o prazo "Até: Proposta dd/mm/aaaa hh:mm" (quando há) vai em `openingAt`.
     */
    fun toMyTenders(cards: List<SpaCard>, companyId: Long, source: String, participation: Boolean, now: Long): List<PortalMyTender> {
        val out = LinkedHashMap<String, PortalMyTender>()
        for (c in cards) {
            val p = parse(c) ?: continue
            val key = PortalTenderMatching.tenderKey(p.uasg, p.number, p.year) ?: continue
            val t = PortalMyTender(
                companyId = companyId, tenderKey = key, uasg = p.uasg6, number = p.number, year = p.year,
                modality = p.modality,
                objectDescription = listOf(p.agency, p.criterion).filter { it.isNotBlank() }.joinToString(" — ").take(500),
                openingAt = PurchaseText.dateTime(p.stage), situation = situation(p).take(120),
                hasProposal = participation, sources = setOf(source), firstSeenAt = now, updatedAt = now,
            )
            out[key] = out[key]?.let { old ->
                old.copy(situation = if (old.situation.contains("Favorita")) old.situation else t.situation, hasProposal = old.hasProposal || t.hasProposal)
            } ?: t
        }
        return out.values.toList()
    }

    /** "Exibindo 10 de 16 registro(s)" → (10, 16). */
    fun showing(text: String?): Pair<Int, Int>? =
        Regex("""Exibindo\s+(\d+)\s+de\s+(\d+)""", RegexOption.IGNORE_CASE).find(text.orEmpty())
            ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
}

/**
 * Código `compra=` da URL de cadastro de proposta: UASG (6) + modalidade (2) + número (5) + ano (4).
 * Ex.: pregão 3/2026 da UASG 927312 → `92731205000032026`.
 */
object CompraCode {
    /** Código de modalidade do portal. 05 = pregão (confirmado no aparelho); 06/03 seguem o id legado do app. */
    fun modalityCode(modality: String): String? {
        val t = TextNorm.norm(modality)
        return when {
            t.contains("pregao") -> "05"
            t.contains("dispensa") -> "06"
            t.contains("concorrencia") -> "03"
            else -> null
        }
    }

    fun of(uasg: String, modality: String, number: String, year: Int): String? {
        val u = uasg.filter(Char::isDigit).takeIf { it.isNotEmpty() && it.length <= 6 } ?: return null
        val n = number.filter(Char::isDigit).trimStart('0').ifEmpty { "0" }.takeIf { it.length <= 5 } ?: return null
        val mod = modalityCode(modality) ?: return null
        return u.padStart(6, '0') + mod + n.padStart(5, '0') + year
    }

    /** Valor de `compra=` numa URL (null se não houver). */
    fun fromUrl(url: String?): String? =
        Regex("""[?&]compra=(\d{17})(?:&|#|$)""").find(url.orEmpty())?.groupValues?.get(1)

    /**
     * A URL é a de cadastro de proposta da compra esperada? Com a modalidade conhecida compara o código inteiro; sem
     * ela, UASG + número + ano (qualquer modalidade).
     */
    fun isProposalPageFor(url: String?, uasg: String, modality: String, number: String, year: Int): Boolean {
        val u = url.orEmpty()
        if (!u.contains("/fornecedor/cadastro-propostas")) return false
        val code = fromUrl(u) ?: return false
        of(uasg, modality, number, year)?.let { return it == code }
        val n = number.filter(Char::isDigit).trimStart('0').ifEmpty { "0" }.padStart(5, '0')
        return code.startsWith(uasg.filter(Char::isDigit).padStart(6, '0')) && code.endsWith(n + year)
    }

    /** Texto do campo "Número da compra" da busca: número + ano sem barra (3/2026 → "32026"). */
    fun searchNumber(number: String, year: Int): String = number.filter(Char::isDigit).trimStart('0').ifEmpty { "0" } + year
}

/** Valor da proposta para a máscara de moeda do portal (4 casas; dígitos sem vírgula viram parte INTEIRA). */
object ProposalMoney {
    /** Texto a DIGITAR: duas casas, vírgula decimal, sem milhar (150999.99 → "150999,99"). */
    fun typed(value: Double): String {
        require(value.isFinite() && value > 0) { "valor inválido" }
        return BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
    }

    /** "R$ 150.999,9900" / "150.999,99" → 150999.9900; null se não houver número. */
    fun parse(text: String?): BigDecimal? {
        val m = Regex("""\d[\d.]*(?:,\d+)?""").find(text.orEmpty().replace("R$", "").replace(" ", "").replace(" ", "")) ?: return null
        return runCatching { BigDecimal(m.value.replace(".", "").replace(',', '.')).setScale(4, RoundingMode.HALF_UP) }.getOrNull()
    }

    /** O campo/cartão mostra exatamente o valor pretendido (comparação numérica com 4 casas). */
    fun matches(expected: Double, shown: String?): Boolean {
        val a = BigDecimal(expected.toString()).setScale(2, RoundingMode.HALF_UP).setScale(4)
        val b = parse(shown) ?: return false
        return a.compareTo(b) == 0
    }
}

/** Estado do bloco "Termo/declarações" do cadastro de proposta (lido pela página; o robô NUNCA marca nada). */
data class DeclarationsState(
    /** A página tem o bloco "Termo/declarações". */
    val section: Boolean,
    /** Aviso "É necessário o aceite do termo e a escolha do enquadramento..." visível. */
    val warning: Boolean,
    /** Checkbox "Termo de Aceitação." (null = não achado). */
    val termAccepted: Boolean?,
    val groups: List<Group>,
    /** Modal "Termo de aceitação das declarações" aberto. */
    val modalOpen: Boolean,
) {
    data class Group(val key: String, val label: String, val yes: Boolean, val no: Boolean) {
        val answered: Boolean get() = yes || no
    }
}

object DeclarationsCheck {
    /** O que falta o USUÁRIO marcar (vazio = pronto: termo aceito + um rádio de cada grupo). */
    fun missing(s: DeclarationsState): List<String> = buildList {
        if (!s.section) { add("bloco “Termo/declarações” não encontrado na página"); return@buildList }
        if (s.modalOpen) add("conclua o “Termo de aceitação das declarações” (Confirmar ou Cancelar)")
        if (s.termAccepted != true) add("aceite do “Termo de Aceitação”")
        s.groups.filter { !it.answered }.forEach { add("escolha Sim/Não em “${it.label}”") }
        if (s.warning && s.termAccepted == true && s.groups.all { it.answered }) add("o portal ainda pede o aceite do termo/declarações")
    }

    /**
     * Seguir? Pronto → sim. Depois de o usuário tocar "Continuar" ([userConfirmed]) aceita só a falta do BLOCO (compra
     * sem declarações); termo/rádios em branco ou o aviso do portal continuam travando.
     */
    fun canProceed(s: DeclarationsState, userConfirmed: Boolean): Boolean {
        val m = missing(s)
        if (m.isEmpty()) return true
        return userConfirmed && !s.section && !s.warning
    }

    fun parse(raw: String?): DeclarationsState? {
        val o = AutomationJson.obj(raw) ?: return null
        with(AutomationJson) {
            val groups = (o["groups"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
                val g = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                DeclarationsState.Group(g.str("k").orEmpty(), g.str("l").orEmpty(), g.bool("s"), g.bool("n"))
            }
            val termo = (o["termo"] as? kotlinx.serialization.json.JsonPrimitive)?.let { p -> if (p is kotlinx.serialization.json.JsonNull) null else p.content == "true" }
            return DeclarationsState(o.bool("section"), o.bool("warning"), termo, groups, o.bool("modal"))
        }
    }
}

/** Cartão de item do cadastro de proposta. */
data class ProposalItemCard(
    val number: Int,
    /** "Proposta não cadastrada" visível. */
    val noProposal: Boolean,
    /** "Meu valor (unitário)" do cartão (null = sem proposta / não mostrado). */
    val myUnitValue: BigDecimal?,
    val estimatedUnitValue: BigDecimal?,
)

object ProposalItemCardParser {
    private val money = Regex("""R\$\s*(\d[\d.]*,\d{2,4})""")

    /**
     * Texto do cartão (ex.: "1 ACESSO ... < apelido > Quantidade solicitada Unidade fornecimento 1 Valor estimado
     * (unitário) Meu valor (unitário) R$ 151.000,0000 R$ 150.999,9900"): os rótulos vêm antes dos valores, na mesma
     * ordem.
     */
    fun parse(text: String, numberHint: Int? = null): ProposalItemCard? {
        val t = text.replace('|', ' ').replace(Regex("\\s+"), " ").trim()
        val n = numberHint ?: Regex("""^(\d{1,4})\b""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val norm = TextNorm.norm(t)
        val labels = listOf("valor estimado" to norm.indexOf("valor estimado"), "meu valor" to norm.indexOf("meu valor"))
            .filter { it.second >= 0 }.sortedBy { it.second }.map { it.first }
        val values = money.findAll(t).map { ProposalMoney.parse(it.groupValues[1]) }.toList()
        fun valueOf(label: String): BigDecimal? = labels.indexOf(label).takeIf { it >= 0 }?.let { values.getOrNull(it) }
        return ProposalItemCard(n, norm.contains("proposta nao cadastrada"), valueOf("meu valor"), valueOf("valor estimado"))
    }
}

/** Aviso (toast PrimeNG) da página. */
data class PortalToast(val severity: String, val text: String) {
    companion object {
        fun of(cls: String, text: String): PortalToast {
            val c = cls.lowercase()
            val sev = when {
                c.contains("toast-message-success") -> "success"
                c.contains("toast-message-error") -> "error"
                c.contains("toast-message-warn") -> "warn"
                else -> "info"
            }
            return PortalToast(sev, text.replace(Regex("\\s+"), " ").trim())
        }
    }
}

/** Decisão depois de clicar "Salvar" num item: seguir, esperar ou parar. */
object ProposalSaveCheck {
    sealed interface Outcome {
        data object Ok : Outcome
        data object Wait : Outcome
        data class Stop(val reason: String) : Outcome
    }

    /**
     * @param timedOut o tempo de espera acabou (sem confirmação → para).
     * Erro/alerta do portal → para; cartão com "Meu valor (unitário)" igual ao pretendido → ok (com ou sem toast);
     * toast de sucesso mas cartão divergente → para.
     */
    fun evaluate(expected: Double, toasts: List<PortalToast>, card: ProposalItemCard?, timedOut: Boolean): Outcome {
        toasts.firstOrNull { it.severity == "error" || it.severity == "warn" }?.let { return Outcome.Stop("o portal avisou: “${it.text.take(160)}”") }
        val shown = card?.myUnitValue
        val same = shown != null && ProposalMoney.matches(expected, shown.toPlainString().replace('.', ','))
        if (same) return Outcome.Ok
        val success = toasts.any { it.severity == "success" }
        if (success && shown != null) return Outcome.Stop("o portal salvou, mas o item mostra ${shown.toPlainString().replace('.', ',')} em vez de ${ProposalMoney.typed(expected)}")
        if (timedOut) {
            return Outcome.Stop(
                if (success) "o portal disse “sucesso”, mas o item não mostra “Meu valor (unitário)”" else "sem confirmação do portal (nenhum aviso de sucesso e o item não mostra o valor)",
            )
        }
        return Outcome.Wait
    }
}
