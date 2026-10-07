package com.licitaia.domain.proposal

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyInWordsTest {

    @Test
    fun `valores basicos`() {
        assertEquals("zero real", MoneyInWords.brl(0.0))
        assertEquals("um real", MoneyInWords.brl(1.0))
        assertEquals("dois reais", MoneyInWords.brl(2.0))
        assertEquals("dezesseis reais", MoneyInWords.brl(16.0))
        assertEquals("cem reais", MoneyInWords.brl(100.0))
        assertEquals("cento e um reais", MoneyInWords.brl(101.0))
        assertEquals("novecentos e noventa e nove reais", MoneyInWords.brl(999.0))
    }

    @Test
    fun `centavos`() {
        assertEquals("um centavo", MoneyInWords.brl(0.01))
        assertEquals("cinquenta centavos", MoneyInWords.brl(0.5))
        assertEquals("um real e um centavo", MoneyInWords.brl(1.01))
        assertEquals("vinte e três reais e quarenta e cinco centavos", MoneyInWords.brl(23.45))
        // arredonda a centavos
        assertEquals("dez reais e onze centavos", MoneyInWords.brl(10.105))
    }

    @Test
    fun `milhares`() {
        assertEquals("mil reais", MoneyInWords.brl(1000.0))
        assertEquals("mil e duzentos reais", MoneyInWords.brl(1200.0))
        assertEquals("mil duzentos e trinta e quatro reais e cinquenta e seis centavos", MoneyInWords.brl(1234.56))
        assertEquals("dois mil e cinquenta reais", MoneyInWords.brl(2050.0))
        assertEquals("cento e cinquenta e um mil reais", MoneyInWords.brl(151_000.0))
    }

    @Test
    fun `milhoes e bilhoes`() {
        assertEquals("um milhão de reais", MoneyInWords.brl(1_000_000.0))
        assertEquals("dois milhões de reais", MoneyInWords.brl(2_000_000.0))
        assertEquals("um milhão e quinhentos mil reais", MoneyInWords.brl(1_500_000.0))
        assertEquals(
            "três milhões, quatrocentos e cinquenta e seis mil setecentos e oitenta e nove reais e dez centavos",
            MoneyInWords.brl(3_456_789.10),
        )
        assertEquals("um milhão de reais e cinquenta centavos", MoneyInWords.brl(1_000_000.50))
        assertEquals("um bilhão e um reais", MoneyInWords.brl(1_000_000_001.0))
    }

    @Test
    fun `inteiro por extenso para prazos`() {
        assertEquals("sessenta", MoneyInWords.integer(60))
        assertEquals("trinta", MoneyInWords.integer(30))
        assertEquals("zero", MoneyInWords.integer(0))
    }
}
