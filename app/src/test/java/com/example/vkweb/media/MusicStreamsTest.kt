package com.example.vkweb.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicStreamsTest {

    @Test
    fun foreignAndMissingStreamsAreMissing() {
        val s = MusicStreams.fromJson(JSONObject(
            """{"items":[{"id":"1_1","url":"https://cs1.vkuseraudio.ru/a.mp3"},{"id":"1_2","url":"https://evil.example/b.mp3"},
               {"id":"bad","url":"https://cs1.vkuseraudio.ru/c.mp3"}],"missing":["1_3","1_3","x"]}""",
        ))
        assertEquals(mapOf("1_1" to "https://cs1.vkuseraudio.ru/a.mp3"), s.urls)
        assertEquals(listOf("1_2", "1_3"), s.missing)
        assertEquals(false, s.byPlay)
    }

    @Test
    fun playerAnswerIsMarked() {
        val s = MusicStreams.fromJson(JSONObject("""{"byPlay":true,"items":[],"missing":["5_6"]}"""))
        assertEquals(true, s.byPlay)
        assertEquals(listOf("5_6"), s.missing)
    }
}
