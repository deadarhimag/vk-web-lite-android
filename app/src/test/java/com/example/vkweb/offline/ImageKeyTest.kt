package com.example.vkweb.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImageKeyTest {

    @Test fun `подпись и метка не меняют ключ`() {
        val a = "https://sun9-46.userapi.com/impg/abc/def.jpg?size=807x1080&quality=95&sign=111&type=album&c_uniq_tag=xyz"
        val b = "https://sun9-46.userapi.com/impg/abc/def.jpg?size=807x1080&quality=95&sign=222&type=album&c_uniq_tag=uvw"
        assertEquals(ImageKey.of(a), ImageKey.of(b))
    }

    @Test fun `сервер картинки не меняет ключ`() {
        val a = "https://sun1-18.vkuserphoto.ru/s/v1/ig2/Xyz.jpg?size=100x100&quality=96&ava=1"
        val b = "https://sun9-46.vkuserphoto.ru/s/v1/ig2/Xyz.jpg?size=100x100&quality=96&ava=1"
        assertEquals(ImageKey.of(a), ImageKey.of(b))
    }

    @Test fun `порядок параметров не меняет ключ`() {
        assertEquals(
            ImageKey.of("https://a.vk.ru/p.jpg?size=50x50&crop=1"),
            ImageKey.of("https://b.vk.ru/p.jpg?crop=1&size=50x50"),
        )
    }

    @Test fun `другой размер или другая картинка — другой ключ`() {
        val base = "https://sun1-18.vkuserphoto.ru/p/a.jpg?size=50x50"
        assertNotEquals(ImageKey.of(base), ImageKey.of("https://sun1-18.vkuserphoto.ru/p/a.jpg?size=200x200"))
        assertNotEquals(ImageKey.of(base), ImageKey.of("https://sun1-18.vkuserphoto.ru/p/b.jpg?size=50x50"))
    }

    @Test fun `без параметров и с якорем`() {
        assertEquals("/images/camera_50.png", ImageKey.of("https://vk.ru/images/camera_50.png"))
        assertEquals("/images/camera_50.png", ImageKey.of("https://vk.ru/images/camera_50.png#frag"))
    }
}
