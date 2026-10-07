package com.licitaia.core.ai

import com.licitaia.ai.api.AIProvider
import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.mock.MockAIProvider
import com.licitaia.domain.model.AiProviderType
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolve o provedor ativo de todo o app pela regra de [ActiveAiResolver]: preferido da empresa (se explícito e com
 * credencial) → provedor global (se com credencial) → qualquer provedor com conta logada ou chave → heurística local.
 * As credenciais são resolvidas por [AiCredentials] na empresa ativa (ou na de [AiCompanyScope]), com fallback para o
 * padrão do aparelho. A empresa de demonstração "prefere" MOCK: nunca gasta nem expõe uma chave real.
 */
@Singleton
class DefaultAiGateway @Inject constructor(
    private val mock: MockAIProvider,
    private val openAi: OpenAiProvider,
    private val anthropic: AnthropicProvider,
    private val gemini: GeminiProvider,
    private val custom: CustomProvider,
    private val settings: AiSettingsSource,
    private val credentials: AiCredentials,
) : AiGateway {

    override suspend fun current(): AIProvider = provider(activeType())

    override suspend fun activeType(): AiProviderType = ActiveAiResolver.resolve(
        companyPreferred = runCatching { settings.companyPreferredProvider() }.getOrNull(),
        globalActive = runCatching { settings.activeProvider() }.getOrNull(),
        isConfigured = { type -> runCatching { credentials.isConfigured(type) }.getOrDefault(false) },
    )

    override fun provider(type: AiProviderType): AIProvider = when (type) {
        AiProviderType.MOCK -> mock
        AiProviderType.OPENAI -> openAi
        AiProviderType.ANTHROPIC -> anthropic
        AiProviderType.GEMINI -> gemini
        AiProviderType.CUSTOM -> custom
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {
    @Binds
    @Singleton
    abstract fun bindAiGateway(impl: DefaultAiGateway): AiGateway

    companion object {
        @Provides
        @Singleton
        fun provideMockAiProvider(): MockAIProvider = MockAIProvider()
    }
}
