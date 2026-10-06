package com.licitaia.domain.auth

import com.licitaia.domain.model.AuthProvider

/**
 * Identidade verificada por um provedor externo (hoje: Google via Credential Manager).
 * Contém apenas claims públicas — nunca o ID token, que não deve ser persistido nem logado.
 */
data class GoogleIdentity(
    /** Claim `sub`: identificador estável da conta Google. */
    val subject: String,
    val email: String,
    val emailVerified: Boolean,
    val name: String,
)

/** A identidade foi reconhecida, mas o usuário ainda não tem nenhuma empresa vinculada. */
class NoCompanyAccessException(val email: String) :
    Exception("Sua conta foi identificada, mas ainda não tem acesso a nenhuma empresa.")

/** O e-mail pertence a uma conta de demonstração; contas reais não se misturam com dados demo. */
class DemoAccountConflictException(val email: String) :
    Exception("Este e-mail pertence à conta de demonstração e não pode ser usado com login Google.")

/** Encerra a sessão no provedor externo (ex.: limpa o estado de credencial do Google). */
interface IdentitySignOut {
    suspend fun signOut(provider: AuthProvider)
}
