package com.licitaia.core.security

import org.junit.Assert.*
import org.junit.Test

class PortableBackupCipherTest {
    @Test fun portableRoundTripAndRandomizedCiphertext() {
        val data = "dados reais de empresa".toByteArray()
        val pass = "senha-portavel-segura".toCharArray()
        val first = PortableBackupCipher.encrypt(data, pass)
        val second = PortableBackupCipher.encrypt(data, pass)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(data, PortableBackupCipher.decrypt(first, pass))
    }
    @Test fun wrongPasswordAndTamperingFailClosed() {
        val bytes = PortableBackupCipher.encrypt("dados".toByteArray(), "senha-portavel-segura".toCharArray())
        assertTrue(runCatching { PortableBackupCipher.decrypt(bytes, "senha-incorreta".toCharArray()) }.isFailure)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching { PortableBackupCipher.decrypt(bytes, "senha-portavel-segura".toCharArray()) }.isFailure)
    }
}
