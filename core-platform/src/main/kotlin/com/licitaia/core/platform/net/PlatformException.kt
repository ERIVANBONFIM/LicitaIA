package com.licitaia.core.platform.net

import java.io.IOException

/** Falha ao falar com a plataforma LicitaPRO, com mensagem amigável em pt-BR. */
class PlatformException(
    message: String,
    val kind: Kind,
    val httpStatus: Int? = null,
    /** Código de erro do servidor quando informado (ex.: TOKEN_EXPIRED). */
    val code: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Kind {
        OFFLINE,
        TIMEOUT,
        /** 401/sessão: o cliente deve apagar o token e voltar ao login (contrato §4.2). */
        UNAUTHORIZED,
        HTTP,
        INVALID_RESPONSE,
    }

    val isUnauthorized: Boolean get() = kind == Kind.UNAUTHORIZED
}
