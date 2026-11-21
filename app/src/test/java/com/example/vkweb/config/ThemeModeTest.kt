package com.example.vkweb.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test fun `по умолчанию тёмная`() {
        assertEquals(ThemeMode.DARK, ThemeMode.DEFAULT)
        assertEquals(ThemeMode.DARK, ThemeMode.fromName(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromName("PURPLE"))
    }

    @Test fun `как в системе следует системе`() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemIsDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemIsDark = false))
    }

    @Test fun `явный выбор важнее системы`() {
        assertFalse(ThemeMode.LIGHT.isDark(systemIsDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemIsDark = false))
    }

    @Test fun `сохранённое значение читается`() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromName(it.name)) }
    }
}
