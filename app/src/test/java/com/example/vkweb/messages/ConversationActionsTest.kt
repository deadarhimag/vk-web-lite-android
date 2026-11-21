package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationActionsTest {

    private fun entry(peer: String, unread: Boolean = false, count: Int = 0, muted: Boolean = false) =
        DialogEntry(peerId = peer, title = "t$peer", snippet = "", time = "", unread = unread, online = false, muted = muted, unreadCount = count)

    private val list = listOf(entry("1", unread = true, count = 3), entry("2"), entry("3", muted = true))

    @Test fun `прочитана — без отметки и без числа, остальные не тронуты`() {
        val r = ConversationActions.apply(list, ConversationActions.READ, "1")
        assertFalse(r[0].unread)
        assertEquals(0, r[0].unreadCount)
        assertEquals(list.drop(1), r.drop(1))
    }

    @Test fun `непрочитана и уведомления`() {
        assertTrue(ConversationActions.apply(list, ConversationActions.UNREAD, "2")[1].unread)
        assertTrue(ConversationActions.apply(list, ConversationActions.MUTE, "2")[1].muted)
        assertFalse(ConversationActions.apply(list, ConversationActions.UNMUTE, "3")[2].muted)
    }

    @Test fun `удалённая пропадает из списка`() {
        assertEquals(listOf("1", "3"), ConversationActions.apply(list, ConversationActions.REMOVE, "2").map { it.peerId })
    }

    @Test fun `неизвестное действие и чужая беседа ничего не меняют`() {
        assertEquals(list, ConversationActions.apply(list, "pin", "1"))
        assertEquals(list, ConversationActions.apply(list, ConversationActions.READ, "99"))
    }
}
