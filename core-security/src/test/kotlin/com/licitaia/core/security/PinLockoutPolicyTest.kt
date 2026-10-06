package com.licitaia.core.security

import com.licitaia.core.security.PinLockoutPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockoutPolicyTest {
    private val now = 1_000_000L

    @Test fun firstFourFailuresDoNotLock() {
        var state = State.NONE
        repeat(4) { state = PinLockoutPolicy.onFailure(state, now) }
        assertEquals(4, state.failures)
        assertFalse(PinLockoutPolicy.isLocked(state, now))
        assertEquals(0, PinLockoutPolicy.remainingAttempts(state))
    }

    @Test fun fifthFailureLocksForThirtySeconds() {
        var state = State.NONE
        repeat(5) { state = PinLockoutPolicy.onFailure(state, now) }
        assertTrue(PinLockoutPolicy.isLocked(state, now))
        assertEquals(30_000L, PinLockoutPolicy.remainingMillis(state, now))
        assertEquals(0L, PinLockoutPolicy.remainingMillis(state, now + 30_000L))
    }

    @Test fun lockoutIsProgressiveAndCapped() {
        assertEquals(0L, PinLockoutPolicy.lockoutMillis(0))
        assertEquals(0L, PinLockoutPolicy.lockoutMillis(4))
        assertEquals(30_000L, PinLockoutPolicy.lockoutMillis(5))
        assertEquals(60_000L, PinLockoutPolicy.lockoutMillis(6))
        assertEquals(5 * 60_000L, PinLockoutPolicy.lockoutMillis(7))
        assertEquals(15 * 60_000L, PinLockoutPolicy.lockoutMillis(8))
        assertEquals(30 * 60_000L, PinLockoutPolicy.lockoutMillis(9))
        assertEquals(60 * 60_000L, PinLockoutPolicy.lockoutMillis(10))
        assertEquals(60 * 60_000L, PinLockoutPolicy.lockoutMillis(50))
    }

    @Test fun successResetsEverything() {
        var state = State.NONE
        repeat(7) { state = PinLockoutPolicy.onFailure(state, now) }
        state = PinLockoutPolicy.onSuccess()
        assertEquals(State.NONE, state)
        assertEquals(PinLockoutPolicy.FREE_ATTEMPTS - 1, PinLockoutPolicy.remainingAttempts(state))
    }

    @Test fun remainingAttemptsCountDown() {
        assertEquals(4, PinLockoutPolicy.remainingAttempts(State.NONE))
        assertEquals(1, PinLockoutPolicy.remainingAttempts(State(failures = 3)))
        assertEquals(0, PinLockoutPolicy.remainingAttempts(State(failures = 9)))
    }

    @Test fun serializationRoundTripAndMalformedInput() {
        val state = State(failures = 6, lockedUntil = 123_456L)
        assertEquals(state, State.parse(state.serialize()))
        assertEquals(State.NONE, State.parse(null))
        assertEquals(State.NONE, State.parse(""))
        assertEquals(State.NONE, State.parse("abc"))
        assertEquals(State.NONE, State.parse("1:2:3"))
        assertEquals(State.NONE, State.parse("x:1"))
    }
}
