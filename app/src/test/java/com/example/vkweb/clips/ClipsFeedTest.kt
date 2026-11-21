package com.example.vkweb.clips

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipsFeedTest {

    private fun clip(id: String, mp4: String = "https://vkvd1.okcdn.ru/v.mp4") =
        JSONObject().put("id", id).put("mp4", mp4).put("hls", "").put("author", "Автор").put("likes", 5)

    @Test fun `ролики с адресом VK принимаются, чужие и без номера — нет`() {
        val p = ClipsPayload.fromJson(JSONObject().put("reset", true).put("hasMore", true).put("items", JSONArray()
            .put(clip("-1_2"))
            .put(clip("-1_3", "https://evil.example/v.mp4"))
            .put(clip("bad"))))!!
        assertEquals(listOf("-1_2"), p.items.map { it.id })
        assertTrue(p.hasMore && p.reset)
        assertEquals("/clip-1_2", p.items[0].pagePath)
    }

    @Test fun `продолжение ленты — новые в конец, прежние на месте`() {
        val a = ClipItem("1_1", "https://vk.com/a.mp4", "")
        val b = ClipItem("1_2", "https://vk.com/b.mp4", "")
        val c = ClipItem("1_3", "https://vk.com/c.mp4", "")
        assertEquals(listOf("1_1", "1_2", "1_3"), ClipsHub.merge(listOf(a, b), listOf(b, c)).map { it.id })
    }

    @Test fun `вкладки раздела разбираются, пустые подписи пропускаются`() {
        val p = ClipsPayload.fromJson(JSONObject().put("items", JSONArray()).put("tabs", JSONArray()
            .put(JSONObject().put("label", "Для вас").put("selected", true))
            .put(JSONObject().put("label", " "))
            .put(JSONObject().put("label", "Подписки"))))!!
        assertEquals(listOf(ClipTab("Для вас", true), ClipTab("Подписки", false)), p.tabs)
    }

    @Test fun `новая лента (вкладка) — новое поколение, продолжение — то же`() {
        val a = ClipItem("1_1", "https://vk.com/a.mp4", "")
        ClipsHub.update(ClipsPayload(listOf(a), hasMore = true, reset = true))
        val g = ClipsHub.generation
        ClipsHub.update(ClipsPayload(listOf(a), hasMore = false, reset = false))
        assertEquals(g, ClipsHub.generation)
        ClipsHub.update(ClipsPayload(listOf(a), hasMore = false, reset = true))
        assertEquals(g + 1, ClipsHub.generation)
    }
}
