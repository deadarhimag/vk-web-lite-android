package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Test

/** Шапка переписки → страница собеседника или участники беседы (4.31). */
class ChatPeopleTest {

    private fun msg(id: String, senderId: Long, sender: String, out: Boolean = false) =
        ChatMessage(id = id, out = out, text = "t", time = "", sender = sender, senderId = senderId)

    @Test fun `личная переписка и сообщество — сразу страница`() {
        assertEquals(ChatPeople.Target.Page("/id42"), ChatPeople.target("42", emptyList()))
        assertEquals(ChatPeople.Target.Page("/club7"), ChatPeople.target("-7", emptyList()))
    }

    @Test fun `беседа — писавшие, последние первыми, по одному разу`() {
        val history = listOf(
            msg("1", 10, "Аня"),
            msg("2", 20, "Борис"),
            msg("3", 0, "", out = true),
            msg("4", 10, "Аня"),
            msg("5", -5, "Бот сообщества"),
            msg("6", 30, ""),
        )
        val target = ChatPeople.target("2000000012", history) as ChatPeople.Target.Choose
        assertEquals(listOf(-5L, 10L, 20L), target.people.map { it.id })
        assertEquals(listOf("/club5", "/id10", "/id20"), target.people.map { it.path })
    }

    @Test fun `беседа без известных авторов и непонятный номер — неизвестно`() {
        assertEquals(ChatPeople.Target.Unknown, ChatPeople.target("2000000012", listOf(msg("1", 0, "Аня"))))
        assertEquals(ChatPeople.Target.Unknown, ChatPeople.target("abc", emptyList()))
        assertEquals(ChatPeople.Target.Unknown, ChatPeople.target(null, emptyList()))
        assertEquals(ChatPeople.Target.Unknown, ChatPeople.target("0", emptyList()))
    }

    @Test fun `номер автора приходит со страницы`() {
        val raw = """{"type":"history","peerId":"2000000012","title":"T","items":[{"id":"m1","out":false,"text":"привет","time":"1","sender":"Аня","senderId":10},{"id":"m2","out":true,"text":"да","time":"2"}]}"""
        val history = ReaderPayload.parse(raw) as ReaderPayload.History
        assertEquals(listOf(10L, 0L), history.items.map { it.senderId })
    }

    // ---- 4.32: полный список участников от VK.

    @Test fun `участники от страницы разбираются, без имени и с номером беседы — пропускаются`() {
        val raw = """{"type":"members","peerId":"2000000012","ok":true,"items":[{"id":8,"name":"Анна К","photo":"https://sun9-1.userapi.com/a.jpg"},{"id":4,"name":"Чужой адрес","photo":"javascript:x"},{"id":-5,"name":"Сообщество"},{"id":9,"name":" "},{"id":2000000001,"name":"Беседа"},{"id":8,"name":"Дубль"}]}"""
        val members = ReaderPayload.parse(raw) as ReaderPayload.Members
        assertEquals("2000000012", members.peerId)
        assertEquals(true, members.ok)
        assertEquals(
            listOf(ChatPeople.Person(8, "Анна К", "https://sun9-1.userapi.com/a.jpg"), ChatPeople.Person(4, "Чужой адрес"), ChatPeople.Person(-5, "Сообщество")),
            members.people,
        )
    }

    @Test fun `полный список — писавшие первыми, затем остальные по порядку VK`() {
        val all = listOf(ChatPeople.Person(1, "А"), ChatPeople.Person(2, "Б"), ChatPeople.Person(3, "В"), ChatPeople.Person(4, "Г"))
        val writers = listOf(ChatPeople.Person(3, "В (старое имя)"), ChatPeople.Person(99, "Вышел из беседы"))
        assertEquals(listOf(3L, 1L, 2L, 4L), ChatPeople.merge(all, writers).map { it.id })
        // Имя — из полного списка (свежее), вышедшие из беседы в нём не появляются.
        assertEquals("В", ChatPeople.merge(all, writers).first().name)
    }

    @Test fun `полного списка нет — только писавшие`() {
        val writers = listOf(ChatPeople.Person(3, "В"))
        assertEquals(writers, ChatPeople.merge(null, writers))
        assertEquals(writers, ChatPeople.merge(emptyList(), writers))
    }
}
