package com.licitaia.domain.bidding

import com.licitaia.domain.model.BidStrategy
import kotlinx.coroutines.flow.Flow

/** Como o decremento mínimo por lance é expresso. */
enum class DecrementMode(val label: String) {
    VALOR("Valor (R$)"),
    PERCENTUAL("Percentual (%)"),
}

/**
 * Configuração PADRÃO do robô de lances da empresa (tela Estratégias). O motor de lances lê esta configuração ao
 * montar a regra de cada sessão; ajustes por licitação (piso, custo) continuam na configuração da sessão.
 */
data class BidStrategyConfig(
    val strategy: BidStrategy = BidStrategy.CONSERVADORA,
    val decrementMode: DecrementMode = DecrementMode.VALOR,
    /** Decremento mínimo por lance em R$ (quando [decrementMode] = VALOR). */
    val decrementValue: Double = 1.0,
    /** Decremento mínimo por lance em % do nosso último lance (quando [decrementMode] = PERCENTUAL). */
    val decrementPct: Double = 0.5,
    /** Margem mínima (%) sobre o custo: o robô nunca propõe lance que fique abaixo dela. */
    val minMarginPct: Double = 5.0,
    /** Só reage quando perdemos a 1ª posição (não cobre o próprio lance). */
    val reactOnlyWhenLosingFirst: Boolean = true,
    /** Dá um lance final nos últimos segundos da disputa (fase aleatória/iminência). */
    val finalBidEnabled: Boolean = false,
    /** Quantos segundos antes do fim o lance final é dado. */
    val finalBidSecondsBefore: Int = 10,
    /** Intervalo mínimo entre lances próprios (s). O Compras.gov.br exige 20 s entre lances do mesmo fornecedor. */
    val minIntervalSeconds: Int = 20,
    /** Pede autorização humana quando o próximo lance ficar a menos de X% do piso. */
    val authorizationThresholdPct: Double = 5.0,
    val updatedAt: Long = 0L,
    val updatedBy: String = "",
) {
    /** Mensagens de validação em PT-BR; vazio = válida. */
    fun validate(): List<String> = buildList {
        when (decrementMode) {
            DecrementMode.VALOR -> if (decrementValue.isNaN() || decrementValue <= 0.0) add("Informe um decremento mínimo maior que R$ 0,00.")
            DecrementMode.PERCENTUAL -> if (decrementPct.isNaN() || decrementPct <= 0.0 || decrementPct > 20.0) add("O decremento percentual deve ficar entre 0,01% e 20%.")
        }
        if (minMarginPct.isNaN() || minMarginPct < 0.0 || minMarginPct > 90.0) add("A margem mínima deve ficar entre 0% e 90%.")
        if (finalBidEnabled && finalBidSecondsBefore !in 2..120) add("O lance final deve ficar entre 2 e 120 segundos antes do fim.")
        if (minIntervalSeconds !in 1..600) add("O intervalo entre lances deve ficar entre 1 e 600 segundos.")
        if (authorizationThresholdPct.isNaN() || authorizationThresholdPct < 0.0 || authorizationThresholdPct > 50.0) add("O alerta de autorização deve ficar entre 0% e 50% do piso.")
    }

    val isValid: Boolean get() = validate().isEmpty()

    /** Decremento em R$ a partir do valor de referência (nosso último lance ou o melhor lance). */
    fun decrementFor(referencePrice: Double): Double = when (decrementMode) {
        DecrementMode.VALOR -> decrementValue
        DecrementMode.PERCENTUAL -> if (referencePrice <= 0.0) 0.0 else referencePrice * decrementPct / 100.0
    }
}

/**
 * Configuração padrão do robô de lances por empresa (DataStore). Contrato lido pelo motor de lances.
 * Gravação exige a permissão "Alterar regras do robô".
 */
interface BidStrategyConfigRepository {
    fun observe(companyId: Long): Flow<BidStrategyConfig>
    suspend fun get(companyId: Long): BidStrategyConfig
    suspend fun save(companyId: Long, config: BidStrategyConfig)
}

/** Serialização simples "chave=valor" (sem dependências), usada na persistência. Pura e testável. */
object BidStrategyConfigCodec {
    private const val SEP = "\n"

    fun encode(c: BidStrategyConfig): String = listOf(
        "strategy" to c.strategy.name,
        "decrementMode" to c.decrementMode.name,
        "decrementValue" to c.decrementValue.toString(),
        "decrementPct" to c.decrementPct.toString(),
        "minMarginPct" to c.minMarginPct.toString(),
        "reactOnlyWhenLosingFirst" to c.reactOnlyWhenLosingFirst.toString(),
        "finalBidEnabled" to c.finalBidEnabled.toString(),
        "finalBidSecondsBefore" to c.finalBidSecondsBefore.toString(),
        "minIntervalSeconds" to c.minIntervalSeconds.toString(),
        "authorizationThresholdPct" to c.authorizationThresholdPct.toString(),
        "updatedAt" to c.updatedAt.toString(),
        "updatedBy" to c.updatedBy.replace("\n", " ").replace("=", " "),
    ).joinToString(SEP) { (k, v) -> "$k=$v" }

    /** Valores ausentes/inválidos caem no padrão (configuração antiga continua legível). */
    fun decode(text: String?): BidStrategyConfig {
        val d = BidStrategyConfig()
        if (text.isNullOrBlank()) return d
        val map = text.split(SEP).mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1)
        }.toMap()
        fun dbl(k: String, def: Double) = map[k]?.toDoubleOrNull()?.takeIf { !it.isNaN() } ?: def
        fun int(k: String, def: Int) = map[k]?.toIntOrNull() ?: def
        fun bool(k: String, def: Boolean) = map[k]?.toBooleanStrictOrNull() ?: def
        return BidStrategyConfig(
            strategy = map["strategy"]?.let { s -> BidStrategy.entries.firstOrNull { it.name == s } } ?: d.strategy,
            decrementMode = map["decrementMode"]?.let { s -> DecrementMode.entries.firstOrNull { it.name == s } } ?: d.decrementMode,
            decrementValue = dbl("decrementValue", d.decrementValue),
            decrementPct = dbl("decrementPct", d.decrementPct),
            minMarginPct = dbl("minMarginPct", d.minMarginPct),
            reactOnlyWhenLosingFirst = bool("reactOnlyWhenLosingFirst", d.reactOnlyWhenLosingFirst),
            finalBidEnabled = bool("finalBidEnabled", d.finalBidEnabled),
            finalBidSecondsBefore = int("finalBidSecondsBefore", d.finalBidSecondsBefore),
            minIntervalSeconds = int("minIntervalSeconds", d.minIntervalSeconds),
            authorizationThresholdPct = dbl("authorizationThresholdPct", d.authorizationThresholdPct),
            updatedAt = map["updatedAt"]?.toLongOrNull() ?: 0L,
            updatedBy = map["updatedBy"].orEmpty(),
        )
    }
}
