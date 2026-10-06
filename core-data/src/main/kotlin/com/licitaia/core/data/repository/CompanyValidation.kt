package com.licitaia.core.data.repository

internal fun validCnpj(digits: String): Boolean {
    if (digits.length != 14 || digits.any { !it.isDigit() } || digits.toSet().size == 1) return false
    fun digit(base: String, weights: IntArray): Int {
        val remainder = base.indices.sumOf { (base[it] - '0') * weights[it] } % 11
        return if (remainder < 2) 0 else 11 - remainder
    }
    val first = digit(digits.take(12), intArrayOf(5,4,3,2,9,8,7,6,5,4,3,2))
    val second = digit(digits.take(12) + first, intArrayOf(6,5,4,3,2,9,8,7,6,5,4,3,2))
    return digits[12] - '0' == first && digits[13] - '0' == second
}
