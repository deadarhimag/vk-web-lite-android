package com.example.vkweb.messages

import com.example.vkweb.owner.PostShare
import com.example.vkweb.owner.WallPost
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageShareTest {

    @Test fun `сообщение для другого приложения — текст, ссылки вложений, музыка названием, пересланное с автором`() {
        val m = ChatMessage(
            "c1", false, "Смотри", "",
            videos = listOf(ChatVideo("-1_2", "Ролик", "")),
            cards = listOf(
                ChatCard("link", "Сайт", url = "https://example.org/p"),
                ChatCard("wall", "Запись", path = "/wall-5_33"),
                ChatCard("audio", "Группа — Песня"),
                ChatCard("doc", "отчёт.pdf", "PDF", url = "https://vk.com/doc1_2?hash=x"),
            ),
            forwards = listOf(ChatForward("Анна", "Исходное", cards = listOf(ChatCard("link", "Ещё", url = "https://e.org")))),
        )
        assertEquals(
            "Смотри\n\nРолик — https://vk.com/video-1_2\nСайт — https://example.org/p\nЗапись — https://vk.com/wall-5_33\n♪ Группа — Песня\n" +
                "отчёт.pdf — https://vk.com/doc1_2?hash=x\n\nПереслано от Анна:\nИсходное\nЕщё — https://e.org",
            MessageShare.textOf(m),
        )
    }

    @Test fun `фото сообщения и пересланного — по порядку, без повторов`() {
        val m = ChatMessage("c1", false, "", "", photos = listOf(ChatPhoto("https://x/1.jpg")),
            forwards = listOf(ChatForward("", "", photos = listOf(ChatPhoto("https://x/2.jpg"), ChatPhoto("https://x/1.jpg")))))
        assertEquals(listOf("https://x/1.jpg", "https://x/2.jpg"), MessageShare.photosOf(m))
    }

    @Test fun `имя файла без недопустимых символов и с расширением`() {
        assertEquals("a_b_c.txt", MessageShare.fileName("a/b:c.txt", "bin"))
        assertEquals("Отчёт.bin", MessageShare.fileName("Отчёт", "bin"))
        assertEquals("file", MessageShare.fileName("  ", ""))
    }

    @Test fun `расширение файла из подписи вложения`() {
        assertEquals("pdf", MessageShare.extensionFrom("PDF · 1.5 МБ"))
        assertEquals("", MessageShare.extensionFrom("1.5 МБ"))
        assertEquals("", MessageShare.extensionFrom(""))
        assertEquals("Отчёт.pdf", MessageShare.fileName("Отчёт", MessageShare.extensionFrom("PDF · 1.5 МБ")))
    }

    @Test fun `запись стены — текст и ссылка, длинный текст обрезается`() {
        val post = WallPost("-1_2", "/wall-1_2", -1, "Клуб", "", 0, "Текст записи", emptyList(), emptyList())
        assertEquals("Текст записи\n\nhttps://vk.com/wall-1_2", PostShare.textOf(post))
        val long = post.copy(text = "я".repeat(1500))
        assertEquals(1001, PostShare.textOf(long).substringBefore("\n\n").length)
    }
}
