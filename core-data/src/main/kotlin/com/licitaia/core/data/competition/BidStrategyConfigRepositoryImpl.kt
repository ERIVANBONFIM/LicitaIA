package com.licitaia.core.data.competition

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.licitaia.core.data.repository.RepositoryAccess
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.domain.bidding.BidStrategyConfig
import com.licitaia.domain.bidding.BidStrategyConfigCodec
import com.licitaia.domain.bidding.BidStrategyConfigRepository
import com.licitaia.domain.bidding.DecrementMode
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.security.Permission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Configuração padrão do robô de lances por empresa, no DataStore do app (chave `bid_strategy_config:<empresa>`). */
@Singleton
class BidStrategyConfigRepositoryImpl @Inject constructor(
    private val prefs: DataStore<Preferences>,
    private val access: RepositoryAccess,
    private val holder: SessionHolder,
    private val audit: AuditRepository,
) : BidStrategyConfigRepository {

    override fun observe(companyId: Long): Flow<BidStrategyConfig> =
        prefs.data.map { BidStrategyConfigCodec.decode(it[key(companyId)]) }.distinctUntilChanged()

    override suspend fun get(companyId: Long): BidStrategyConfig = observe(companyId).first()

    override suspend fun save(companyId: Long, config: BidStrategyConfig) {
        access.requireCompany(companyId, Permission.ALTERAR_REGRAS)
        val errors = config.validate()
        require(errors.isEmpty()) { errors.first() }
        val previous = get(companyId)
        val stamped = config.copy(updatedAt = System.currentTimeMillis(), updatedBy = holder.current?.user?.name.orEmpty())
        prefs.edit { it[key(companyId)] = BidStrategyConfigCodec.encode(stamped) }
        audit.record(
            AuditAction.MUDANCA_REGRA,
            previousValue = describe(previous),
            newValue = describe(stamped),
            details = "Configuração padrão do robô de lances (Estratégias)",
        )
    }

    private fun key(companyId: Long) = stringPreferencesKey("bid_strategy_config:$companyId")

    companion object {
        /** Resumo auditável (sem dados sensíveis). */
        fun describe(c: BidStrategyConfig): String = buildString {
            append(c.strategy.label)
            append(" · decremento ")
            append(
                when (c.decrementMode) {
                    DecrementMode.VALOR -> "R$ " + String.format(Locale.ROOT, "%.2f", c.decrementValue)
                    DecrementMode.PERCENTUAL -> String.format(Locale.ROOT, "%.2f%%", c.decrementPct)
                },
            )
            append(" · margem mín. ").append(String.format(Locale.ROOT, "%.1f%%", c.minMarginPct))
            if (c.reactOnlyWhenLosingFirst) append(" · só reage ao perder o 1º lugar")
            if (c.finalBidEnabled) append(" · lance final ${c.finalBidSecondsBefore}s antes")
            append(" · intervalo ${c.minIntervalSeconds}s")
        }
    }
}
