package com.licitaia.domain.demo

/**
 * Credenciais da conta de demonstração criada pelo seed local.
 * A senha é armazenada no banco apenas como hash PBKDF2 gerado no momento do seed.
 */
object DemoAccount {
    const val EMAIL = "demo@licitaia.app"
    const val PASSWORD = "demo1234"
}
