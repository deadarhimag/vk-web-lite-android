package com.example.vkweb.sync

import com.example.vkweb.messages.ChatMessage
import com.example.vkweb.messages.ChatPhoto
import com.example.vkweb.messages.DialogEntry
import com.example.vkweb.messages.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Цепочка последних сообщений в уведомлении (5.00.12). */
class NotifyChainTest {

    private fun dialog(unread: Int) = DialogEntry("100", "Канал", "", "", unread = unread > 0, online = false, unreadCount = unread)
    private fun inc(n: Int, text: String = "входящее $n", sender: String = "") =
        ChatMessage(id = "c$n", out = false, text = text, time = "", sender = sender, dateSec = 1000L + n)
    private fun out(n: Int) = ChatMessage(id = "c$n", out = true, text = "моё $n", time = "")

    @Test fun `входящие подряд с конца, до своего сообщения`() {
        val history = listOf(inc(1), out(2), inc(3), inc(4), inc(5))
        assertEquals(listOf("входящее 3", "входящее 4", "входящее 5"), NotifyChain.of(dialog(5), history).map { it.text })
    }

    @Test fun `не больше непрочитанных и не больше восьми`() {
        val history = (1..20).map { inc(it) }
        assertEquals(listOf("входящее 19", "входящее 20"), NotifyChain.of(dialog(2), history).map { it.text })
        assertEquals(NotifyChain.MAX, NotifyChain.of(dialog(50), history).size)
        assertEquals("число неизвестно - одно", listOf("входящее 20"), NotifyChain.of(dialog(0), history).map { it.text })
    }

    @Test fun `вложения подписаны, неотправленное своё не обрывает, автор беседы сохраняется`() {
        val photo = ChatMessage(id = "c7", out = false, text = "", time = "", photos = listOf(ChatPhoto("https://a")), sender = "Анна")
        val pending = ChatMessage(id = "o:1", out = true, text = "черновик", time = "", status = MessageStatus.QUEUED)
        val chain = NotifyChain.of(dialog(3), listOf(inc(5, "привет\n  всем", "Иван"), photo, pending))
        assertEquals(listOf("Иван" to "привет всем", "Анна" to "[Фото]"), chain.map { it.sender to it.text })
    }
}
