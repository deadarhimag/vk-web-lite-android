package com.example.vkweb.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuItemsTest {

    @Test fun `по умолчанию включены все пункты`() {
        assertEquals(MenuItems.ALL.toSet(), MenuItems.enabled(null))
        assertTrue(MenuItems.ALL.containsAll(Tab.entries.map { it.name }))
        assertTrue(MenuItems.ALL.contains(HiddenSection.FEED.name))
        // Разделы без пункта меню в список не попадают.
        assertTrue(HiddenSection.STORIES.name !in MenuItems.ALL)
    }

    @Test fun `пустой или негодный набор не оставляет меню пустым`() {
        assertEquals(MenuItems.ALL.toSet(), MenuItems.enabled(emptySet()))
        assertEquals(MenuItems.ALL.toSet(), MenuItems.enabled(setOf("NOPE", "STORIES")))
    }

    @Test fun `выключенные пункты убираются, один остаётся`() {
        assertEquals(setOf("MUSIC"), MenuItems.enabled(setOf("MUSIC", "NOPE")))
    }

    @Test fun `экран при запуске выбирается только из включённых`() {
        val enabled = setOf("MUSIC", "FEED")
        assertEquals("FEED", MenuItems.start("FEED", enabled))
        // Выбранный выключен или не задан: первый включённый по порядку меню.
        assertEquals("MUSIC", MenuItems.start("MESSAGES", enabled))
        assertEquals("MUSIC", MenuItems.start(null, enabled))
    }

    @Test fun `хранятся выключенные пункты, остальные включены`() {
        assertEquals(MenuItems.ALL.toSet(), MenuItems.fromDisabled(null))
        assertEquals(MenuItems.ALL.toSet() - setOf("CLIPS", "GAMES"), MenuItems.fromDisabled(setOf("CLIPS", "GAMES")))
        // Неизвестные имена игнорируются; выключить все нельзя — включены все.
        assertEquals(MenuItems.ALL.toSet(), MenuItems.fromDisabled(setOf("NOPE")))
        assertEquals(MenuItems.ALL.toSet(), MenuItems.fromDisabled(MenuItems.ALL.toSet()))
        assertEquals(setOf("CLIPS", "GAMES"), MenuItems.disabledOf(MenuItems.ALL.toSet() - setOf("CLIPS", "GAMES")))
    }

    @Test fun `имя определяет раздел или открытый раздел VK`() {
        assertEquals(Tab.CLIPS, MenuItems.tab("CLIPS"))
        assertEquals(HiddenSection.MARKET, MenuItems.section("MARKET"))
        assertEquals(null, MenuItems.section("STORIES"))
        assertEquals(null, MenuItems.tab("MARKET"))
    }
}
