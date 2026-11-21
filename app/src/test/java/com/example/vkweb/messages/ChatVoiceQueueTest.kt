package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVoiceQueueTest {

    private val labels = ChatVoiceQueue.Labels(fromMe = "Вы", trackTitle = "Голосовое", chatTitle = "Аня")
    private fun voice(n: Int) = ChatVoice("https://cs.example/$n.mp3", durationSec = n, id = "1_$n")

    private val history = listOf(
        ChatMessage("a", out = false, text = "", time = "10:00", voices = listOf(voice(1))),
        ChatMessage("b", out = true, text = "", time = "10:01", voices = listOf(voice(2))),
        ChatMessage(
            "c", out = false, text = "", time = "10:02", sender = "Петя",
            forwards = listOf(ChatForward(sender = "", text = "", voices = listOf(voice(3), voice(1)))),
        ),
    )

    @Test fun `все голосовые по порядку, нажатое — начало, повторы один раз`() {
        val q = ChatVoiceQueue.build(history, voice(2), labels)
        assertEquals(listOf(1, 2, 3).map { voice(it).url }, q.tracks.map { it.url })
        assertEquals(1, q.start)
        assertTrue(q.tracks.all { it.voice && ChatVoiceQueue.isVoice(it.id) })
    }

    @Test fun `автор — вы, имя в беседе или переписка, в пересланном без имени — автор сообщения`() {
        val artists = ChatVoiceQueue.build(history, voice(1), labels).tracks.map { it.artist }
        assertEquals(listOf("Аня · 10:00", "Вы · 10:01", "Петя · 10:02"), artists)
    }

    @Test fun `голосового нет в истории — одно, без очереди`() {
        val q = ChatVoiceQueue.build(history, voice(9), labels)
        assertEquals(0, q.start)
        assertEquals(listOf(voice(9).url), q.tracks.map { it.url })
        assertEquals("Аня", q.tracks.single().artist)
    }
}
