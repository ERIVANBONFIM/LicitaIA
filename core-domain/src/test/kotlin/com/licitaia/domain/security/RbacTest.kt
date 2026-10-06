package com.licitaia.domain.security

import com.licitaia.domain.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RbacTest {

    @Test
    fun `admin possui todas as permissoes`() {
        assertEquals(Permission.entries.toSet(), Rbac.permissionsOf(UserRole.ADMIN))
        Permission.entries.forEach { assertTrue(it.name, Rbac.can(UserRole.ADMIN, it)) }
    }

    @Test
    fun `diretoria aprova piso e envio mas nao opera sessoes`() {
        assertTrue(Rbac.can(UserRole.DIRETORIA, Permission.APROVAR_PISO))
        assertTrue(Rbac.can(UserRole.DIRETORIA, Permission.APROVAR_ENVIO))
        assertTrue(Rbac.can(UserRole.DIRETORIA, Permission.ALTERAR_REGRAS))
        assertTrue(Rbac.can(UserRole.DIRETORIA, Permission.VER_AUDITORIA))
        assertFalse(Rbac.can(UserRole.DIRETORIA, Permission.OPERAR_SESSOES))
        assertFalse(Rbac.can(UserRole.DIRETORIA, Permission.GERENCIAR_EMPRESAS))
        assertFalse(Rbac.can(UserRole.DIRETORIA, Permission.CONFIGURAR_IA))
    }

    @Test
    fun `licitacoes opera sessoes e responde mas nao aprova piso nem ve custos`() {
        assertTrue(Rbac.can(UserRole.LICITACOES, Permission.OPERAR_SESSOES))
        assertTrue(Rbac.can(UserRole.LICITACOES, Permission.RESPONDER_MENSAGENS))
        assertTrue(Rbac.can(UserRole.LICITACOES, Permission.PREPARAR_PROPOSTA))
        assertTrue(Rbac.can(UserRole.LICITACOES, Permission.GERENCIAR_DOCUMENTOS))
        assertFalse(Rbac.can(UserRole.LICITACOES, Permission.APROVAR_PISO))
        assertFalse(Rbac.can(UserRole.LICITACOES, Permission.APROVAR_ENVIO))
        assertFalse(Rbac.can(UserRole.LICITACOES, Permission.VER_CUSTOS))
        assertFalse(Rbac.can(UserRole.LICITACOES, Permission.VER_AUDITORIA))
    }

    @Test
    fun `financeiro ve custos e define preco minimo mas nao opera nem envia`() {
        assertTrue(Rbac.can(UserRole.FINANCEIRO, Permission.VER_CUSTOS))
        assertTrue(Rbac.can(UserRole.FINANCEIRO, Permission.DEFINIR_PRECO_MINIMO))
        assertTrue(Rbac.can(UserRole.FINANCEIRO, Permission.APROVACAO_FINANCEIRA))
        assertTrue(Rbac.can(UserRole.FINANCEIRO, Permission.APROVAR_PISO))
        assertFalse(Rbac.can(UserRole.FINANCEIRO, Permission.OPERAR_SESSOES))
        assertFalse(Rbac.can(UserRole.FINANCEIRO, Permission.APROVAR_ENVIO))
        assertFalse(Rbac.can(UserRole.FINANCEIRO, Permission.RESPONDER_MENSAGENS))
    }

    @Test
    fun `tecnico avalia viabilidade e nao toca em dinheiro`() {
        assertTrue(Rbac.can(UserRole.TECNICO, Permission.AVALIAR_VIABILIDADE))
        assertTrue(Rbac.can(UserRole.TECNICO, Permission.ANALISAR))
        assertFalse(Rbac.can(UserRole.TECNICO, Permission.VER_CUSTOS))
        assertFalse(Rbac.can(UserRole.TECNICO, Permission.APROVAR_PISO))
        assertFalse(Rbac.can(UserRole.TECNICO, Permission.OPERAR_SESSOES))
        assertFalse(Rbac.can(UserRole.TECNICO, Permission.ALTERAR_REGRAS))
    }

    @Test
    fun `apenas admin gerencia empresas e configura IA`() {
        UserRole.entries.filter { it != UserRole.ADMIN }.forEach { role ->
            assertFalse(role.name, Rbac.can(role, Permission.GERENCIAR_EMPRESAS))
            assertFalse(role.name, Rbac.can(role, Permission.CONFIGURAR_IA))
        }
    }

    @Test
    fun `todo perfil tem ao menos uma permissao e pode buscar`() {
        UserRole.entries.forEach { role ->
            assertTrue(role.name, Rbac.permissionsOf(role).isNotEmpty())
            assertTrue(role.name, Rbac.can(role, Permission.BUSCAR))
        }
    }
}
