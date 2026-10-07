package com.licitaia.core.ai

import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AiProviderType.ANTHROPIC
import com.licitaia.domain.model.AiProviderType.CUSTOM
import com.licitaia.domain.model.AiProviderType.GEMINI
import com.licitaia.domain.model.AiProviderType.MOCK
import com.licitaia.domain.model.AiProviderType.OPENAI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveAiResolverTest {

    private suspend fun resolve(preferred: AiProviderType?, global: AiProviderType?, vararg configured: AiProviderType) =
        ActiveAiResolver.resolve(preferred, global) { it in configured }

    @Test
    fun `conta ChatGPT logada vale mesmo com o global padrao MOCK`() = runTest {
        // Causa raiz do "Análise heurística" com ChatGPT conectado: global = MOCK encerrava a busca.
        assertEquals(OPENAI, resolve(null, MOCK, OPENAI))
        assertEquals(OPENAI, resolve(null, null, OPENAI))
    }

    @Test
    fun `chave de API configurada vale quando o global aponta para provedor sem credencial`() = runTest {
        assertEquals(ANTHROPIC, resolve(null, GEMINI, ANTHROPIC))
        assertEquals(CUSTOM, resolve(null, OPENAI, CUSTOM))
    }

    @Test
    fun `global configurado tem prioridade sobre o fallback`() = runTest {
        assertEquals(GEMINI, resolve(null, GEMINI, OPENAI, GEMINI))
    }

    @Test
    fun `preferencia explicita da empresa vale se disponivel`() = runTest {
        assertEquals(ANTHROPIC, resolve(ANTHROPIC, OPENAI, OPENAI, ANTHROPIC))
        // Preferida sem credencial: cai no global.
        assertEquals(OPENAI, resolve(ANTHROPIC, OPENAI, OPENAI))
    }

    @Test
    fun `demonstracao sempre heuristica mesmo com credenciais`() = runTest {
        assertEquals(MOCK, resolve(MOCK, OPENAI, OPENAI, ANTHROPIC, GEMINI))
    }

    @Test
    fun `nenhum provedor real disponivel cai na heuristica`() = runTest {
        assertEquals(MOCK, resolve(null, MOCK))
        assertEquals(MOCK, resolve(GEMINI, OPENAI))
    }

    @Test
    fun `fallback segue a ordem fixa`() = runTest {
        assertEquals(OPENAI, resolve(null, MOCK, CUSTOM, GEMINI, OPENAI))
        assertEquals(GEMINI, resolve(null, MOCK, CUSTOM, GEMINI))
    }

    @Test
    fun `escopo de empresa vai no contexto da coroutine`() = runTest {
        val inside = withAiCompany(42L) { currentCoroutineContext()[AiCompanyScope]?.companyId }
        assertEquals(42L, inside)
        assertNull(withAiCompany(0L) { currentCoroutineContext()[AiCompanyScope] })
    }
}
