package com.licitaia.core.ai

import com.licitaia.core.security.SecretStore

/**
 * Token de acesso OAuth de curta duração guardado no cofre (Keystore) junto com o instante de expiração.
 * O Google não devolve o `expires_in` pela API de autorização do Play Services, então assume-se o
 * prazo padrão (~1 h) com folga: [DEFAULT_TTL_MS]. Um token "vencido" ainda fica no cofre como prova
 * de que houve consentimento — a renovação silenciosa decide se ele é substituído ou descartado.
 */
internal class OAuthTokenCache(
    private val secrets: SecretStore,
    private val tokenKey: String,
    private val expiryKey: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Há autorização registrada (token presente, válido ou não). */
    suspend fun exists(): Boolean = secrets.contains(tokenKey)

    /** Token ainda utilizável, ou null se ausente/expirado (com margem de segurança). */
    suspend fun valid(): String? {
        val token = secrets.get(tokenKey)?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = secrets.get(expiryKey)?.toLongOrNull() ?: return null
        return token.takeUnless { isExpired(expiresAt, clock()) }
    }

    suspend fun store(token: String, ttlMs: Long = DEFAULT_TTL_MS) {
        require(token.isNotBlank()) { "Token vazio." }
        secrets.put(tokenKey, token)
        secrets.put(expiryKey, (clock() + ttlMs.coerceAtLeast(0)).toString())
    }

    /** Marca o token como expirado sem apagá-lo (ex.: servidor respondeu 401). */
    suspend fun invalidate() {
        if (secrets.contains(tokenKey)) secrets.put(expiryKey, "0")
    }

    suspend fun clear() {
        secrets.remove(tokenKey)
        secrets.remove(expiryKey)
    }

    companion object {
        /** Tokens de acesso Google duram ~3600 s; 55 min deixa folga para a chamada em curso. */
        const val DEFAULT_TTL_MS = 55L * 60 * 1000
        /** Margem antes do vencimento nominal em que o token já é tratado como expirado. */
        const val SKEW_MS = 60L * 1000

        fun isExpired(expiresAtMs: Long, nowMs: Long): Boolean = nowMs + SKEW_MS >= expiresAtMs
    }
}
