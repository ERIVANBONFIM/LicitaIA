package com.licitaia.feature.auth.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GoogleIdTokenClaimsTest {

    private fun token(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        return "$header.${enc.encodeToString(payload.toByteArray())}.assinatura-falsa"
    }

    private val valid = token(
        """{"iss":"https://accounts.google.com","sub":"1234567890","aud":"web-client-id","email":"ana@example.com",
           "email_verified":true,"name":"Ana Lima","nonce":"abc","exp":4102444800}""",
    )

    @Test
    fun `parse extrai claims publicas`() {
        val c = GoogleIdTokenClaims.parse(valid)
        assertEquals("1234567890", c.subject)
        assertEquals("ana@example.com", c.email)
        assertTrue(c.emailVerified)
        assertEquals("Ana Lima", c.name)
        assertEquals(listOf("web-client-id"), c.audiences)
        assertEquals("abc", c.nonce)
    }

    @Test
    fun `validate aceita audiencia e nonce corretos`() {
        GoogleIdTokenClaims.parse(valid).validate("web-client-id", "abc", nowSeconds = 1_700_000_000)
    }

    @Test
    fun `validate rejeita audiencia de outro app`() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleIdTokenClaims.parse(valid).validate("outro-client-id", "abc", nowSeconds = 1_700_000_000)
        }
    }

    @Test
    fun `validate rejeita nonce diferente (replay)`() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleIdTokenClaims.parse(valid).validate("web-client-id", "xyz", nowSeconds = 1_700_000_000)
        }
    }

    @Test
    fun `validate rejeita token expirado`() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleIdTokenClaims.parse(valid).validate("web-client-id", "abc", nowSeconds = 4_102_444_801)
        }
    }

    @Test
    fun `aud em array e email_verified como string sao aceitos`() {
        val c = GoogleIdTokenClaims.parse(
            token("""{"sub":"1","aud":["a","web-client-id"],"email_verified":"false","nonce":"n"}"""),
        )
        assertEquals(listOf("a", "web-client-id"), c.audiences)
        assertFalse(c.emailVerified)
        assertThrows(IllegalArgumentException::class.java) { c.validate("web-client-id", "n", nowSeconds = 0) }
    }

    @Test
    fun `token malformado lanca excecao`() {
        assertThrows(IllegalArgumentException::class.java) { GoogleIdTokenClaims.parse("nao.e.um.jwt.valido") }
    }
}

