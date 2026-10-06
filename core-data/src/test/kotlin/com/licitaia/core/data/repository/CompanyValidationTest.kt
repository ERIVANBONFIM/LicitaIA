package com.licitaia.core.data.repository
import org.junit.Assert.*
import org.junit.Test
class CompanyValidationTest {
    @Test fun `cnpj checks both check digits`() {
        assertTrue(validCnpj("11222333000181"))
        assertFalse(validCnpj("11222333000182"))
        assertFalse(validCnpj("11111111111111"))
        assertFalse(validCnpj("123"))
    }
}
