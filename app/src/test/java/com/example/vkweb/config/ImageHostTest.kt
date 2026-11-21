package com.example.vkweb.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHostTest {
    @Test fun `обложки видео с сетей доставки VK Видео загружаются`() {
        listOf("sun9-24.userapi.com", "i.vkuserphoto.ru", "i.mycdn.me", "iv.okcdn.ru", "m.vkvideo.ru").forEach {
            assertTrue(it, AppConfig.isImageHost(it))
        }
    }

    @Test fun `чужие и поддельные хосты — нет`() {
        listOf("evilmycdn.me", "mycdn.me.evil.example", "example.org", "", null).forEach {
            assertFalse(it.toString(), AppConfig.isImageHost(it))
        }
    }
}
