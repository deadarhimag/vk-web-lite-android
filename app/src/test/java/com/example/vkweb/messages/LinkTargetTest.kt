package com.example.vkweb.messages

import com.example.vkweb.messages.LinkTarget.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkTargetTest {

    private val trusted: (String?, String?) -> Boolean = { scheme, host ->
        scheme == "https" && (host == "vk.com" || host == "m.vk.com" || host == "m.vk.ru")
    }

    @Test fun `страницы VK по https открываются в главном окне как путь с запросом`() {
        assertEquals(Target.Site("/club123"), LinkTarget.forUrl("https://vk.com/club123", trusted))
        assertEquals(Target.Site("/wall-1_2?reply=5"), LinkTarget.forUrl("https://m.vk.ru/wall-1_2?reply=5#c", trusted))
    }

    @Test fun `чужие сайты, http и чужие схемы — наружу`() {
        assertEquals(Target.External("https://example.org/a"), LinkTarget.forUrl("https://example.org/a", trusted))
        assertEquals(Target.External("http://vk.com/club1"), LinkTarget.forUrl("http://vk.com/club1", trusted))
        assertEquals(Target.External("https://vk.com.evil.example/x"), LinkTarget.forUrl("https://vk.com.evil.example/x", trusted))
    }

    @Test fun `путь на сайте остаётся путём`() {
        assertEquals(Target.Site("/audio?act=x"), LinkTarget.forUrl("/audio?act=x#y", trusted))
        assertEquals(Target.External("//evil.example/x"), LinkTarget.forUrl("//evil.example/x", trusted))
    }

    @Test fun `карточка записи и клипа — страница сайта`() {
        assertEquals(Target.Site("/wall-5_33"), LinkTarget.forCard(ChatCard("wall", "Запись", path = "/wall-5_33"), trusted))
        assertEquals(Target.Site("/clip-5_8"), LinkTarget.forCard(ChatCard("clip", "Клип", path = "/clip-5_8"), trusted))
    }

    @Test fun `музыка без номера — только поиск по названию, а не адрес со страницы`() {
        val target = LinkTarget.forCard(ChatCard("audio", "Группа — Песня"), trusted) as Target.Track
        assertEquals("Группа — Песня", target.title)
        assertEquals("", target.trackId)
    }

    @Test fun `музыка с номером — включить трек, поиск запасной`() {
        val target = LinkTarget.forCard(ChatCard("audio", "Группа — Песня", trackId = "-5_77"), trusted) as Target.Track
        assertEquals("-5_77", target.trackId)
        assertEquals("Группа — Песня", target.title)
    }

    @Test fun `ссылка и файл — по адресу, сайт VK в главном окне, остальное наружу`() {
        assertEquals(Target.External("https://example.org/p"), LinkTarget.forCard(ChatCard("link", "Л", url = "https://example.org/p"), trusted))
        assertEquals(Target.Site("/doc7_1?hash=abc"), LinkTarget.forCard(ChatCard("doc", "Ф", url = "https://vk.com/doc7_1?hash=abc"), trusted))
        assertEquals(Target.External("https://cs.example/v.mp4"), LinkTarget.forCard(ChatCard("video_message", "В", url = "https://cs.example/v.mp4"), trusted))
    }
}
