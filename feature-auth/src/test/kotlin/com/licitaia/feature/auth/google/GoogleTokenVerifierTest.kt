package com.licitaia.feature.auth.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class GoogleTokenVerifierTest {
    @Test fun `accepts signed token and rejects changed payload or unknown key`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = pair.public as RSAPublicKey
        val enc = Base64.getUrlEncoder().withoutPadding()
        fun encoded(value: String) = enc.encodeToString(value.toByteArray())
        val keys = Json.parseToJsonElement("""[{"kid":"test","kty":"RSA","n":"${enc.encodeToString(key.modulus.toByteArray())}","e":"${enc.encodeToString(key.publicExponent.toByteArray())}"}]""").jsonArray
        val input = "${encoded("""{"kid":"test","alg":"RS256"}""")}.${encoded("""{"sub":"123","iss":"accounts.google.com","exp":4102444800}""")}"
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(input.toByteArray()); enc.encodeToString(sign()) }
        val token = "$input.$signature"
        assertEquals("123", GoogleTokenVerifier.verifyWithKeys(token, keys).subject)
        val altered = input.substringBefore('.') + "." + encoded("""{"sub":"attacker"}""") + ".$signature"
        assertThrows(IllegalArgumentException::class.java) { GoogleTokenVerifier.verifyWithKeys(altered, keys) }
        assertThrows(IllegalArgumentException::class.java) { GoogleTokenVerifier.verifyWithKeys(token, Json.parseToJsonElement("[]").jsonArray) }
    }
}
