package com.example.vkweb.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameDialogsTest {

    private fun d(id: String, snippet: String = "привет", unread: Boolean = false, online: Boolean = false, time: String = "12:30") =
        DialogEntry(peerId = id, title = "t$id", snippet = snippet, time = time, unread = unread, online = online)

    @Test fun `порядок не в счёт`() {
        assertTrue(HistoryMerge.sameDialogs(listOf(d("1"), d("2")), listOf(d("2"), d("1"))))
    }

    @Test fun `мигание «в сети» не считается новым`() {
        assertTrue(HistoryMerge.sameDialogs(listOf(d("1", online = false)), listOf(d("1", online = true))))
    }

    @Test fun `новое сообщение — другой набор`() {
        assertFalse(HistoryMerge.sameDialogs(listOf(d("1")), listOf(d("1", snippet = "ответ"))))
    }

    @Test fun `прочитали — другой набор`() {
        assertFalse(HistoryMerge.sameDialogs(listOf(d("1", unread = true)), listOf(d("1", unread = false))))
    }

    @Test fun `появился новый диалог — другой набор`() {
        assertFalse(HistoryMerge.sameDialogs(listOf(d("1")), listOf(d("1"), d("2"))))
    }
}
