package com.example.vkweb.video

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VkVideoModelsTest {

    @Test fun `каталог — ролики с адресами VK, чужие адреса отброшены`() {
        val c = VideoCatalog.fromJson(JSONObject().put("key", "/").put("hasMore", true).put("items", JSONArray()
            .put(JSONObject().put("id", "-5_1").put("title", "Фильм").put("mp4", "https://vkvd1.okcdn.ru/1.mp4").put("cover", "https://evil.example/p.jpg"))
            .put(JSONObject().put("id", "bad"))))!!
        assertEquals(listOf("-5_1"), c.items.map { it.id })
        assertEquals("", c.items[0].cover)
        assertEquals("/video-5_1", c.items[0].pagePath)
    }

    @Test fun `подписи — длительность и просмотры`() {
        assertEquals("2:20:02", VideoFormat.duration(8402))
        assertEquals("1:05", VideoFormat.duration(65))
        assertEquals("", VideoFormat.duration(0))
        assertEquals("56 тыс. просмотров", VideoFormat.views(56827))
        assertEquals("1,2 млн просмотров", VideoFormat.views(1_234_567))
        assertEquals("2 млн просмотров", VideoFormat.views(2_000_000))
        assertEquals("1 просмотр", VideoFormat.views(1))
        assertEquals("3 просмотра", VideoFormat.views(3))
        assertEquals("11 просмотров", VideoFormat.views(11))
    }

    @Test fun `разделы каталога — только страницы VK Видео, подразделы на один уровень`() {
        val json = org.json.JSONObject("""{"key":"/","items":[],"sections":[
            {"title":"Для вас","url":"https://m.vkvideo.ru/discover","selected":true},
            {"title":"Чужой","url":"https://evil.example/x"},
            {"title":"Политика","url":"https://m.vkvideo.ru/politics","subs":[{"title":"СМИ","url":"https://m.vkvideo.ru/politics/media","selected":true}]}]}""")
        val c = VideoCatalog.fromJson(json)!!
        org.junit.Assert.assertEquals(listOf("Для вас", "Политика"), c.sections.map { it.title })
        org.junit.Assert.assertTrue(c.sections[0].selected)
        org.junit.Assert.assertEquals(listOf("СМИ"), c.sections[1].subs.map { it.title })
    }
}
