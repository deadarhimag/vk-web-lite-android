package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEditingTest {

    private val now = 1_800_000_000L
    private fun msg(id: String, out: Boolean = true, text: String = "привет", ago: Long = 60, photos: List<ChatPhoto> = emptyList()) =
        ChatMessage(id = id, out = out, text = text, time = "", dateSec = now - ago, photos = photos)

    @Test fun `изменить — своё, с текстом, без вложений, не старше суток`() {
        assertTrue(MessageEditing.canEdit(msg("c1"), now))
        assertFalse("чужое", MessageEditing.canEdit(msg("c1", out = false), now))
        assertFalse("старое", MessageEditing.canEdit(msg("c1", ago = MessageEditing.LIMIT_SEC + 1), now))
        assertFalse("без текста", MessageEditing.canEdit(msg("c1", text = " "), now))
        assertFalse("неотправленное", MessageEditing.canEdit(msg("o:1"), now))
        assertFalse("с фото", MessageEditing.canEdit(msg("c1", photos = listOf(ChatPhoto("https://x/a.jpg"))), now))
    }

    @Test fun `удалить у себя — любое подтверждённое, у всех — своё свежее`() {
        assertTrue(MessageEditing.canDelete(msg("c1", out = false)))
        assertFalse(MessageEditing.canDelete(msg("o:1")))
        assertTrue(MessageEditing.canDeleteForAll(msg("c1"), now))
        assertFalse(MessageEditing.canDeleteForAll(msg("c1", out = false), now))
        assertFalse(MessageEditing.canDeleteForAll(msg("c1", ago = MessageEditing.LIMIT_SEC + 1), now))
    }

    @Test fun `удалённое и изменённое в списке`() {
        val list = listOf(msg("c1"), msg("c2", text = "было"), msg("c3"))
        assertEquals(listOf("c1", "c3"), MessageEditing.removed(list, 2).map { it.id })
        assertEquals("стало", MessageEditing.edited(list, 2, "стало")[1].text)
    }
}
