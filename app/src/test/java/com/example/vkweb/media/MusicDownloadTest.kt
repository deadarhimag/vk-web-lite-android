package com.example.vkweb.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MusicDownloadTest {

    @Test
    fun prefersLargeCoverAndKeepsTags() {
        val d = MusicDownload.fromJson(JSONObject(
            """{"id":"-1_2","url":"https://cs1.vkuseraudio.ru/a/b.mp3","title":"T","artist":"A","album":"Al",
               "cover":"https://sun9-1.userapi.com/s.jpg","coverLarge":"https://sun9-1.userapi.com/l.jpg"}""",
        ))!!
        assertEquals("https://sun9-1.userapi.com/l.jpg", d.coverUrl)
        assertEquals("Al", d.album)
    }

    @Test
    fun busyRefusalIsKept() {
        val d = MusicDownload.fromJson(JSONObject("""{"id":"1_2","url":"","ok":false,"reason":"busy"}"""))!!
        assertFalse(d.ok)
        assertEquals(MusicDownload.REASON_BUSY, d.reason)
    }

    @Test
    fun foreignStreamRejected() {
        assertNull(MusicDownload.fromJson(JSONObject("""{"id":"1_2","url":"https://evil.example/a.mp3"}""")))
        assertNull(MusicDownload.fromJson(JSONObject("""{"id":"x","url":"https://cs1.vkuseraudio.ru/a.mp3"}""")))
    }

    @Test
    fun hlsStreamAccepted() {
        val d = MusicDownload.fromJson(JSONObject("""{"id":"1_2","url":"https://vd12.okcdn.ru/expires/1/a/video.m3u8"}"""))
        assertEquals("https://vd12.okcdn.ru/expires/1/a/video.m3u8", d?.url)
    }

    @Test
    fun cacheFileNameCarriesIdAndIsSafe() {
        val name = MusicCache.displayName("-12_34", "AC/DC", "Back: In (Black)?")
        assertEquals("AC_DC - Back_ In _Black__ (-12_34).mp3", name)
        assertEquals("-12_34", MusicCache.idFromName(name))
        assertEquals("VK track (1_2).mp3", MusicCache.displayName("1_2", "", " "))
        // Имена кэша 4.90–4.96 в папке приложения.
        assertEquals("5_6", MusicCache.idFromName("5_6.mp3"))
        assertNull(MusicCache.idFromName("Чужая песня.mp3"))
        assertNull(MusicCache.idFromName("x (1_2).mp3.part"))
        // MediaStore добавил суффикс (1) после номера при совпадении имён.
        assertEquals("1_2", MusicCache.idFromName("A - B (1_2) (1).mp3"))
    }
}
