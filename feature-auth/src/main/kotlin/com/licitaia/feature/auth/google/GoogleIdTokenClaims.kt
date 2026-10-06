package com.licitaia.feature.auth.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** Public claims. Only GoogleTokenVerifier establishes signature authenticity before use. */
data class GoogleIdTokenClaims(
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val name: String?,
    val audiences: List<String>,
    val nonce: String?,
    val expiresAtSeconds: Long?,
    val issuer: String?,
) {
    fun validate(expectedAudience: String, expectedNonce: String, nowSeconds: Long = System.currentTimeMillis() / 1000) {
        require(subject.isNotBlank()) { "Token sem identificador de usuário." }
        require(issuer in VALID_ISSUERS) { "Emissor do token inválido." }
        require(expectedAudience in audiences) { "O token não foi emitido para este aplicativo." }
        require(nonce == expectedNonce) { "Nonce do token não confere." }
        require(expiresAtSeconds != null && expiresAtSeconds > nowSeconds) { "Token expirado." }
    }

    companion object {
        private val VALID_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
        private val json = Json { ignoreUnknownKeys = true }

        /** Decodifica o payload do JWT (Base64URL). Lança [IllegalArgumentException] se malformado. */
        fun parse(idToken: String): GoogleIdTokenClaims {
            val parts = idToken.split('.')
            require(parts.size == 3) { "Formato de token inválido." }
            val payload = String(Base64.getUrlDecoder().decode(padBase64(parts[1])), Charsets.UTF_8)
            val obj = json.parseToJsonElement(payload).jsonObject
            val aud = obj["aud"]?.let { element ->
                runCatching { element.jsonArray.map { it.jsonPrimitive.content } }
                    .getOrElse { listOf(element.jsonPrimitive.content) }
            }.orEmpty()
            return GoogleIdTokenClaims(
                subject = obj["sub"]?.jsonPrimitive?.content.orEmpty(),
                email = obj["email"]?.jsonPrimitive?.content,
                emailVerified = obj["email_verified"]?.jsonPrimitive?.let { it.booleanOrNull ?: (it.content == "true") } ?: false,
                name = obj["name"]?.jsonPrimitive?.content,
                audiences = aud,
                nonce = obj["nonce"]?.jsonPrimitive?.content,
                expiresAtSeconds = obj["exp"]?.jsonPrimitive?.longOrNull,
                issuer = obj["iss"]?.jsonPrimitive?.content,
            )
        }

        private fun padBase64(s: String): String = s + "=".repeat((4 - s.length % 4) % 4)
    }
}


