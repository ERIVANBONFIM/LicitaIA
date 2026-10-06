package com.licitaia.core.security

/**
 * Política de bloqueio progressivo do PIN de desbloqueio (função pura; o estado é persistido pelo chamador).
 *
 * Até [FREE_ATTEMPTS] - 1 erros consecutivos não há bloqueio. A partir do 5º erro cada novo erro impõe um
 * bloqueio crescente: 30 s, 1 min, 5 min, 15 min, 30 min e, daí em diante, 1 h. Um acerto zera o estado.
 */
object PinLockoutPolicy {
    const val FREE_ATTEMPTS = 5

    /** Estado persistido: erros consecutivos e instante (epoch ms) até o qual o PIN está bloqueado. */
    data class State(val failures: Int = 0, val lockedUntil: Long = 0L) {
        fun serialize(): String = "$failures:$lockedUntil"

        companion object {
            val NONE = State()

            /** Valor malformado → estado limpo (nunca lança). */
            fun parse(raw: String?): State {
                val parts = raw?.split(':') ?: return NONE
                if (parts.size != 2) return NONE
                val failures = parts[0].toIntOrNull()?.coerceAtLeast(0) ?: return NONE
                val until = parts[1].toLongOrNull()?.coerceAtLeast(0L) ?: return NONE
                return State(failures, until)
            }
        }
    }

    private val STEPS_MS = longArrayOf(30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L)

    /** Duração do bloqueio imposto após [failures] erros consecutivos; 0 enquanto há tentativas livres. */
    fun lockoutMillis(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        return STEPS_MS[(failures - FREE_ATTEMPTS).coerceAtMost(STEPS_MS.lastIndex)]
    }

    /** Tempo restante de bloqueio em [now]; 0 = livre para tentar. */
    fun remainingMillis(state: State, now: Long): Long = (state.lockedUntil - now).coerceAtLeast(0L)

    fun isLocked(state: State, now: Long): Boolean = remainingMillis(state, now) > 0L

    /** Tentativas restantes antes do próximo bloqueio (0 = o próximo erro bloqueia). */
    fun remainingAttempts(state: State): Int = (FREE_ATTEMPTS - 1 - state.failures).coerceAtLeast(0)

    /** Novo estado após um erro em [now]: incrementa o contador e aplica o bloqueio correspondente. */
    fun onFailure(state: State, now: Long): State {
        val failures = state.failures + 1
        val lock = lockoutMillis(failures)
        return State(failures, if (lock > 0L) now + lock else 0L)
    }

    /** Acerto: zera contador e bloqueio. */
    fun onSuccess(): State = State.NONE
}
