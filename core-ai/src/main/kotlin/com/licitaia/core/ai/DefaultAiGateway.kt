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
 * Resolve o provedor: preferido da empresa ativa → provedor global ativo → demonstração.
 * Um provedor sem credencial (chave de API, ou conta Google autorizada no modo OAuth) nunca é
 * escolhido, então o app funciona sem credenciais.
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

    override suspend fun current(): AIProvider {
        val candidates = listOfNotNull(
            runCatching { settings.companyPreferredProvider() }.getOrNull(),
            runCatching { settings.activeProvider() }.getOrNull(),
        ).distinct()
        for (type in candidates) {
            if (type == AiProviderType.MOCK) return mock
            if (runCatching { credentials.isConfigured(type) }.getOrDefault(false)) return provider(type)
        }
        return mock
    }

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
