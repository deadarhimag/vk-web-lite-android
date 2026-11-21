package com.example.vkweb.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Адреса из данных страницы — одно правило (код ревью 4.44). */
class SafeUrlsTest {

    @Test fun `только https и не длиннее предела`() {
        assertEquals("https://vk.com/a", SafeUrls.https("  https://vk.com/a "))
        assertEquals("", SafeUrls.https("http://vk.com/a"))
        assertEquals("", SafeUrls.https("javascript:alert(1)"))
        assertEquals("", SafeUrls.https(null))
        assertEquals("", SafeUrls.https("https://vk.com/" + "a".repeat(SafeUrls.MAX_LENGTH)))
        assertEquals("", SafeUrls.https("https://vk.com/abcdef", maxLength = 10))
    }

    @Test fun `картинка, видео, звук — только на своих серверах VK`() {
        val img = "https://sun9-1.userapi.com/a.jpg"
        assertEquals(img, SafeUrls.image(img))
        assertEquals("", SafeUrls.image("https://evil.example/a.jpg"))
        assertEquals("", SafeUrls.video("https://evil.example/a.mp4"))
        assertEquals("", SafeUrls.audio("https://evil.example/a.mp3"))
    }

    @Test fun `хост адреса`() {
        assertEquals("m.vk.ru", SafeUrls.hostOf("https://m.vk.ru/feed"))
        assertNull(SafeUrls.hostOf("not a url"))
        assertNull(SafeUrls.hostOf(null))
    }
}
