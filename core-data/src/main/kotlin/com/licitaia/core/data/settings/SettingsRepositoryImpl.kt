package com.licitaia.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private object Keys {
    val captchaRepeat = intPreferencesKey("captcha_repeat_minutes")
    val captchaVibrate = booleanPreferencesKey("captcha_vibrate")
    val haptic = booleanPreferencesKey("haptic_feedback")
    val push = booleanPreferencesKey("push_enabled")
    val biometric = booleanPreferencesKey("biometric_lock")
    val sessionTimeout = intPreferencesKey("session_timeout_minutes")
    val screenshot = booleanPreferencesKey("screenshot_protection")
    val remember = booleanPreferencesKey("remember_session")
    val activeAi = stringPreferencesKey("active_ai_provider")
    val bottomBar = booleanPreferencesKey("show_bottom_bar")
    // "Manter sessão ativa" dos portais (opt-in por empresa+portal) e intervalo em minutos.
    val portalKeepAliveMinutes = intPreferencesKey("portal_keep_alive_minutes")
    val portalKeepAlive = stringSetPreferencesKey("portal_keep_alive")
    // "Entrar automaticamente com certificado digital" (opt-in por empresa+portal).
    val portalAutoCertLogin = stringSetPreferencesKey("portal_auto_cert_login")

    val rememberedUser = longPreferencesKey("session_user_id")
    val rememberedCompany = longPreferencesKey("session_company_id")

    // Atualização via GitHub Releases (ver app/.../update).
    val updateLastCheckAt = longPreferencesKey("update_last_check_at")
    val updateSkippedTag = stringPreferencesKey("update_skipped_tag")
    val updateSkippedUntil = longPreferencesKey("update_skipped_until")
}

private fun Preferences.toSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        captchaRepeatMinutes = this[Keys.captchaRepeat] ?: defaults.captchaRepeatMinutes,
        captchaVibrate = this[Keys.captchaVibrate] ?: defaults.captchaVibrate,
        hapticFeedback = this[Keys.haptic] ?: defaults.hapticFeedback,
        pushEnabled = this[Keys.push] ?: defaults.pushEnabled,
        biometricLock = this[Keys.biometric] ?: defaults.biometricLock,
        sessionTimeoutMinutes = this[Keys.sessionTimeout] ?: defaults.sessionTimeoutMinutes,
        screenshotProtection = this[Keys.screenshot] ?: defaults.screenshotProtection,
        rememberSession = this[Keys.remember] ?: defaults.rememberSession,
        activeAiProvider = this[Keys.activeAi]?.let { name -> AiProviderType.entries.firstOrNull { it.name == name } }
            ?: defaults.activeAiProvider,
        showBottomBar = this[Keys.bottomBar] ?: defaults.showBottomBar,
        portalKeepAliveMinutes = (this[Keys.portalKeepAliveMinutes] ?: defaults.portalKeepAliveMinutes)
            .takeIf { it in AppSettings.PORTAL_KEEP_ALIVE_OPTIONS } ?: defaults.portalKeepAliveMinutes,
        portalKeepAlive = this[Keys.portalKeepAlive] ?: defaults.portalKeepAlive,
        portalAutoCertLogin = this[Keys.portalAutoCertLogin] ?: defaults.portalAutoCertLogin,
    )
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.captchaRepeat] = next.captchaRepeatMinutes
            prefs[Keys.captchaVibrate] = next.captchaVibrate
            prefs[Keys.haptic] = next.hapticFeedback
            prefs[Keys.push] = next.pushEnabled
            prefs[Keys.biometric] = next.biometricLock
            prefs[Keys.sessionTimeout] = next.sessionTimeoutMinutes
            prefs[Keys.screenshot] = next.screenshotProtection
            prefs[Keys.remember] = next.rememberSession
            prefs[Keys.activeAi] = next.activeAiProvider.name
            prefs[Keys.bottomBar] = next.showBottomBar
            prefs[Keys.portalKeepAliveMinutes] = next.portalKeepAliveMinutes
            prefs[Keys.portalKeepAlive] = next.portalKeepAlive
            prefs[Keys.portalAutoCertLogin] = next.portalAutoCertLogin
        }
    }
}

/** Sessão lembrada (ids apenas; nenhuma credencial). */
@Singleton
class SessionPrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    data class Remembered(val userId: Long, val companyId: Long)

    suspend fun read(): Remembered? {
        val prefs = dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
        val user = prefs[Keys.rememberedUser] ?: return null
        val company = prefs[Keys.rememberedCompany] ?: return null
        return Remembered(user, company)
    }

    suspend fun save(userId: Long, companyId: Long) {
        dataStore.edit {
            it[Keys.rememberedUser] = userId
            it[Keys.rememberedCompany] = companyId
        }
    }

    suspend fun updateCompany(companyId: Long) {
        dataStore.edit { prefs ->
            if (prefs.contains(Keys.rememberedUser)) prefs[Keys.rememberedCompany] = companyId
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(Keys.rememberedUser)
            it.remove(Keys.rememberedCompany)
        }
    }
}

/**
 * Política de verificação de atualizações (sem credenciais): instante da última checagem bem-sucedida
 * e tag adiada pelo usuário em "Depois", com validade.
 */
@Singleton
class UpdatePrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    data class Skipped(val tag: String, val until: Long)

    private suspend fun prefs(): Preferences = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .first()

    suspend fun lastCheckAt(): Long? = prefs()[Keys.updateLastCheckAt]

    suspend fun markChecked(at: Long = System.currentTimeMillis()) {
        dataStore.edit { it[Keys.updateLastCheckAt] = at }
    }

    suspend fun skipped(): Skipped? {
        val p = prefs()
        val tag = p[Keys.updateSkippedTag] ?: return null
        val until = p[Keys.updateSkippedUntil] ?: return null
        return Skipped(tag, until)
    }

    suspend fun skip(tag: String, until: Long) {
        dataStore.edit {
            it[Keys.updateSkippedTag] = tag
            it[Keys.updateSkippedUntil] = until
        }
    }

    suspend fun clearSkip() {
        dataStore.edit {
            it.remove(Keys.updateSkippedTag)
            it.remove(Keys.updateSkippedUntil)
        }
    }
}
