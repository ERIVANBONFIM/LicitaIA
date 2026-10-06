package com.licitaia.domain.security

import com.licitaia.domain.model.UserRole

enum class Permission(val label: String) {
    VER_TUDO("Visualizar tudo"),
    BUSCAR("Buscar licitações"),
    ANALISAR("Analisar editais"),
    PREPARAR_PROPOSTA("Preparar propostas"),
    APROVAR_PROPOSTA("Aprovar propostas"),
    APROVAR_ENVIO("Aprovar envio ao portal"),
    APROVAR_PISO("Aprovar/alterar piso"),
    ALTERAR_REGRAS("Alterar regras do robô"),
    OPERAR_SESSOES("Operar sessões de pregão"),
    RESPONDER_MENSAGENS("Responder ao pregoeiro"),
    VER_CUSTOS("Ver custos e margens"),
    DEFINIR_PRECO_MINIMO("Definir preço mínimo"),
    APROVACAO_FINANCEIRA("Aprovação financeira"),
    AVALIAR_VIABILIDADE("Avaliar viabilidade técnica"),
    GERENCIAR_DOCUMENTOS("Gerenciar documentos"),
    GERENCIAR_EMPRESAS("Gerenciar empresas e usuários"),
    CONFIGURAR_IA("Configurar provedores de IA"),
    VER_AUDITORIA("Ver auditoria"),
}

/** Matriz de permissões por perfil (SDD §22). */
object Rbac {
    private val matrix: Map<UserRole, Set<Permission>> = mapOf(
        UserRole.ADMIN to Permission.entries.toSet(),
        UserRole.DIRETORIA to setOf(
            Permission.VER_TUDO, Permission.APROVAR_PISO, Permission.APROVAR_ENVIO,
            Permission.APROVAR_PROPOSTA, Permission.ALTERAR_REGRAS, Permission.VER_CUSTOS,
            Permission.VER_AUDITORIA, Permission.BUSCAR, Permission.ANALISAR,
        ),
        UserRole.LICITACOES to setOf(
            Permission.BUSCAR, Permission.ANALISAR, Permission.PREPARAR_PROPOSTA,
            Permission.RESPONDER_MENSAGENS, Permission.OPERAR_SESSOES,
            Permission.GERENCIAR_DOCUMENTOS,
        ),
        UserRole.FINANCEIRO to setOf(
            Permission.VER_CUSTOS, Permission.DEFINIR_PRECO_MINIMO, Permission.APROVACAO_FINANCEIRA,
            Permission.APROVAR_PISO, Permission.BUSCAR,
        ),
        UserRole.TECNICO to setOf(
            Permission.AVALIAR_VIABILIDADE, Permission.ANALISAR, Permission.BUSCAR,
            Permission.GERENCIAR_DOCUMENTOS,
        ),
    )

    fun permissionsOf(role: UserRole): Set<Permission> = matrix[role].orEmpty()

    fun can(role: UserRole, permission: Permission): Boolean = permission in permissionsOf(role)
}
