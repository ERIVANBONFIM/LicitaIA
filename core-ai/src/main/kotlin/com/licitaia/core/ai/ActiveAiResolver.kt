package com.licitaia.core.ai

import com.licitaia.domain.model.AiProviderType
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Regra única do "provedor ativo" usado em TODO o app (análise do edital, Pergunte ao edital, relevância do radar,
 * proposta, mensagens do pregoeiro...):
 *
 * 1. Demonstração ([companyPreferred] == MOCK, só devolvido para a empresa/usuário demo) → heurística local.
 * 2. Preferência explícita da empresa (não MOCK), se tiver credencial (chave de API ou conta conectada).
 * 3. Provedor global escolhido em Configurações > IA, se tiver credencial.
 * 4. Qualquer provedor real com credencial (conta logada OU chave), na ordem [FALLBACK_ORDER].
 * 5. Nenhum provedor real disponível → heurística local (MOCK), e a UI deve dizer isso.
 *
 * Antes, o global padrão era MOCK e a regra parava nele: com o ChatGPT conectado mas sem tocar em "Usar este
 * provedor", tudo caía na heurística. Agora uma conta/chave configurada sempre é usada.
 */
object ActiveAiResolver {
    val FALLBACK_ORDER = listOf(AiProviderType.OPENAI, AiProviderType.ANTHROPIC, AiProviderType.GEMINI, AiProviderType.CUSTOM)

    suspend fun resolve(
        companyPreferred: AiProviderType?,
        globalActive: AiProviderType?,
        isConfigured: suspend (AiProviderType) -> Boolean,
    ): AiProviderType {
        if (companyPreferred == AiProviderType.MOCK) return AiProviderType.MOCK
        val ordered = listOfNotNull(companyPreferred, globalActive?.takeIf { it != AiProviderType.MOCK }) + FALLBACK_ORDER
        for (type in ordered.distinct()) {
            if (isConfigured(type)) return type
        }
        return AiProviderType.MOCK
    }
}

/**
 * Empresa cujas credenciais/preferências de IA valem na coroutine atual. Necessário em trabalho de fundo (análise
 * retomada na abertura do app antes do login, workers): sem sessão, a resolução cairia no padrão do aparelho e não
 * acharia a conta ChatGPT/chave salva só para a empresa — era um dos caminhos que gerava análise heurística indevida.
 */
class AiCompanyScope(val companyId: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiCompanyScope>
}

/** Executa [block] resolvendo provedor/credenciais da IA para a empresa [companyId]. */
suspend fun <T> withAiCompany(companyId: Long, block: suspend () -> T): T =
    if (companyId <= AiSecretKeys.DEVICE_SCOPE) block() else withContext(AiCompanyScope(companyId)) { block() }
