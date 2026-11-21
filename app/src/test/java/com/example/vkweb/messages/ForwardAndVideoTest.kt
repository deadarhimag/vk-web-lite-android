package com.example.vkweb.messages

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.54: «Переслать» (своя переписка первой), файлы видео для проигрывателя в переписке, карточки звонков. */
class ForwardAndVideoTest {

    private fun dialog(peer: String, title: String) = DialogEntry(peer, title, "", "", unread = false, online = false)

    @Test fun `своя переписка всегда первой, даже если в списке она ниже`() {
        val list = ForwardTargets.build(
            listOf(dialog("2000000001", "Беседа"), dialog("5", "Аня"), dialog("30804332", "Иван Иванов")),
            self = "30804332", selfTitle = "Избранное",
        )
        assertEquals(listOf("30804332", "2000000001", "5"), list.map { it.peerId })
        assertEquals("Избранное", list[0].title)
        assertTrue(list[0].isSelf)
        assertFalse(list[1].isSelf)
    }

    @Test fun `своя первой и когда её нет в загруженном списке`() {
        val list = ForwardTargets.build(listOf(dialog("5", "Аня")), self = "\"30804332\"", selfTitle = "Избранное")
        assertEquals(listOf("30804332", "5"), list.map { it.peerId })
    }

    @Test fun `свой номер неизвестен — без своей, повторы убраны`() {
        val list = ForwardTargets.build(listOf(dialog("5", "Аня"), dialog("5", "Аня"), dialog("", "?")), self = "null", selfTitle = "Избранное")
        assertEquals(listOf("5"), list.map { it.peerId })
        assertEquals("", ForwardTargets.selfId("0"))
        assertEquals("", ForwardTargets.selfId("-5"))
        assertEquals("42", ForwardTargets.selfId("42"))
    }

    @Test fun `итог пересылки разбирается, чужой номер отбрасывается`() {
        val ok = ReaderPayload.parse("""{"type":"forwarded","ok":true,"target":"30804332","reason":""}""") as ReaderPayload.Forwarded
        assertTrue(ok.ok)
        assertEquals("30804332", ok.target)
        val bad = ReaderPayload.parse("""{"type":"forwarded","ok":false,"target":"javascript:1","reason":"ошибка API 7"}""") as ReaderPayload.Forwarded
        assertEquals("", bad.target)
        assertEquals("ошибка API 7", bad.reason)
    }

    @Test fun `файлы видео — только https на серверах VK`() {
        val v = ReaderPayload.parse(
            """{"type":"video","id":"-5_77","ok":true,"mp4":"https://vkvd197.okcdn.ru/?expires=1","hls":"https://evil.example/v.m3u8",
               "player":"https://vkvideo.ru/video_ext.php?oid=-5&id=77","title":"Ролик","reason":"video.get"}""",
        ) as ReaderPayload.Video
        assertEquals("-5_77", v.id)
        assertEquals("https://vkvd197.okcdn.ru/?expires=1", v.mp4)
        assertEquals("", v.hls)
        assertEquals("https://vkvideo.ru/video_ext.php?oid=-5&id=77", v.player)
        assertTrue(v.hasSource)
        val none = ReaderPayload.parse("""{"type":"video","id":"-5_77","ok":true,"mp4":"http://vkvd1.okcdn.ru/x"}""") as ReaderPayload.Video
        assertFalse(none.hasSource)
    }

    @Test fun `карточка звонка без адреса принимается, делится названием и итогом`() {
        val list = ReaderPayload.parseCards(JSONArray("""[
            {"kind":"call","title":"Исходящий видеозвонок","subtitle":"Длительность 1:05"},
            {"kind":"call","title":"Групповой звонок идёт","subtitle":"участников: 3","url":"https://vk.com/call/join/abc"}]"""))
        assertEquals(2, list.size)
        assertEquals("Длительность 1:05", list[0].subtitle)
        assertEquals("https://vk.com/call/join/abc", list[1].url)
        val text = MessageShare.textOf(ChatMessage(id = "c1", out = true, text = "", time = "", cards = list.take(1)))
        assertEquals("Исходящий видеозвонок · Длительность 1:05", text)
    }
}

/** 3.55: трек из переписки играет приложение по адресу потока из ответа API. */
class ChatAudioUrlTest {
    @Test fun `адрес звука — только https на серверах звука VK, заглушка отбрасывается`() {
        val ok = ReaderPayload.parse(
            """{"type":"audiourl","id":"474499155_456973058","ok":true,"url":"https://cs9-3v4.vkuseraudio.ru/s/v1/ac/x/index.m3u8?siren=1","title":"SISENIN — Блуд","duration":137,"how":"поиск"}""",
        ) as ReaderPayload.AudioUrl
        assertTrue(ok.ok)
        assertEquals(137, ok.durationSec)
        assertEquals("поиск", ok.how)
        val evil = ReaderPayload.parse("""{"type":"audiourl","id":"1_2","ok":true,"url":"https://evil.example/a.m3u8"}""") as ReaderPayload.AudioUrl
        assertFalse(evil.ok)
        assertEquals("", evil.url)
        val stub = ReaderPayload.parse("""{"type":"audiourl","id":"1_2","ok":true,"url":"https://vk.com/mp3/audio_api_unavailable.mp3"}""") as ReaderPayload.AudioUrl
        assertFalse(stub.ok)
    }
}

/** 3.58: сообщение с файлами — пузырь «отправляется» до прихода настоящего. */
class AttachmentPendingTest {
    private fun msg(id: String, out: Boolean) = ChatMessage(id = id, out = out, text = "t", time = "")

    @Test fun `пузырь — текст и названия файлов, статус «отправляется»`() {
        val m = AttachmentPending.message("Смотри", listOf("фото.jpg", "отчёт.pdf"), 42)
        assertEquals("Смотри\n📎 фото.jpg\n📎 отчёт.pdf", m.text)
        assertTrue(m.isPending)
        assertTrue(m.out)
        assertTrue(AttachmentPending.isPendingId(m.id))
        assertEquals("📎 a.png", AttachmentPending.message("  ", listOf("a.png"), 1).text)
    }

    @Test fun `настоящее пришло — своё подтверждённое с номером больше прежних`() {
        val before = listOf(msg("c10", true), msg("c11", false))
        val max = AttachmentPending.maxCmid(before)
        assertEquals(11L, max)
        assertFalse("чужое не в счёт", AttachmentPending.arrived(before + msg("c12", false), max))
        assertFalse("черновое не в счёт", AttachmentPending.arrived(before + msg("d:x", true), max))
        assertFalse("догруженное старое не в счёт", AttachmentPending.arrived(listOf(msg("c3", true)) + before, max))
        assertTrue(AttachmentPending.arrived(before + msg("c12", true), max))
    }
}
