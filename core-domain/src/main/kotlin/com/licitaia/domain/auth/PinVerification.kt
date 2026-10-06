package com.licitaia.domain.auth

/** Resultado da verificação do PIN de desbloqueio com política de bloqueio progressivo. */
sealed interface PinVerification {
    data object Success : PinVerification

    /** PIN incorreto; [remainingAttempts] = tentativas antes do próximo bloqueio (0 = próxima erra bloqueia). */
    data class Wrong(val remainingAttempts: Int) : PinVerification

    /** Bloqueio temporário ativo: nenhuma verificação é feita até [retryAfterMs] decorrer. */
    data class Locked(val retryAfterMs: Long) : PinVerification
}
