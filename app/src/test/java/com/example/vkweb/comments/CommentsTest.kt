package com.example.vkweb.comments

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Комментарии к видео и клипам своим окном (4.45): разбор сообщений страницы и номер ролика из пути. */
class CommentsTest {

    @Test fun `номер ролика из пути страницы`() {
        assertEquals("-5_1", CommentTarget.fromPagePath("/video-5_1"))
        assertEquals("12_34", CommentTarget.fromPagePath("/clip12_34?access_key=abc"))
        assertEquals("-5_1", CommentTarget.fromPagePath("/video-5_1_abcdef"))
        assertNull(CommentTarget.fromPagePath("/videos-5"))
        assertNull(CommentTarget.fromPagePath("/audio"))
        assertEquals("w-5_9", CommentTarget.ofPost("-5_9"))
        assertNull(CommentTarget.ofPost("мусор"))
        assertNull(CommentTarget.fromPagePath(""))
        assertNull(CommentTarget.fromPagePath(null))
        assertNull("слишком длинный номер", CommentTarget.fromPagePath("/video-1234567890123_1"))
    }

    @Test fun `порция комментариев — чужие адреса картинок отброшены, тип вложения очищен`() {
        val json = JSONObject()
            .put("id", "-5_1").put("offset", 0).put("next", 30).put("total", 42).put("more", true).put("ok", true)
            .put("items", JSONArray()
                .put(JSONObject().put("id", 11).put("author", "Иван").put("photo", "https://sun9-1.userapi.com/a.jpg")
                    .put("text", "Привет").put("date", 1700000000).put("likes", 2).put("replies", 1))
                .put(JSONObject().put("id", 12).put("photo", "https://evil.example/a.jpg").put("attach", "sticker<script>")
                    .put("sticker", "http://vk.ru/s.png"))
                .put(JSONObject().put("id", 0).put("text", "без номера")))
        val page = CommentsPage.fromJson(json)!!
        assertTrue(page.ok)
        assertTrue(page.hasMore)
        assertEquals(30, page.next)
        assertEquals(42, page.total)
        assertEquals(listOf(11L, 12L), page.items.map { it.id })
        assertEquals("https://sun9-1.userapi.com/a.jpg", page.items[0].photoUrl)
        assertEquals("", page.items[1].photoUrl)
        assertEquals("", page.items[1].stickerUrl)
        assertEquals("stickerscript", page.items[1].attachment)
        assertEquals(1, page.items[0].replies)
    }

    @Test fun `ответы с чужим номером не принимаются`() {
        assertNull(CommentsPage.fromJson(JSONObject().put("id", "мусор")))
        assertNull(CommentSent.fromJson(JSONObject().put("id", "1")))
        val sent = CommentSent.fromJson(JSONObject().put("id", "-5_1").put("ok", false).put("reason", "ошибка API 15"))!!
        assertFalse(sent.ok)
        assertEquals("ошибка API 15", sent.reason)
    }

    @Test fun `подписчик получает только пока он подписан`() {
        val listener = Recorder()
        val got = listener.got
        CommentsHub.listener = listener
        CommentsHub.deliver(CommentsPage("1_2", 0, 0, emptyList(), 0, false, true, ""))
        CommentsHub.deliver(CommentSent("1_2", true, ""))
        CommentsHub.listener = null
        CommentsHub.deliver(CommentSent("1_2", false, ""))
        assertEquals(listOf("page:1_2", "sent:true"), got)
        CommentsHub.request = null
        assertFalse("без главного окна — своего списка нет", CommentsHub.isAvailable)
    }
}

private class Recorder : CommentsHub.Listener {
    val got = ArrayList<String>()
    override fun onPage(page: CommentsPage) { got += "page:" + page.videoId }
    override fun onSent(result: CommentSent) { got += "sent:" + result.ok }
}
