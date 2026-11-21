package com.example.vkweb.video

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoQualityTest {

    @Test fun `выбор — самое высокое не выше настройки`() {
        val have = listOf(240, 360, 480, 720, 1080)
        assertEquals(720, VideoQuality.pick(have, 720))
        assertEquals(480, VideoQuality.pick(have, 600))
        assertEquals(1080, VideoQuality.pick(have, 2160))
    }

    @Test fun `все выше настройки — самое низкое из есть, не выше по возможности`() {
        assertEquals(480, VideoQuality.pick(listOf(480, 720), 360))
        assertNull(VideoQuality.pick(emptyList(), 720))
    }

    @Test fun `авто без потока — как по умолчанию`() {
        assertEquals(720, VideoQuality.pick(listOf(360, 720, 1080), VideoQuality.AUTO))
    }

    @Test fun `файлы — только годные адреса и высоты, передача через Intent без потерь`() {
        val o = JSONObject().put("720", "https://vkvd1.okcdn.ru/7.mp4").put("480", "http://bad").put("x", "https://vkvd1.okcdn.ru/x.mp4")
        val files = VideoQuality.parse(o) { if (it.startsWith("https://")) it else "" }
        assertEquals(mapOf(720 to "https://vkvd1.okcdn.ru/7.mp4"), files)
        assertEquals(files, VideoQuality.decode(VideoQuality.encode(files)) { it })
    }

    @Test fun `подписи`() {
        assertEquals("720p", VideoQuality.label(720))
        assertEquals("Авто", VideoQuality.label(VideoQuality.AUTO))
    }

    private val files = mapOf(360 to "https://v/360.mp4", 720 to "https://v/720.mp4", 1080 to "https://v/1080.mp4")
    private val hls = "https://v/index.m3u8"

    @Test fun `выбор — авто при потоке, затем файлы сверху вниз`() {
        assertEquals(listOf(VideoQuality.AUTO, 1080, 720, 360), VideoQuality.choices(files, hls))
        assertEquals(listOf(1080, 720, 360), VideoQuality.choices(files, ""))
        assertEquals(emptyList<Int>(), VideoQuality.choices(emptyMap(), ""))
    }

    @Test fun `начальное — авто только с потоком, иначе потолок по настройке`() {
        assertEquals(VideoQuality.AUTO, VideoQuality.initial(files, hls, VideoQuality.AUTO))
        assertEquals(720, VideoQuality.initial(files, "", VideoQuality.AUTO))
        assertEquals(720, VideoQuality.initial(files, hls, 720))
        assertEquals(VideoQuality.AUTO, VideoQuality.initial(emptyMap(), hls, 720))
        assertEquals(-1, VideoQuality.initial(emptyMap(), "", 720))
    }

    @Test fun `адрес — поток для авто, файл для высоты, иначе ничего`() {
        assertEquals(hls, VideoQuality.urlFor(VideoQuality.AUTO, files, hls))
        assertEquals(null, VideoQuality.urlFor(VideoQuality.AUTO, files, ""))
        assertEquals("https://v/720.mp4", VideoQuality.urlFor(720, files, hls))
        assertEquals(null, VideoQuality.urlFor(480, files, hls))
        assertEquals(null, VideoQuality.urlFor(-1, files, hls))
    }
}
