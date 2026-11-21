package com.example.vkweb.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Куда встраивается свой скрипт после загрузки страницы (4.65, ScriptInjection). */
class TrustedUrlTest {

    @Test fun `страницы VK по https — да`() {
        listOf("https://m.vk.com/mail", "https://vk.com/", "https://vkvideo.ru/video-1_2", "https://M.VK.RU/x").forEach {
            assertTrue(it, UrlPolicy.isTrustedUrl(it))
        }
    }

    @Test fun `чужие, поддельные, http и битые — нет`() {
        listOf(
            "http://m.vk.com/mail", "https://evilvk.com/", "https://vk.com.evil.example/", "https://example.org/",
            "javascript:alert(1)", "about:blank", "data:text/html,x", "ht tp://::", "", null,
        ).forEach { assertFalse(it.toString(), UrlPolicy.isTrustedUrl(it)) }
    }

    @Test fun `хост для журнала`() {
        assertEquals("evil.example", UrlPolicy.hostOf("https://evil.example/a"))
        assertNull(UrlPolicy.hostOf("ht tp://::"))
        assertNull(UrlPolicy.hostOf(null))
    }
}
