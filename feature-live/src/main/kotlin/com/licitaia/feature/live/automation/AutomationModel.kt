package com.licitaia.feature.live.automation

import java.text.Normalizer

/*
 * Modelo do motor de automação por DOM (lógica pura, testável na JVM).
 *
 * O motor só lê e interage com a página como o usuário faria (texto visível, rótulos, cliques, digitação). Não usa
 * APIs/REST internas do portal. Os localizadores são "robustos": vários candidatos por alvo, por texto visível,
 * rótulo, placeholder, aria-label, papel (role) e nome do campo, normalizando acentos e caixa. Quando um candidato
 * por TEXTO acha o elemento, o seletor CSS estável dele é guardado no [LearnedSelectors] ("mapa aprendido") e tentado
 * primeiro na próxima vez — se deixar de valer, volta aos candidatos por texto.
 *
 * As telas do SPA "Compras eletrônicas" (lista de compras, cadastro de proposta) foram mapeadas no aparelho: os
 * seletores reais estão em [SpaScripts]/[PortalTargets]; a sala de disputa e o chat ainda são heurística por texto.
 */

/** Como um candidato procura o elemento na página. */
enum class LocatorKind {
    /** Texto visível do elemento (botão, link, aba, item de menu, célula). */
    TEXT,
    /** Rótulo do campo: `<label for>`, label ancestral, aria-labelledby, texto da célula/linha anterior. */
    LABEL,
    PLACEHOLDER,
    ARIA_LABEL,
    /** Atributo `role` (ex.: "dialog", "row"); [Locator.value] = papel. */
    ROLE,
    /** `name` ou `formcontrolname` (Angular) do campo, comparação exata normalizada. */
    NAME,
    /** Seletor CSS (só do mapa aprendido ou de telas mapeadas). */
    CSS,
}

/** Tipo de elemento aceito pelo alvo. */
enum class ElementKind {
    /** a, button, [role=button|menuitem|tab|link|option], input[type=submit|button], [onclick], mat-option. */
    CLICKABLE,
    /** input (texto/número), textarea, select. */
    INPUT,
    CHECKBOX,
    /** Qualquer elemento visível. */
    ANY,
}

data class Locator(
    val kind: LocatorKind,
    val value: String,
    /** true = texto normalizado igual; false = contém (desempate pelo texto mais curto). */
    val exact: Boolean = false,
)

/**
 * Um alvo da página: chave estável (para o mapa aprendido + logs), tipo de elemento, candidatos em ordem de preferência
 * e, opcionalmente, o texto de um CONTÊINER (linha/cartão) dentro do qual procurar (ex.: a linha do "Item 3").
 */
data class Target(
    val key: String,
    val label: String,
    val kind: ElementKind,
    val candidates: List<Locator>,
    val scopeText: String? = null,
    val timeoutMs: Long = 15_000,
    /** Ação crítica (preencher valor, enviar lance): mais de um elemento igualmente bom = AMBÍGUO → para. */
    val critical: Boolean = false,
) {
    fun inScope(text: String?): Target = copy(scopeText = text, key = if (text == null) key else "$key@scope")
}

/** Consulta enviada ao driver da página (candidatos já com o seletor aprendido na frente, se houver). */
data class ElementQuery(
    val kind: ElementKind,
    val locators: List<Locator>,
    val scopeText: String? = null,
    /** Ação crítica: elemento ambíguo não é clicado/preenchido. */
    val critical: Boolean = false,
)

/** Resposta do driver ao procurar um elemento. */
data class FindResult(
    val found: Boolean,
    /** Índice do candidato que achou (em [ElementQuery.locators]). */
    val locatorIndex: Int = -1,
    /** Elementos igualmente bons para o candidato vencedor (>1 = ambíguo). */
    val count: Int = 0,
    /** Seletor CSS estável do elemento achado (id/name/formcontrolname/aria/caminho curto); null se não há um estável. */
    val css: String? = null,
    val text: String = "",
    val tag: String = "",
)

/** Resultado de preencher/clicar. */
data class ActionResult(
    val ok: Boolean,
    val find: FindResult = FindResult(false),
    /** Valor lido de volta do campo após preencher. */
    val readBack: String? = null,
    val error: String? = null,
)

/** "Fotografia" mínima da página para classificar o estado (sem valores de campos). */
data class PageProbe(
    /** URL sem query/fragmento. */
    val url: String = "",
    val title: String = "",
    /** Texto visível normalizado (minúsculas, sem acentos), truncado. Fica só na memória. */
    val text: String = "",
    val hasCaptcha: Boolean = false,
    /** Campo de código de verificação (MFA/OTP) visível. */
    val hasOtpField: Boolean = false,
    /** Textos de diálogos/modais abertos (normalizados). */
    val dialogs: List<String> = emptyList(),
)

/** Interface da página (WebView no app, falsa nos testes). Todas as chamadas são suspensas e nunca lançam por timeout. */
interface PageDriver {
    suspend fun currentUrl(): String?
    suspend fun find(query: ElementQuery): FindResult
    suspend fun fill(query: ElementQuery, value: String): ActionResult
    suspend fun click(query: ElementQuery): ActionResult
    /** Texto/valor do elemento (input → value; demais → texto visível). */
    suspend fun read(query: ElementQuery): String?
    suspend fun probe(): PageProbe
    /** Textos das "linhas" visíveis (tr, [role=row], mat-row, cartões, itens de lista), normalizados por espaços. */
    suspend fun rows(scopeText: String? = null): List<String>
    /** HTML das tabelas (todas as frames do mesmo domínio), limitado (páginas em tabela; as telas atuais do SPA usam [SpaScripts]). */
    suspend fun tablesHtml(): String
    /** Snapshot estrutural do MODO MAPEAR (JSON), já sem valores digitados. */
    suspend fun snapshot(): String

