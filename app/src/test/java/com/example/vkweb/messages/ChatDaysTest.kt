package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ChatDaysTest {

    private val zone: ZoneId = ZoneOffset.ofHours(3)
    private fun sec(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toEpochSecond()
    private fun msg(id: String, date: Long, out: Boolean = false, sender: String = "", status: MessageStatus = MessageStatus.SENT) =
        ChatMessage(id = id, out = out, text = "т", time = "старое", sender = sender, dateSec = date, status = status)

    private val now = sec(2026, 10, 12, 15)

    @Test fun `подписи дней`() {
        val today = ChatDays.day(sec(2026, 10, 12, 9), zone)!!
        assertEquals("Сегодня", ChatDays.label(today, now, zone))
        assertEquals("Вчера", ChatDays.label(today - 1, now, zone))
        assertEquals("3 октября", ChatDays.label(today - 9, now, zone))
        assertEquals("12 октября 2025", ChatDays.label(ChatDays.day(sec(2025, 10, 12, 9), zone)!!, now, zone))
    }

    @Test fun `день по часовому поясу`() {
        // 23:30 по Москве — тот же день, хотя в UTC это 20:30; 00:30 — уже следующий.
        val late = ChatDays.day(sec(2026, 10, 11, 23, 30), zone)
        val early = ChatDays.day(sec(2026, 10, 12, 0, 30), zone)
        assertEquals(1L, early!! - late!!)
    }

    @Test fun `разделитель только при смене дня, сообщение без даты его не порождает`() {
        val items = listOf(
            msg("c1", sec(2026, 10, 11, 10)), msg("c2", sec(2026, 10, 11, 11)),
            msg("c3", sec(2026, 10, 12, 9)), msg("o:1", 0, out = true, status = MessageStatus.QUEUED),
        )
        val days = ChatDays.days(items, zone)
        assertTrue(ChatDays.headerAt(days, 0) != null)
        assertNull(ChatDays.headerAt(days, 1))
        assertTrue(ChatDays.headerAt(days, 2) != null)
        assertNull(ChatDays.headerAt(days, 3))
    }

    @Test fun `нет дат — нет разделителей и время со страницы`() {
        val items = listOf(msg("c1", 0), msg("c2", 0))
        val days = ChatDays.days(items, zone)
        assertNull(ChatDays.headerAt(days, 0))
        assertNull(ChatDays.headerAt(days, 1))
        assertEquals("старое", ChatDays.time(items[0], zone))
        assertEquals("09:05", ChatDays.time(msg("c3", sec(2026, 10, 12, 9, 5)), zone))
    }

    @Test fun `группы реплик`() {
        val items = listOf(
            msg("c1", sec(2026, 10, 12, 9, 0), sender = "Аня"),
            msg("c2", sec(2026, 10, 12, 9, 3), sender = "Аня"),
            msg("c3", sec(2026, 10, 12, 9, 20), sender = "Аня"),
            msg("c4", sec(2026, 10, 12, 9, 21), sender = "Петя"),
            msg("c5", sec(2026, 10, 12, 9, 22), out = true),
            msg("o:1", 0, out = true, status = MessageStatus.QUEUED),
        )
        assertFalse(ChatDays.groupedWithPrevious(items, 0, zone))
        assertTrue(ChatDays.groupedWithPrevious(items, 1, zone))
        assertFalse("больше пяти минут", ChatDays.groupedWithPrevious(items, 2, zone))
        assertFalse("другой автор", ChatDays.groupedWithPrevious(items, 3, zone))
        assertFalse("своё после чужого", ChatDays.groupedWithPrevious(items, 4, zone))
        assertTrue("неотправленное — к своим", ChatDays.groupedWithPrevious(items, 5, zone))
    }

    // 4.53
    @Test fun `аватар у последнего сообщения реплики в беседе`() {
        val t = sec(2026, 10, 12, 14)
        val items = listOf(
            msg("c:1", t, sender = "Анна"),
            msg("c:2", t + 60, sender = "Анна"),
            msg("c:3", t + 120, sender = "Борис"),
            msg("c:4", t + 180, out = true),
            msg("c:5", t + 240, sender = "Анна"),
        )
        assertEquals(ChatDays.AvatarSlot.SPACE, ChatDays.avatarSlot(items, 0, zone))
        assertEquals(ChatDays.AvatarSlot.SHOW, ChatDays.avatarSlot(items, 1, zone))
        assertEquals("другой автор следом", ChatDays.AvatarSlot.SHOW, ChatDays.avatarSlot(items, 2, zone))
        assertEquals("своё", ChatDays.AvatarSlot.NONE, ChatDays.avatarSlot(items, 3, zone))
        assertEquals("последнее в списке", ChatDays.AvatarSlot.SHOW, ChatDays.avatarSlot(items, 4, zone))
        // Личная переписка: автора не подписывают — места под аватар нет.
        assertEquals(ChatDays.AvatarSlot.NONE, ChatDays.avatarSlot(listOf(msg("c:9", t)), 0, zone))
    }
}
