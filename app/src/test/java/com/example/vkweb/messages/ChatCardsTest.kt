package com.example.vkweb.messages

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCardsTest {

    private fun cards(json: String) = ReaderPayload.parseCards(JSONArray(json))

    @Test fun `карточки разбираются`() {
        val list = cards("""[
            {"kind":"link","title":"Заголовок","subtitle":"example.org","url":"https://example.org/p"},
            {"kind":"wall","title":"Запись","path":"/wall-5_33"},
            {"kind":"clip","title":"Клип","path":"/clip-5_8"}]""")
        assertEquals(listOf("link", "wall", "clip"), list.map { it.kind })
        assertEquals("https://example.org/p", list[0].url)
        assertEquals("/wall-5_33", list[1].path)
    }

    @Test fun `чужое отбрасывается — неизвестный вид, небезопасный адрес, посторонний путь`() {
        val list = cards("""[
            {"kind":"hack","title":"x","url":"https://a.b/c"},
            {"kind":"link","title":"x","url":"javascript:alert(1)"},
            {"kind":"link","title":"x","url":"http://a.b/c"},
            {"kind":"wall","title":"x","path":"/login?next=/"},
            {"kind":"wall","title":"x","path":"//evil.example/wall1_2"},
            {"kind":"doc","title":"без адреса"}]""")
        assertTrue(list.isEmpty())
    }

    @Test fun `число карточек ограничено, длинные названия режутся`() {
        val many = (1..10).joinToString(",", "[", "]") { """{"kind":"link","title":"${"т".repeat(500)}","url":"https://a.b/$it"}""" }
        val list = cards(many)
        assertEquals(6, list.size)
        assertEquals(200, list[0].title.length)
    }

    @Test fun `сообщение только с карточкой не пропадает`() {
        val raw = """{"type":"history","peerId":"5","title":"T","items":[{"id":"c1","out":false,"text":"","time":"1","cards":[{"kind":"doc","title":"Файл","url":"https://vk.com/doc1_2"}]}]}"""
        val history = ReaderPayload.parse(raw) as ReaderPayload.History
        assertEquals("doc", history.items.single().cards.single().kind)
    }

    @Test fun `те же карточки с другой подписью — известное остаётся, а урезанная версия карточки не стирает`() {
        val known = ChatMessage("c1", false, "", "", cards = listOf(ChatCard("link", "Старое", "a", url = "https://a.b/c")))
        val renamed = known.copy(cards = listOf(ChatCard("link", "Новое", "b", url = "https://a.b/c")))
        assertEquals(listOf(known), HistoryMerge.messages(listOf(known), listOf(renamed)))
        val lite = ChatMessage("c1", false, "[Ссылка]", "")
        assertEquals(known.cards, HistoryMerge.messages(listOf(known), listOf(lite)).single().cards)
        val other = known.copy(cards = listOf(ChatCard("link", "Другая", "", url = "https://a.b/other")))
        assertEquals(other.cards, HistoryMerge.messages(listOf(known), listOf(other)).single().cards)
    }

    // ---- ссылки в тексте

    private fun urls(text: String) = LinkFinder.find(text).map { it.url }

    @Test fun `ссылки находятся, знаки после них в адрес не входят`() {
        assertEquals(listOf("https://example.org/a?b=1"), urls("Смотри https://example.org/a?b=1, это важно"))
        assertEquals(listOf("https://example.org"), urls("(https://example.org)."))
        assertEquals(listOf("https://ru.wikipedia.org/wiki/Тест_(значение)"), urls("см. https://ru.wikipedia.org/wiki/Тест_(значение)!"))
        assertEquals(listOf("http://a.example/x", "https://b.example/y"), urls("http://a.example/x и https://b.example/y"))
    }

    @Test fun `адрес с www получает схему, границы совпадают с текстом`() {
        val text = "заходи на www.vk.com/club1 сегодня"
        val link = LinkFinder.find(text).single()
        assertEquals("https://www.vk.com/club1", link.url)
        assertEquals("www.vk.com/club1", text.substring(link.start, link.end))
    }

    @Test fun `обычный текст и чужие схемы ссылками не считаются`() {
        assertTrue(LinkFinder.find("просто текст без ссылок").isEmpty())
        assertTrue(LinkFinder.find("javascript:alert(1) и ftp://x.y/z и mailto:a@b.c").isEmpty())
        assertTrue(LinkFinder.find("").isEmpty())
    }
}