    /** Roda um script ([SpaScripts]) e devolve o retorno cru do WebView (null = sem resposta). */
    suspend fun eval(script: String): String? = null

    /**
     * Digitação NATIVA (eventos de tecla do Android → keydown/keypress/input confiáveis na página) no elemento que
     * está com o foco. [clearFirst] = apaga antes (o script de foco já selecionou tudo). false = não deu para digitar
     * (WebView fora de uma janela, sem foco...).
     */
    suspend fun typeKeys(text: String, clearFirst: Boolean): Boolean = false

    /** Tecla Enter nativa no elemento com foco. */
    suspend fun pressEnter(): Boolean = false

    /** Toque nativo na posição (px da view) — usado só como último recurso para "Pesquisar". */
    suspend fun tapAt(x: Float, y: Float): Boolean = false

    /** Alternativa à digitação nativa: `document.execCommand('insertText')` no elemento com foco. */
    suspend fun insertText(text: String): Boolean = false
}

/** Normalização usada em toda a comparação de textos (mesma do JS [AutomationScripts.PRELUDE]). */
object TextNorm {
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")
    fun norm(s: String?): String =
        Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFD).replace(marks, "").lowercase().replace(spaces, " ").trim()

    /** "R$ 1.234,56" / "1234,56" / "1,234.56" → 1234.56; null se não houver número. */
    fun parseMoney(s: String?): Double? {
        val raw = s.orEmpty().replace("R$", "").replace(" ", " ").trim()
        val m = Regex("""-?\d[\d.,\s]*""").find(raw)?.value?.replace(" ", "")?.trimEnd('.', ',') ?: return null
        val lastComma = m.lastIndexOf(',')
        val lastDot = m.lastIndexOf('.')
        val normalized = when {
            // "1.234,56" / "1234,5": vírgula decimal (padrão BR).
            lastComma > lastDot -> m.replace(".", "").replace(',', '.')
            // "1,234.56": ponto decimal com milhar por vírgula.
            lastComma in 0 until lastDot -> m.replace(",", "")
            // Só pontos: "12.5"/"12.50" = decimal; "1.234"/"1.234.567" = milhar.
            lastDot >= 0 && m.count { it == '.' } == 1 && m.length - lastDot - 1 in 1..2 -> m
            lastDot >= 0 -> m.replace(".", "")
            else -> m
        }
        return normalized.toDoubleOrNull()
    }

    /** Valor no formato que os campos de moeda do portal aceitam ao digitar: "1234,56". */
    fun formatInputMoney(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v).replace('.', ',')

    /** Quantidade: inteiro sem casas; senão com vírgula. */
    fun formatInputNumber(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.').replace('.', ',')
}

/**
 * Mapa aprendido: chave (página + alvo) → seletor CSS que funcionou. Persistido pelo [LearnedSelectorStore].
 * Página = caminho da URL normalizado (números viram ":n"), para valer para qualquer compra/item.
 */
class LearnedSelectors(initial: Map<String, Entry> = emptyMap()) {
    data class Entry(val css: String, val hits: Int, val updatedAt: Long)

    private val map = LinkedHashMap(initial)

    @Synchronized fun get(pageKey: String, targetKey: String): String? = map[key(pageKey, targetKey)]?.css

    @Synchronized fun learn(pageKey: String, targetKey: String, css: String, now: Long) {
        val k = key(pageKey, targetKey)
        val old = map[k]
        map[k] = Entry(css, if (old?.css == css) old.hits + 1 else 1, now)
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
    }

    @Synchronized fun forget(pageKey: String, targetKey: String) { map.remove(key(pageKey, targetKey)) }

    @Synchronized fun snapshot(): Map<String, Entry> = LinkedHashMap(map)

    companion object {
        const val MAX_ENTRIES = 500
        fun key(pageKey: String, targetKey: String) = "$pageKey|$targetKey"

        /** "https://host/a/123/b?x=1" → "host/a/:n/b"; rota em fragmento ("#/compras/9") vira parte do caminho. */
        fun pageKey(url: String?): String {
            val raw = url.orEmpty().removePrefix("https://").removePrefix("http://")
            val path = raw.substringBefore('#').substringBefore('?')
            val fragment = raw.substringAfter('#', "").substringBefore('?').takeIf { it.startsWith("/") }.orEmpty()
            return (if (fragment.isEmpty()) path else path.trimEnd('/') + "/#" + fragment).split('/')
                .joinToString("/") { seg -> if (seg.count(Char::isDigit) >= 3) ":n" else seg.lowercase() }
                .trimEnd('/')
        }

        /** Seletor aceitável para aprender: curto, sem ids gerados (mat-input-12, cdk-…, ng-…). */
        fun isStableCss(css: String?): Boolean {
            if (css.isNullOrBlank() || css.length > 300) return false
            val generated = Regex("""#(mat-|cdk-|ng-|mat_|ui-id-|p-)[\w-]*\d""")
            return !generated.containsMatchIn(css)
        }
    }
}
