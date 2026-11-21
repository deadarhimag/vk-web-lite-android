package com.example.vkweb.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentTest {

    private val pixel = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UP1A.231105.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36"
    private val samsung = "Mozilla/5.0 (Linux; Android 13; SM-S918B Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Version/4.0 Chrome/119.0.6045.193 Mobile Safari/537.36"

    @Test fun `модель, сборка и версия Android убираются`() {
        val ua = UserAgent.generic(pixel)
        assertFalse(ua.contains("Pixel"))
        assertFalse(ua.contains("UP1A"))
        assertFalse(ua.contains("Android 14"))
        assertTrue(ua.contains("(Linux; Android 10; K)"))
    }

    @Test fun `как у сокращённого User-Agent Chrome`() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            UserAgent.generic(pixel),
        )
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36",
            UserAgent.generic(samsung),
        )
    }

    @Test fun `одинаковый результат на разных телефонах с одной версией Chrome`() {
        val other = pixel.replace("Pixel 7 Build/UP1A.231105.001", "M2101K6G Build/SKQ1.211006.001").replace(
            "Android 14", "Android 12",
        )
        assertEquals(UserAgent.generic(pixel), UserAgent.generic(other))
    }

    @Test fun `маркер WebView убирается всегда, это нужно, чтобы VK не показал заглушку`() {
        assertFalse(UserAgent.withoutWebViewMarker(pixel).contains("; wv"))
        assertFalse(UserAgent.generic(pixel).contains("wv"))
        assertFalse(UserAgent.generic(pixel).contains("Version/"))
    }

    @Test fun `без защиты остальное не меняется`() {
        val ua = UserAgent.withoutWebViewMarker(pixel)
        assertTrue(ua.contains("Pixel 7"))
        assertTrue(ua.contains("Chrome/120.0.6099.230"))
    }

    @Test fun `непонятная строка остаётся как есть`() {
        assertEquals("curl/8.0", UserAgent.generic("curl/8.0"))
    }
}
