package com.example.vkweb.messages

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatVoiceTest {

    @Test fun `голосовые разбираются, небезопасные записи и лишнее отбрасываются`() {
        val list = ReaderPayload.parseVoices(JSONArray("""[
            {"url":"https://cs.example/a.mp3","duration":12,"id":"5_4"},
            {"url":"http://cs.example/b.mp3","duration":3},
            {"url":"javascript:1","duration":3},
            {"url":"https://cs.example/c.mp3","duration":999999,"id":"плохой"}]"""))
        assertEquals(listOf("https://cs.example/a.mp3", "https://cs.example/c.mp3"), list.map { it.url })
        assertEquals(12, list[0].durationSec)
        assertEquals("5_4", list[0].id)
        assertEquals("слишком длинная длительность режется, плохой номер отбрасывается", 3600 to "", list[1].durationSec to list[1].id)
    }

    @Test fun `сообщение только с голосовым не пропадает`() {
        val raw = """{"type":"history","peerId":"5","title":"T","items":[{"id":"c1","out":false,"text":"","time":"1","voices":[{"url":"https://cs.example/a.mp3","duration":5}]}]}"""
        assertEquals(1, (ReaderPayload.parse(raw) as ReaderPayload.History).items.single().voices.size)
    }

    @Test fun `музыка без адреса принимается, прочие карточки без адреса — нет`() {
        val list = ReaderPayload.parseCards(JSONArray("""[
            {"kind":"audio","title":"Группа — Песня","subtitle":"Музыка · 3:05"},
            {"kind":"video_message","title":"Видеосообщение","url":"https://cs.example/v.mp4"},
            {"kind":"link","title":"без адреса"}]"""))
        assertEquals(listOf("audio", "video_message"), list.map { it.kind })
    }

    @Test fun `обложка и номер трека разбираются, чужое отбрасывается`() {
        val list = ReaderPayload.parseCards(JSONArray("""[
            {"kind":"audio","title":"А — Б","cover":"https://sun.userapi.com/c.jpg","id":"-5_77","key":"ab12"},
            {"kind":"audio","title":"В — Г","cover":"http://x/c.jpg","id":"drop table","key":"a b"},
            {"kind":"link","title":"Л","url":"https://example.org/p","cover":"https://x/c.jpg","id":"1_2"}]"""))
        assertEquals(Triple("https://sun.userapi.com/c.jpg", "-5_77", "ab12"), Triple(list[0].cover, list[0].trackId, list[0].trackKey))
        assertEquals(Triple("", "", ""), Triple(list[1].cover, list[1].trackId, list[1].trackKey))
        assertEquals("номер трека — только у музыки", "", list[2].trackId)
    }

    @Test fun `карточка музыки получает обложку позже — известная версия обновляется`() {
        val known = ChatMessage("c1", false, "", "", cards = listOf(ChatCard("audio", "А — Б")))
        val fresh = known.copy(cards = listOf(ChatCard("audio", "А — Б", cover = "https://sun.userapi.com/c.jpg", trackId = "-5_77")))
        assertEquals(fresh.cards, HistoryMerge.messages(listOf(known), listOf(fresh)).single().cards)
        // Урезанная версия без карточек обложку не стирает.
        val lite = ChatMessage("c1", false, "[Аудио]", "")
        assertEquals(fresh.cards, HistoryMerge.messages(listOf(fresh), listOf(lite)).single().cards)
    }

    @Test fun `те же голосовые с другой подписью в адресе — известное остаётся`() {
        val known = ChatMessage("c1", false, "", "", voices = listOf(ChatVoice("https://cs.example/a.mp3?sig=1", 12, "5_4")))
        val fresh = known.copy(voices = listOf(ChatVoice("https://cs2.example/a.mp3?sig=2", 12, "5_4")))
        assertEquals(listOf(known), HistoryMerge.messages(listOf(known), listOf(fresh)))
        val lite = ChatMessage("c1", false, "[Голосовое сообщение]", "")
        assertEquals(known.voices, HistoryMerge.messages(listOf(known), listOf(lite)).single().voices)
    }

    @Test fun `время и полоса проигрывателя`() {
        assertEquals("0:07", VoiceFormat.time(7_400))
        assertEquals("1:15", VoiceFormat.time(75_000))
        assertEquals("1:02:30", VoiceFormat.time(3_750_000))
        assertEquals("0:00", VoiceFormat.time(-5))
        assertEquals(500, VoiceFormat.progress(5_000, 10_000))
        assertEquals("за краем — не больше шкалы", 1000, VoiceFormat.progress(20_000, 10_000))
        assertEquals("длительность неизвестна", 0, VoiceFormat.progress(5_000, 0))
    }
}
