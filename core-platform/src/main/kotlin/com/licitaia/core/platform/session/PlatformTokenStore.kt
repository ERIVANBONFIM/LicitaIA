package com.licitaia.core.platform.session

import com.licitaia.core.platform.net.UserDto
import com.licitaia.core.security.SecretStore
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guarda o JWT e o `user` da plataforma com segurança, reusando o [SecretStore] cifrado por Keystore
 * (o mesmo cofre das chaves de API/portal). Nada é gravado em texto puro.
 *
 * O contrato não tem refresh token: ao expirar (30 d) ou em 401, o cliente limpa e volta ao login.
 */
@Singleton
class PlatformTokenStore @Inject constructor(
    private val secrets: SecretStore,
    private val json: Json,
) {
    suspend fun save(token: String, user: UserDto) {
        secrets.put(KEY_TOKEN, token)
        secrets.put(KEY_USER, json.encodeToString(UserDto.serializer(), user))
    }

    /** Atualiza só o `user` (ex.: após `GET /auth/me`), mantendo o token. */
    suspend fun updateUser(user: UserDto) {
        secrets.put(KEY_USER, json.encodeToString(UserDto.serializer(), user))
    }

    suspend fun token(): String? = secrets.get(KEY_TOKEN)

    suspend fun user(): UserDto? = secrets.get(KEY_USER)?.let {
        runCatching { json.decodeFromString(UserDto.serializer(), it) }.getOrNull()
    }

    /** O `user` como foi gravado (JSON), para o site embutido no app entrar já logado. */
    suspend fun userJson(): String? = secrets.get(KEY_USER)

    suspend fun hasSession(): Boolean = !token().isNullOrBlank()

    suspend fun clear() {
        secrets.remove(KEY_TOKEN)
        secrets.remove(KEY_USER)
    }

    private companion object {
        const val KEY_TOKEN = "platform_jwt"
        const val KEY_USER = "platform_user"
    }
}
