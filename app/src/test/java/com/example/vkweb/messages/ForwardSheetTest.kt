package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Test

/** 3.91: лист пересылки — фото и вид получателя, поиск, цитата пересылаемого. */
class ForwardSheetTest {

    private val labels = ForwardTargets.Labels("Фото", "Видео", "Голосовое сообщение", "Вложение", "Пересланные сообщения")

    @Test fun `получатель несёт фото, «в сети» и вид`() {
        val list = ForwardTargets.build(
            listOf(
                DialogEntry("5", "Аня", "", "", unread = false, online = true, avatar = "https://sun9-1.userapi.com/a.jpg"),
                DialogEntry("2000000007", "Беседа", "", "", unread = false, online = false),
                DialogEntry("-42", "Клуб", "", "", unread = false, online = false),
            ),
            self = "30804332", selfTitle = "Избранное",
        )
        assertEquals(
            listOf(ForwardTargets.Kind.SELF, ForwardTargets.Kind.USER, ForwardTargets.Kind.CHAT, ForwardTargets.Kind.GROUP),
            list.map { it.kind },
        )
        assertEquals("https://sun9-1.userapi.com/a.jpg", list[1].avatar)
        assertEquals(true, list[1].online)
    }

    @Test fun `поиск без учёта регистра и ё`() {
        val t = listOf(ForwardTargets.Target("1", "Пётр Иванов", false), ForwardTargets.Target("2", "Анна", false))
        assertEquals(listOf("1"), ForwardTargets.filter(t, "петр").map { it.peerId })
        assertEquals(listOf("1", "2"), ForwardTargets.filter(t, "  ").map { it.peerId })
    }

    @Test fun `цитата — текст, иначе что во вложениях`() {
        assertEquals("Привет как дела", ForwardTargets.preview(ChatMessage("c1", false, "Привет\n  как дела", ""), labels))
        val photos = listOf(ChatPhoto("https://a/1.jpg"), ChatPhoto("https://a/2.jpg"))
        assertEquals("Фото · 2", ForwardTargets.preview(ChatMessage("c1", false, "", "", photos = photos), labels))
        assertEquals("Вложение", ForwardTargets.preview(ChatMessage("c1", false, "", ""), labels))
    }
}
