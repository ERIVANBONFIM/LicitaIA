package com.licitaia.feature.auth.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** RS256 with Google's official rotating public keys. Offline verification fails closed. */
object GoogleTokenVerifier {
    suspend fun verify(token: String): GoogleIdTokenClaims = withContext(Dispatchers.IO) {
        require(token.length <= 32768) { "Credencial Google inválida." }
        val parts = token.split('.')
        require(parts.size == 3) { "Credencial Google inválida." }
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(String(decoder.decode(parts[0]), Charsets.UTF_8)).jsonObject
        require(header["alg"]?.jsonPrimitive?.content == "RS256") { "Algoritmo Google inválido." }
        val kid = header["kid"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Token sem chave pública.")
        val connection = URL("https://www.googleapis.com/oauth2/v3/certs").openConnection() as HttpsURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = false
        val keys = try {
            require(connection.responseCode == 200) { "Não foi possível verificar a identidade Google. Tente com internet." }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            require(body.length < 100000) { "Resposta de verificação inválida." }
            Json.parseToJsonElement(body).jsonObject["keys"]!!.jsonArray
        } finally { connection.disconnect() }
        verifyWithKeys(token, keys)
    }

    internal fun verifyWithKeys(token: String, keys: JsonArray): GoogleIdTokenClaims {
        val parts = token.split('.')
        require(parts.size == 3 && token.length <= 32768)
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(String(decoder.decode(parts[0]), Charsets.UTF_8)).jsonObject
        require(header["alg"]?.jsonPrimitive?.content == "RS256")
        val kid = header["kid"]?.jsonPrimitive?.content
        val key = keys.map { it.jsonObject }.firstOrNull { it["kid"]?.jsonPrimitive?.content == kid }
            ?: throw IllegalArgumentException("Chave Google desconhecida. Entre novamente.")
        require(key["kty"]?.jsonPrimitive?.content == "RSA")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decoder.decode(key["n"]!!.jsonPrimitive.content)),
            BigInteger(1, decoder.decode(key["e"]!!.jsonPrimitive.content)),
        ))
        val valid = Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verify(decoder.decode(parts[2]))
        }
        require(valid) { "Assinatura da identidade Google inválida." }
        return GoogleIdTokenClaims.parse(token)
    }
}
