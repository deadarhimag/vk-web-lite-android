package com.example.vkweb.clips

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipLikeTest {

    private val clip = ClipItem(id = "-5_1", mp4 = "https://vkvd1.okcdn.ru/1.mp4", hls = "", likes = 7, liked = false)

    @Test fun `разбор ответа страницы`() {
        assertEquals(ClipLike("-5_1", true, true, 8, ""), ClipLike.fromJson(JSONObject("""{"id":"-5_1","ok":true,"liked":true,"likes":8}""")))
        assertNull(ClipLike.fromJson(JSONObject("""{"id":"чужое","ok":true}""")))
    }

    @Test fun `число без точного ответа — на единицу`() {
        assertEquals(8, ClipLikes.count(clip, liked = true))
        assertEquals(7, ClipLikes.count(clip, liked = false))
        assertEquals(6, ClipLikes.count(clip.copy(liked = true), liked = false))
    }
}
