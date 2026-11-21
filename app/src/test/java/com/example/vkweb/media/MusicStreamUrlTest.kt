package com.example.vkweb.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Адрес звука у найденного поиском (4.88): только серверы звука VK — иначе трек играет страница, как раньше. */
class MusicStreamUrlTest {

    private fun track(id: String, url: String?) = JSONObject().put("id", id).put("title", "T").put("artist", "A")
        .put("duration", 100).apply { if (url != null) put("url", url) }

    @Test fun `адрес звука — только https на серверах звука VK`() {
        val p = MusicPayload.fromJson(
            JSONObject().put("page", "search").put("kind", "list").put(
                "items", JSONArray()
                    .put(track("30_1", "https://psv4.vkuseraudio.ru/s/v1/a1.mp3?x=1"))
                    .put(track("30_2", "https://evil.example.com/a.mp3"))
                    .put(track("30_3", "http://psv4.vkuseraudio.ru/a.mp3"))
                    .put(track("30_4", null)),
            ),
        )
        assertEquals(listOf("https://psv4.vkuseraudio.ru/s/v1/a1.mp3?x=1", "", "", ""), p.tracks.map { it.streamUrl })
    }
}
