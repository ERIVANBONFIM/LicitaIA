package com.licitaia.core.platform

/**
 * Configuração da camada de PLATAFORMA (LicitaPRO na VPS).
 *
 * Modo NOVO e opcional: convive com o app local atual sem substituí-lo. Só é exercitado quando o usuário
 * escolhe "Entrar na plataforma LicitaPRO" em Configurações.
 *
 * Base URL conforme CONTRATO_API: `https://<host>/api`. O backend usa `FRONTEND_URL` com default
 * `https://licitapro.duckdns.org`; o domínio final previsto é `*.nexussystemtech.com.br`. O host exato
 * ainda será confirmado pelo dono — por isso é configurável (gradle/env/local.properties repassam para cá
 * quando necessário). Mantemos TLS estrito (sem bypass de certificado): a VPS responde no IP com certificado
 * do domínio, então o cliente deve usar o DOMÍNIO, não o IP.
 */
object PlatformConfig {
    /** Base URL padrão (termina com barra, como o OkHttp HttpUrl espera para resolver caminhos relativos). */
    const val DEFAULT_BASE_URL: String = "https://cont-negociacao.nexussystemtech.com.br/api/"

    /** Páginas de licitação por request na sincronização (contrato: `limit` <= 200). */
    const val SYNC_PAGE_SIZE: Int = 200

    /** Teto de páginas por ciclo de sync, para não varrer os 144k registros de uma vez. */
    const val SYNC_MAX_PAGES: Int = 25
}
