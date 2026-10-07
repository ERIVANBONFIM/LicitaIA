package com.licitaia.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.licitaia.core.data.repository.ProtectedOpportunitySource
import com.licitaia.domain.sync.DailySyncSettings
import com.licitaia.domain.sync.DailySyncStatus
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private object DailyKeys {
    val enabled = booleanPreferencesKey("daily_sync_enabled")
    val hour = intPreferencesKey("daily_sync_hour")
    val minute = intPreferencesKey("daily_sync_minute")
    val lastCompletedAt = longPreferencesKey("daily_sync_last_completed_at")
    val lastCleanupAt = longPreferencesKey("daily_cleanup_last_at")
    val lastVacuumAt = longPreferencesKey("daily_vacuum_last_at")
}

/**
 * Atualização diária das licitações no DataStore do app: ligada/desligada, horário (padrão 05:30), fim da última
 * sincronização completa, última limpeza e último VACUUM. [running] fica só em memória (Worker em andamento).
 */
@Singleton
class DailySyncPrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val data: Flow<Preferences> = dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    private val runningFlow = MutableStateFlow(false)

    val settings: Flow<DailySyncSettings> = data.map { it.toSettings() }.distinctUntilChanged()

    val status: Flow<DailySyncStatus> = combine(data, runningFlow) { p, running ->
        DailySyncStatus(lastCompletedAt = p[DailyKeys.lastCompletedAt], lastCleanupAt = p[DailyKeys.lastCleanupAt], running = running)
    }.distinctUntilChanged()

    suspend fun currentSettings(): DailySyncSettings = settings.first()

    suspend fun currentStatus(): DailySyncStatus = status.first()

    suspend fun lastVacuumAt(): Long? = data.first()[DailyKeys.lastVacuumAt]

    suspend fun update(transform: (DailySyncSettings) -> DailySyncSettings) {
        dataStore.edit { p ->
            val next = transform(p.toSettings())
            p[DailyKeys.enabled] = next.enabled
            p[DailyKeys.hour] = next.hour.coerceIn(0, 23)
            p[DailyKeys.minute] = next.minute.coerceIn(0, 59)
        }
    }

    fun setRunning(running: Boolean) {
        runningFlow.value = running
    }

    suspend fun markCompleted(at: Long) {
        dataStore.edit { it[DailyKeys.lastCompletedAt] = at }
    }

    suspend fun markCleanup(at: Long, vacuumed: Boolean) {
        dataStore.edit {
            it[DailyKeys.lastCleanupAt] = at
            if (vacuumed) it[DailyKeys.lastVacuumAt] = at
        }
    }

    private fun Preferences.toSettings(): DailySyncSettings {
        val d = DailySyncSettings()
        return DailySyncSettings(
            enabled = this[DailyKeys.enabled] ?: d.enabled,
            hour = (this[DailyKeys.hour] ?: d.hour).coerceIn(0, 23),
            minute = (this[DailyKeys.minute] ?: d.minute).coerceIn(0, 59),
        )
    }
}

/** Conjunto (possivelmente vazio) de fontes protegidas da limpeza diária; outros módulos contribuem com `@IntoSet`. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ListingMaintenanceModule {
    @Multibinds
    abstract fun protectedOpportunitySources(): Set<ProtectedOpportunitySource>
}
