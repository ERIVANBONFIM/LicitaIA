package com.licitaia.domain.demo

/**
 * Identidade do espaço de demonstração ISOLADO ("Explorar demonstração" na tela de login).
 * O usuário demo não tem senha (entra só pelo botão), é ADMIN apenas da empresa demo e nunca
 * se mistura com contas ou empresas reais. [EMAIL] continua reservado: login local/Google o recusam.
 */
object DemoAccount {
    const val EMAIL = "demo@licitaia.app"
    const val USER_NAME = "Visitante da demonstração"
    const val COMPANY_NAME = "Demo Telecom Ltda"
    const val COMPANY_TRADE_NAME = "Demo Telecom"
    /** CNPJ fictício com dígitos verificadores válidos. */
    const val COMPANY_CNPJ = "11222333000181"
}
