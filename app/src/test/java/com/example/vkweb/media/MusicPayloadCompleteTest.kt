package com.example.vkweb.media

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPayloadCompleteTest {

    private fun json(extra: String) = JSONObject("""{"type":"music","kind":"my","key":"/audio","hasMore":false,"items":[]$extra}""")

    @Test fun `без явного «весь список» список не полный — сохранённое не выбрасывается`() {
        assertFalse(MusicPayload.fromJson(json("")).complete)
    }

    @Test fun `отсутствие «есть ещё» не значит «весь список»`() {
        val p = MusicPayload.fromJson(json(""","complete":false"""))
        assertFalse(p.hasMore)
        assertFalse(p.complete)
    }

    @Test fun `явный ответ страницы — весь список`() {
        assertTrue(MusicPayload.fromJson(json(""","complete":true""")).complete)
    }
}
