package com.example.vkweb.comments

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ответы на комментарии (4.46): строки списка с ветками и разбор первых ответов. */
class CommentRowsTest {

    private fun c(id: Long, replies: Int = 0, thread: List<VideoComment> = emptyList()) =
        VideoComment(id, "a$id", "", "t$id", 0, 0, "", "", replies, thread)

    private fun shape(rows: List<CommentRows.Row>) = rows.map {
        when (it) {
            is CommentRows.Row.Item -> "${"  ".repeat(it.depth)}${it.comment.id}"
            is CommentRows.Row.More -> "  +${it.remaining}${if (it.loading) "…" else ""}"
        }
    }

    @Test fun `первые ответы под комментарием и строка «показать остальные»`() {
        val t = CommentRows()
        t.addTop(listOf(c(1, replies = 5, thread = listOf(c(11), c(12))), c(2)), replace = true)
        assertEquals(listOf("1", "  11", "  12", "  +3", "2"), shape(t.rows()))
        val first = t.rows()[0] as CommentRows.Row.Item
        assertTrue("ответы видны — число у комментария не пишется", first.expanded)
    }

    @Test fun `вся ветка заменяет первые ответы, пока грузится — строка ждёт`() {
        val t = CommentRows()
        t.addTop(listOf(c(1, replies = 4, thread = listOf(c(11)))), replace = true)
        assertTrue(t.startThread(1))
        assertFalse("повторный запрос, пока грузится, не уходит", t.startThread(1))
        assertEquals(listOf("1", "  11", "  +3…"), shape(t.rows()))
        t.addThread(1, listOf(c(11), c(12), c(13), c(14)), replace = true, next = 4, hasMore = false)
        assertEquals(listOf("1", "  11", "  12", "  13", "  14"), shape(t.rows()))
    }

    @Test fun `ветку не отдали — строка пропадает, тайм-аут — возвращается`() {
        val t = CommentRows()
        t.addTop(listOf(c(1, replies = 3), c(2, replies = 2)), replace = true)
        t.startThread(1)
        assertTrue(t.failThread(1, retry = true))
        assertFalse("ответ уже пришёл — тайм-аут ничего не меняет", t.failThread(1, retry = true))
        t.startThread(2)
        t.failThread(2, retry = false)
        assertEquals(listOf("1", "  +3", "2"), shape(t.rows()))
    }

    @Test fun `новая первая порция начинает список заново, повторы отброшены`() {
        val t = CommentRows()
        t.addTop(listOf(c(1), c(2)), replace = true)
        t.addTop(listOf(c(2), c(3)), replace = false)
        assertEquals(listOf("1", "2", "3"), shape(t.rows()))
        t.addTop(listOf(c(9)), replace = true)
        assertEquals(listOf("9"), shape(t.rows()))
    }

    @Test fun `после своего ответа ветка перечитывается`() {
        val t = CommentRows()
        t.addTop(listOf(c(1, replies = 1)), replace = true)
        t.startThread(1)
        t.addThread(1, listOf(c(11)), replace = true, next = 1, hasMore = false)
        t.resetThread(1)
        assertEquals(0, t.threadNext(1))
        assertEquals(listOf("1", "  +1"), shape(t.rows()))
    }

    @Test fun `разбор — первые ответы внутри комментария, глубже не идёт, номер родителя и ответа`() {
        val reply = JSONObject().put("id", 11).put("text", "r").put("thread", JSONArray().put(JSONObject().put("id", 99).put("text", "x")))
        val page = CommentsPage.fromJson(
            JSONObject().put("id", "-5_1").put("parent", 7).put("ok", true)
                .put("items", JSONArray().put(JSONObject().put("id", 1).put("text", "t").put("replies", 2).put("thread", JSONArray().put(reply)))),
        )!!
        assertEquals(7L, page.parent)
        assertEquals(listOf(11L), page.items[0].thread.map { it.id })
        assertTrue(page.items[0].thread[0].thread.isEmpty())
        assertEquals(11L, CommentSent.fromJson(JSONObject().put("id", "-5_1").put("ok", true).put("replyTo", 11))!!.replyTo)
    }
}
