package com.example.vkweb.owner

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PostPhotoKeyTest {

    private fun photo(key: String) = PostPhoto.fromJson(
        JSONObject().put("url", "https://sun9-1.userapi.com/a.jpg").put("open", "/video-42_7").put("key", key),
    )!!

    @Test fun `ключ доступа ролика сохраняется и переживает сохранение на диск (4_14)`() {
        val p = photo("ab12CD")
        assertEquals("ab12CD", p.key)
        assertEquals(p, PostPhoto.fromJson(p.toJson()))
    }

    @Test fun `чужое в ключе отбрасывается`() {
        assertEquals("", photo("x&y=1").key)
        assertEquals("", photo("").key)
    }
}
