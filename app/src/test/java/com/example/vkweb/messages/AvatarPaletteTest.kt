package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarPaletteTest {

    @Test fun `белые буквы читаются на любом цвете заглушки`() {
        val white = 0xFFFFFFFF.toInt()
        AvatarPalette.COLORS.forEach { c ->
            val ratio = AvatarPalette.contrast(c, white)
            assertTrue("Цвет #%06X: контраст %.2f меньше 4.5".format(c and 0xFFFFFF, ratio), ratio >= 4.5)
        }
    }

    @Test fun `цвет стабилен для одного имени`() {
        assertEquals(AvatarPalette.colorFor("Иван Петров"), AvatarPalette.colorFor("Иван Петров"))
        assertTrue(AvatarPalette.colorFor("") in AvatarPalette.COLORS)
    }

    @Test fun `инициалы`() {
        assertEquals("ИП", AvatarPalette.initials("иван  петров"))
        assertEquals("Б", AvatarPalette.initials("Беседа"))
        assertEquals("?", AvatarPalette.initials("   "))
    }

    // 4.53
    @Test fun `имя автора читается на входящем пузыре любой темы`() {
        // Фоны входящего пузыря (colorSurfaceContainerHigh): светлая, тёмная, чёрная тема.
        val light = intArrayOf(0xFFECEEF2.toInt())
        val dark = intArrayOf(0xFF2A3140.toInt(), 0xFF1A1C21.toInt())
        for ((colors, backgrounds) in listOf(AvatarPalette.NAME_COLORS_LIGHT to light, AvatarPalette.NAME_COLORS_DARK to dark)) {
            colors.forEach { c ->
                backgrounds.forEach { b ->
                    val ratio = AvatarPalette.contrast(c, b)
                    assertTrue("Имя #%06X на #%06X: %.2f меньше 4.5".format(c and 0xFFFFFF, b and 0xFFFFFF, ratio), ratio >= 4.5)
                }
            }
        }
        assertTrue(AvatarPalette.isDark(0xFF2A3140.toInt()))
        assertTrue(!AvatarPalette.isDark(0xFFECEEF2.toInt()))
    }

    @Test fun `имя тем же цветом, что кружок`() {
        val i = AvatarPalette.COLORS.indexOf(AvatarPalette.colorFor("Анна К"))
        assertEquals(AvatarPalette.NAME_COLORS_LIGHT[i], AvatarPalette.nameColorFor("Анна К", darkBackground = false))
        assertEquals(AvatarPalette.NAME_COLORS_DARK[i], AvatarPalette.nameColorFor("Анна К", darkBackground = true))
    }
}
