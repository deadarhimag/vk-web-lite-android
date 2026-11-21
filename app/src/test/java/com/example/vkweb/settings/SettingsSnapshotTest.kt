package com.example.vkweb.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Смена настроек за время их экрана (4.66): память приложения сменой не считается. */
class SettingsSnapshotTest {
    private val base = mapOf("theme_mode" to "DARK", "nav_style" to "AUTO")

    @Test fun `память приложения не считается сменой настроек`() {
        val after = base + mapOf(
            AppSettings.Keys.MENU_DISCOVERED to true,
            AppSettings.Keys.SESSION_LIFETIME to "3600000,3500000",
            "_has_set_default_values" to true,
        )
        assertEquals(SettingsSnapshot.of(base), SettingsSnapshot.of(after))
    }

    @Test fun `настоящая смена — заметна, порядок ключей не важен`() {
        assertNotEquals(SettingsSnapshot.of(base), SettingsSnapshot.of(base + ("nav_style" to "BOTTOM")))
        assertEquals(SettingsSnapshot.of(base), SettingsSnapshot.of(base.entries.reversed().associate { it.key to it.value }))
    }
}
