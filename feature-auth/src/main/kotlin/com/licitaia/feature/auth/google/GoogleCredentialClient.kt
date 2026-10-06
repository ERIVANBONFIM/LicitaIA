package com.licitaia.feature.auth.google

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.licitaia.domain.auth.GoogleIdentity
import com.licitaia.domain.auth.IdentitySignOut
import com.licitaia.domain.model.AuthProvider
import com.licitaia.feature.auth.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GoogleSignInResult {
    data class Success(val identity: GoogleIdentity) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data object NoAccount : GoogleSignInResult
    data object NotConfigured : GoogleSignInResult
    data class Failure(val message: String) : GoogleSignInResult
}

/**
 * "Entrar com Google" via Credential Manager (Sign in with Google). Pede SOMENTE o ID token —
 * nenhum escopo de Gmail, Drive ou contatos. O token nunca é persistido nem logado: dele extraímos
 * apenas `sub`, e-mail, nome e `email_verified`, após verificar RS256 com JWKS oficial,
 * audiência, nonce, emissor e expiração.
 */
@Singleton
class GoogleCredentialClient @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : IdentitySignOut {

    val isConfigured: Boolean get() = SERVER_CLIENT_ID.isNotBlank()

    /** [activityContext] precisa ser a Activity visível (o seletor de contas é uma UI do sistema). */
    suspend fun signIn(activityContext: Context): GoogleSignInResult {
        if (!isConfigured) return GoogleSignInResult.NotConfigured
        val nonce = newNonce()
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(SERVER_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        return try {
            val response = CredentialManager.create(activityContext).getCredential(activityContext, request)
            val credential = response.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return GoogleSignInResult.Failure("O Google devolveu um tipo de credencial inesperado.")
            }
            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val claims = GoogleTokenVerifier.verify(googleCredential.idToken)
            claims.validate(expectedAudience = SERVER_CLIENT_ID, expectedNonce = nonce)
            val email = requireNotNull(claims.email) { "Token Google sem e-mail." }.trim().lowercase()
            GoogleSignInResult.Success(
                GoogleIdentity(
                    subject = claims.subject,
                    email = email,
                    emailVerified = claims.emailVerified,
                    name = googleCredential.displayName?.takeIf { it.isNotBlank() } ?: claims.name ?: email.substringBefore('@'),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleSignInResult.NoAccount
        } catch (e: GetCredentialInterruptedException) {
            GoogleSignInResult.Failure("A solicitação foi interrompida. Tente novamente.")
        } catch (e: GetCredentialProviderConfigurationException) {
            GoogleSignInResult.Failure("O Google Play Services não está disponível ou atualizado neste aparelho.")
        } catch (e: GetCredentialException) {
            // DEVELOPER_ERROR / código 10: o par (pacote, SHA-1 do certificado de assinatura) deste build não está
            // cadastrado no projeto Google Cloud do Web Client ID. Mensagem acionável, sem dados do token.
            if (isDeveloperError(e)) {
                GoogleSignInResult.Failure(DEVELOPER_ERROR_MESSAGE)
            } else {
                // Mensagem genérica: o tipo do erro é útil para diagnóstico, nunca inclui o token.
                GoogleSignInResult.Failure("Não foi possível entrar com o Google (${e.type.substringAfterLast('.')}).")
            }
        } catch (e: GoogleIdTokenParsingException) {
            GoogleSignInResult.Failure("A credencial do Google não pôde ser lida.")
        } catch (e: java.io.IOException) {
            GoogleSignInResult.Failure("Não foi possível verificar a assinatura Google. Verifique a internet e tente novamente.")
        } catch (e: IllegalArgumentException) {
            GoogleSignInResult.Failure(e.message ?: "Credencial do Google inválida.")
        } catch (e: Exception) {
            GoogleSignInResult.Failure("Não foi possível verificar a credencial Google. Entre novamente.")
        }
    }

    /** Limpa o estado de credencial para que a próxima entrada mostre o seletor de contas. */
    override suspend fun signOut(provider: AuthProvider) {
        if (provider != AuthProvider.GOOGLE) return
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val SERVER_CLIENT_ID: String = BuildConfig.GOOGLE_SERVER_CLIENT_ID
        const val DEVELOPER_ERROR_MESSAGE = "Este build não está cadastrado no Google Cloud (pacote + SHA-1). Veja o README."

        /** Código 10 isolado (`10:`, `[10]`, `code 10`, `status 10`) ou texto DEVELOPER_ERROR / 28444 (console não configurado). */
        private val CODE_10 = Regex("""(^|[\s\[(:=])10([\s\]):,.]|$)""")

        fun isDeveloperError(e: GetCredentialException): Boolean {
            val text = listOfNotNull(e.type, e.message, e.errorMessage?.toString()).joinToString(" ")
            return text.contains("DEVELOPER_ERROR", ignoreCase = true) ||
                text.contains("28444") ||
                text.contains("Developer console is not set up correctly", ignoreCase = true) ||
                CODE_10.containsMatchIn(text)
        }
    }
}


