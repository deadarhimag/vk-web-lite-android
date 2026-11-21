package com.example.vkweb.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTrackRequestTest {

    @Test fun `номер и название принимаются, название приводится в порядок`() {
        val request = ChatTrackRequest.from("-5_77", "  Группа —\n Песня ")!!
        assertEquals("-5_77", request.trackId)
        assertEquals("Группа — Песня", request.title)
    }

    @Test fun `без номера — только поиск по названию`() {
        val request = ChatTrackRequest.from("", "Группа — Песня")!!
        assertEquals("", request.trackId)
    }

    @Test fun `чужой номер или пустая просьба отклоняются`() {
        assertNull(ChatTrackRequest.from("drop table", "x"))
        assertNull(ChatTrackRequest.from("", "   "))
        assertNull(ChatTrackRequest.from(null, null))
    }

    @Test fun `ждём только страницу музыки, а не поиск или плейлист`() {
        val request = ChatTrackRequest.from("1_2", "x")!!
        assertTrue(request.isMusicPage("/audio"))
        assertFalse(request.isMusicPage("/search"))
        assertFalse(request.isMusicPage(null))
    }

    @Test fun `название не длиннее 200 знаков`() {
        assertEquals(200, ChatTrackRequest.from("1_2", "я".repeat(500))!!.title.length)
    }
}
