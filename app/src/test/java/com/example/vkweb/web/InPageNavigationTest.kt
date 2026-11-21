package com.example.vkweb.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class InPageNavigationTest {

    private val trusted: (String?, String?) -> Boolean = { scheme, host ->
        scheme == "https" && host != null && (host == "m.vk.com" || host == "m.vk.ru")
    }

    @Test fun `полный адрес сайта — путь и запрос`() {
        assertEquals("/club123", InPageNavigation.relative("https://m.vk.com/club123", trusted))
        assertEquals("/wall-1_2?reply=5", InPageNavigation.relative("https://m.vk.ru/wall-1_2?reply=5#c", trusted))
        assertEquals("/", InPageNavigation.relative("https://m.vk.ru", trusted))
    }

    @Test fun `путь на сайте остаётся путём, фрагмент отбрасывается`() {
        assertEquals("/groups", InPageNavigation.relative("/groups", trusted))
        assertEquals("/audio?act=x", InPageNavigation.relative("/audio?act=x#y", trusted))
    }

    @Test fun `чужие адреса и схемы без перехода силами страницы`() {
        assertNull(InPageNavigation.relative("https://evil.example/club1", trusted))
        assertNull(InPageNavigation.relative("http://m.vk.com/club1", trusted))
        assertNull(InPageNavigation.relative("//evil.example/x", trusted))
        assertNull(InPageNavigation.relative("javascript:alert(1)", trusted))
        assertNull(InPageNavigation.relative("https://m.vk.com/a b", trusted))
    }

    @Test fun `путь без запроса`() {
        assertEquals("/audio", InPageNavigation.pathOf("/audio?act=block"))
        assertEquals("/club1", InPageNavigation.pathOf("/club1"))
    }

    @Test fun `силами страницы — только на том же сайте (VK или VK Видео)`() {
        assertTrue(InPageNavigation.sameSite("m.vk.ru", "https://m.vk.com/audio"))
        assertTrue(InPageNavigation.sameSite("m.vkvideo.ru", "/video"))
        assertFalse(InPageNavigation.sameSite("m.vkvideo.ru", "https://m.vk.com/audio"))
        assertFalse(InPageNavigation.sameSite("m.vk.ru", "https://m.vkvideo.ru/"))
        assertFalse(InPageNavigation.sameSite("evil.example", "https://m.vk.com/feed"))
    }
}
