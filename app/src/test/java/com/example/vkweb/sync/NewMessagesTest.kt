package com.example.vkweb.sync

import com.example.vkweb.messages.DialogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewMessagesTest {

    private fun dialog(peer: String, snippet: String, unread: Boolean, time: String = "12:30") =
        DialogEntry(
            peerId = peer, title = "Диалог $peer", snippet = snippet, time = time, unread = unread, online = false,
        )

    @Test fun `первый снимок запоминается без уведомления`() {
        val result = NewMessages.detect(listOf(dialog("1", "давно", unread = true)), SyncState())
        assertTrue(result.fresh.isEmpty())
        assertTrue(result.state.seeded)
        assertEquals(1, result.state.notified.size)
    }

    @Test fun `новое сообщение в непрочитанном диалоге — повод для уведомления`() {
        val first = NewMessages.detect(listOf(dialog("1", "привет", unread = true)), SyncState())
        val second = NewMessages.detect(
            listOf(dialog("1", "привет", unread = true), dialog("2", "ты где", unread = true)), first.state,
        )
        assertEquals(listOf("2"), second.fresh.map { it.peerId })
        assertEquals(listOf("1", "2"), second.unread.map { it.peerId })
    }

    @Test fun `о том же сообщении второй раз не сообщаем`() {
        val state = NewMessages.detect(listOf(dialog("1", "привет", unread = true)), SyncState()).state
        val again = NewMessages.detect(listOf(dialog("1", "привет", unread = true)), state)
        assertTrue(again.fresh.isEmpty())
    }

    @Test fun `время в подписи не участвует — вчерашнее не становится новым`() {
        val state = NewMessages.detect(listOf(dialog("1", "привет", unread = true, time = "23:59")), SyncState()).state
        val nextDay = NewMessages.detect(listOf(dialog("1", "привет", unread = true, time = "вчера")), state)
        assertTrue(nextDay.fresh.isEmpty())
    }

    @Test fun `новый текст в том же диалоге — снова новое`() {
        val state = NewMessages.detect(listOf(dialog("1", "привет", unread = true)), SyncState()).state
        val next = NewMessages.detect(listOf(dialog("1", "как дела", unread = true)), state)
        assertEquals(listOf("1"), next.fresh.map { it.peerId })
    }

    @Test fun `прочитанное в уведомление не попадает`() {
        val state = NewMessages.detect(emptyList(), SyncState()).state
        val result = NewMessages.detect(listOf(dialog("1", "привет", unread = false)), state)
        assertTrue(result.fresh.isEmpty())
        assertTrue(result.unread.isEmpty())
    }

    @Test fun `прочитали, потом снова написали — сообщаем снова`() {
        var state = NewMessages.detect(emptyList(), SyncState()).state
        state = NewMessages.detect(listOf(dialog("1", "ок", unread = true)), state).state
        state = NewMessages.detect(listOf(dialog("1", "ок", unread = false)), state).state
        val again = NewMessages.detect(listOf(dialog("1", "ок", unread = true)), state)
        assertEquals(listOf("1"), again.fresh.map { it.peerId })
    }

    @Test fun `состояние сохраняется и читается без потерь`() {
        val state = SyncState(seeded = true, notified = mapOf("1" to "ab", "-5" to "cd"))
        assertEquals(state, SyncState.fromJson(state.toJson()))
    }

    @Test fun `повреждённое состояние читается как пустое`() {
        assertFalse(SyncState.fromJson("{ не json").seeded)
        assertFalse(SyncState.fromJson(null).seeded)
        assertFalse(SyncState.fromJson("").seeded)
    }

    @Test fun `беседы без уведомлений не уведомляют`() {
        val seeded = SyncState(seeded = true)
        val r = NewMessages.detect(listOf(dialog("1", "привет", unread = true).copy(muted = true), dialog("2", "здравствуй", unread = true)), seeded)
        assertEquals(listOf("2"), r.fresh.map { it.peerId })
    }
}

/** Новые сообщения и свои настройки диалогов (4.47). */
class NewMessagesRulesTest {

    private fun dialog(peer: String, snippet: String, muted: Boolean = false) =
        DialogEntry(peerId = peer, title = "Диалог $peer", snippet = snippet, time = "", unread = true, online = false, muted = muted)

    @Test fun `выключенный на телефоне диалог в уведомление не попадает`() {
        val rules = mapOf("1" to DialogNotify(NotifyMode.OFF))
        val r = NewMessages.detect(listOf(dialog("1", "а"), dialog("2", "б")), SyncState(seeded = true), rules)
        assertEquals(listOf("2"), r.fresh.map { it.peerId })
        assertEquals(listOf("2"), r.unread.map { it.peerId })
    }

    @Test fun `«всегда» сообщает и о заглушённой в VK беседе`() {
        val rules = mapOf("1" to DialogNotify(NotifyMode.ALWAYS))
        val r = NewMessages.detect(listOf(dialog("1", "а", muted = true)), SyncState(seeded = true), rules)
        assertEquals(listOf("1"), r.fresh.map { it.peerId })
    }

    @Test fun `включили уведомления диалога — о давнем сообщении в нём не сообщаем`() {
        val off = mapOf("1" to DialogNotify(NotifyMode.OFF))
        val state = NewMessages.detect(listOf(dialog("1", "давно")), SyncState(seeded = true), off).state
        val on = NewMessages.detect(listOf(dialog("1", "давно")), state)
        assertTrue(on.fresh.isEmpty())
        assertEquals(listOf("1"), on.unread.map { it.peerId })
        val next = NewMessages.detect(listOf(dialog("1", "новое")), on.state)
        assertEquals(listOf("1"), next.fresh.map { it.peerId })
    }
}
