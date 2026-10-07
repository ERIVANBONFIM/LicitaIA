package com.licitaia.core.ai

import com.licitaia.ai.mock.MockAIProvider
import com.licitaia.core.security.SecretStore
import com.licitaia.domain.model.AiAuthMode
import com.licitaia.domain.model.AiProviderType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Provedor ativo de TODO o app resolvido pelo gateway real, com credenciais de verdade (cofre/conta) simuladas:
 * conta ChatGPT (OAuth), chave de API, preferência da empresa, demonstração e nenhum provedor.
 */
class DefaultAiGatewayTest {

    private val settings = mockk<AiSettingsSource>()
    private val secrets = mockk<SecretStore>()
    private val chatGpt = mockk<ChatGptAuthorizer>()
    private val google = mockk<GoogleAiAuthorizer>()
    private val mock = MockAIProvider()
    private val openAi = mockk<OpenAiProvider> { every { type } returns AiProviderType.OPENAI }
    private val anthropic = mockk<AnthropicProvider> { every { type } returns AiProviderType.ANTHROPIC }
    private val gemini = mockk<GeminiProvider> { every { type } returns AiProviderType.GEMINI }
    private val custom = mockk<CustomProvider> { every { type } returns AiProviderType.CUSTOM }

    private val gateway = DefaultAiGateway(mock, openAi, anthropic, gemini, custom, settings, AiCredentials(settings, secrets, google, chatGpt))

    /** Cenário: [oauth] = provedores em modo conta (escopo 7); [keys] = provedores com chave no cofre (padrão do aparelho). */
    private fun scenario(
        preferred: AiProviderType? = null,
        active: AiProviderType = AiProviderType.MOCK,
        oauth: Map<AiProviderType, Boolean> = emptyMap(),
        keys: Set<AiProviderType> = emptySet(),
    ) {
        coEvery { settings.companyPreferredProvider() } returns preferred
        coEvery { settings.activeProvider() } returns active
        coEvery { settings.endpoint(any()) } answers {
            val type = firstArg<AiProviderType>()
            if (type in oauth) {
                AiEndpoint(type.defaultModel.ifBlank { "m" }, type.defaultBaseUrl, AiAuthMode.OAUTH, companyId = 7)
            } else {
                AiEndpoint(type.defaultModel.ifBlank { "m" }, type.defaultBaseUrl.ifBlank { "https://ia.exemplo.com.br/v1" }, AiAuthMode.API_KEY)
            }
        }
        coEvery { secrets.get(any()) } answers {
            val key = firstArg<String>()
            if (keys.any { key == AiSecretKeys.apiKey(it) }) "sk-teste" else null
        }
        coEvery { chatGpt.hasAuthorization(any()) } answers { oauth[AiProviderType.OPENAI] == true && firstArg<Long>() == 7L }
        coEvery { google.hasAuthorization(any()) } answers { oauth[AiProviderType.GEMINI] == true && firstArg<Long>() == 7L }
    }

    @Test
    fun `conta ChatGPT conectada e usada mesmo com o provedor global ainda em MOCK`() = runTest {
        scenario(oauth = mapOf(AiProviderType.OPENAI to true))
        assertEquals(AiProviderType.OPENAI, gateway.activeType())
        assertSame(openAi, gateway.current())
    }

    @Test
    fun `chave de API e usada quando o global aponta para provedor sem credencial`() = runTest {
        scenario(active = AiProviderType.GEMINI, keys = setOf(AiProviderType.ANTHROPIC))
        assertSame(anthropic, gateway.current())
    }

    @Test
    fun `modo conta sem conta autorizada nao conta como configurado`() = runTest {
        scenario(active = AiProviderType.OPENAI, oauth = mapOf(AiProviderType.OPENAI to false), keys = setOf(AiProviderType.CUSTOM))
        assertSame(custom, gateway.current())
    }

    @Test
    fun `preferencia explicita da empresa vence o global quando disponivel`() = runTest {
        scenario(preferred = AiProviderType.GEMINI, active = AiProviderType.OPENAI, oauth = mapOf(AiProviderType.OPENAI to true), keys = setOf(AiProviderType.GEMINI))
        assertSame(gemini, gateway.current())
        // Preferida sem credencial: usa o global (conta ChatGPT).
        scenario(preferred = AiProviderType.ANTHROPIC, active = AiProviderType.OPENAI, oauth = mapOf(AiProviderType.OPENAI to true))
        assertSame(openAi, gateway.current())
    }

    @Test
    fun `demonstracao sempre usa a heuristica local`() = runTest {
        scenario(preferred = AiProviderType.MOCK, active = AiProviderType.OPENAI, oauth = mapOf(AiProviderType.OPENAI to true), keys = setOf(AiProviderType.ANTHROPIC))
        assertSame(mock, gateway.current())
    }

    @Test
    fun `sem nenhum provedor real a heuristica e usada`() = runTest {
        scenario(active = AiProviderType.OPENAI)
        assertEquals(AiProviderType.MOCK, gateway.activeType())
        assertSame(mock, gateway.current())
    }
}
