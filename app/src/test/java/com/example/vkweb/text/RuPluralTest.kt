package com.example.vkweb.text

import org.junit.Assert.assertEquals
import org.junit.Test

class RuPluralTest {

    private fun friends(n: Long) = RuPlural.count(n, "друг", "друга", "друзей")

    @Test fun `формы`() {
        assertEquals("1 друг", friends(1))
        assertEquals("2 друга", friends(2))
        assertEquals("5 друзей", friends(5))
        assertEquals("0 друзей", friends(0))
        assertEquals("21 друг", friends(21))
        assertEquals("104 друга", friends(104))
    }

    @Test fun `от 11 до 14 — всегда многие`() {
        listOf(11L, 12L, 13L, 14L, 111L, 1012L).forEach { assertEquals("$it друзей", friends(it)) }
    }

    @Test fun `отрицательные — как положительные`() {
        assertEquals("друга", RuPlural.pick(-3, "друг", "друга", "друзей"))
    }
}
