package com.example.vkweb.messages

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaTest {

    private fun videoJson(id: String, extra: String = "") =
        """{"id":"$id","title":"Ролик","cover":"https://i.vkuserphoto.ru/v.jpg","duration":75,"key":"abc123"$extra}"""

    @Test fun `видео из сообщения страницы разбирается`() {
        val v = ReaderPayload.parseVideos(JSONArray("[${videoJson("-42_9")}]")).single()
        assertEquals("-42_9", v.id)
        assertEquals("Ролик", v.title)
        assertEquals("https://i.vkuserphoto.ru/v.jpg", v.coverUrl)
        assertEquals(75, v.durationSec)
        assertEquals("abc123", v.accessKey)
    }

    @Test fun `плохие номера, обложки и ключи отбрасываются, число видео ограничено`() {
        assertTrue(ReaderPayload.parseVideos(JSONArray("[${videoJson("abc")}]")).isEmpty())
        val bad = ReaderPayload.parseVideos(JSONArray("""[{"id":"1_2","cover":"http://x/c.jpg","key":"a b/c","duration":-5}]""")).single()
        assertEquals("", bad.coverUrl)
        assertEquals("", bad.accessKey)
        assertEquals(0, bad.durationSec)
        assertEquals(5, ReaderPayload.parseVideos(JSONArray((1..9).joinToString(",", "[", "]") { videoJson("1_$it") })).size)
    }

    @Test fun `адрес страницы видео — путь на сайте, с ключом для закрытых`() {
        assertEquals("/video-42_9?access_key=abc123", ChatVideo("-42_9", "", "", 0, "abc123").pagePath)
        assertEquals("/video7_1", ChatVideo("7_1", "", "").pagePath)
        assertEquals("/clip-5_8", ChatVideo("-5_8", "", "", isClip = true).pagePath)
    }

    @Test fun `номер фото разбирается, чужое значение отбрасывается`() {
        val photos = ReaderPayload.parsePhotos(JSONArray("""[{"url":"https://x/a.jpg","width":10,"height":5,"id":"7_55"},{"url":"https://x/b.jpg","id":"drop table"}]"""))
        assertEquals(listOf("7_55", ""), photos.map { it.id })
    }

    @Test fun `сообщение только с видео не пропадает`() {
        val raw = """{"type":"history","peerId":"5","title":"T","items":[{"id":"c1","out":false,"text":"","time":"10:00","videos":[${videoJson("1_2")}]}]}"""
        val history = ReaderPayload.parse(raw) as ReaderPayload.History
        assertEquals(1, history.items.size)
        assertEquals("1_2", history.items[0].videos.single().id)
    }

    @Test fun `ход отправки вложений разбирается`() {
        val a = ReaderPayload.parse("""{"type":"attach","stage":"error","detail":"поле выбора файла не найдено"}""") as ReaderPayload.Attach
        assertEquals("error", a.stage)
        assertEquals("поле выбора файла не найдено", a.detail)
    }

    // ---- слияние

    private fun msg(photos: List<ChatPhoto> = emptyList(), videos: List<ChatVideo> = emptyList(), text: String = "") =
        ChatMessage(id = "c10", out = false, text = text, time = "10:00", photos = photos, videos = videos)

    @Test fun `те же фото по номеру — сообщение не меняется, даже если пути картинок разные`() {
        val known = msg(photos = listOf(ChatPhoto("https://a.x/impg/AAA/one.jpg?size=1280x960", 1280, 960, "7_1"), ChatPhoto("https://a.x/impg/BBB/two.jpg", 0, 0, "7_2")))
        val fresh = msg(photos = listOf(ChatPhoto("https://b.x/impg/ZZZ/other.jpg?size=604x453", 604, 453, "7_2"), ChatPhoto("https://b.x/impg/YYY/dif.jpg", 0, 0, "7_1")))
        assertEquals(listOf(known), HistoryMerge.messages(listOf(known), listOf(fresh)))
    }

    @Test fun `другой номер фото — заменяется`() {
        val known = msg(photos = listOf(ChatPhoto("https://a/p.jpg", id = "7_1")))
        val fresh = msg(photos = listOf(ChatPhoto("https://a/p.jpg", id = "7_9")))
        assertEquals(listOf(fresh), HistoryMerge.messages(listOf(known), listOf(fresh)))
    }

    @Test fun `видео не пропадает из сообщения при урезанной версии`() {
        val video = ChatVideo("1_2", "Ролик", "https://c/v.jpg", 10)
        val known = msg(videos = listOf(video))
        val lite = msg(text = "[Видео]")
        val merged = HistoryMerge.messages(listOf(known), listOf(lite.copy(preview = false))).single()
        assertEquals(listOf(video), merged.videos)
    }

    @Test fun `те же видео с другой обложкой — известное остаётся`() {
        val known = msg(videos = listOf(ChatVideo("1_2", "Ролик", "https://c/old.jpg", 10)))
        val fresh = msg(videos = listOf(ChatVideo("1_2", "Ролик", "https://c/new.jpg?x=1", 10)))
        assertEquals(listOf(known), HistoryMerge.messages(listOf(known), listOf(fresh)))
    }

    @Test fun `обложка, появившаяся позже, заменяет пустую`() {
        val known = msg(videos = listOf(ChatVideo("1_2", "", "", 10, isClip = true)))
        val fresh = msg(videos = listOf(ChatVideo("1_2", "", "https://c/v.jpg", 10, isClip = true)))
        assertEquals(listOf(fresh), HistoryMerge.messages(listOf(known), listOf(fresh)))
        // Наоборот — пришла версия без обложки: известная остаётся.
        assertEquals(listOf(fresh), HistoryMerge.messages(listOf(fresh), listOf(known)))
    }

    @Test fun `признак клипа, появившийся позже, не теряется`() {
        val known = msg(videos = listOf(ChatVideo("1_2", "Ролик", "https://c/v.jpg", 10)))
        val fresh = msg(videos = listOf(ChatVideo("1_2", "Ролик", "https://c/v.jpg", 10, isClip = true)))
        assertEquals(true, HistoryMerge.messages(listOf(known), listOf(fresh)).single().videos.single().isClip)
    }

    // ---- заголовки «id…»

    private fun dialog(peer: String, title: String) = DialogEntry(peer, title, "привет", "10:00", false, false)

    @Test fun `заглушка не затирает настоящее имя в списке диалогов`() {
        val shown = listOf(dialog("5", "Анна Петрова"), dialog("-9", "Клуб"), dialog("2000000001", "Друзья"))
        val incoming = listOf(dialog("5", "id5"), dialog("-9", "Сообщество 9"), dialog("2000000001", "Беседа"), dialog("7", "id7"))
        val merged = HistoryMerge.dialogs(shown, incoming)
        assertEquals(listOf("Анна Петрова", "Клуб", "Друзья", "id7"), merged.map { it.title })
    }

    @Test fun `настоящее имя заменяет заглушку, а новое имя — старое`() {
        assertEquals("Анна", HistoryMerge.dialogs(listOf(dialog("5", "id5")), listOf(dialog("5", "Анна"))).single().title)
        assertEquals("Анна К.", HistoryMerge.dialogs(listOf(dialog("5", "Анна")), listOf(dialog("5", "Анна К."))).single().title)
        assertEquals("", HistoryMerge.dialogs(emptyList(), listOf(dialog("5", ""))).single().title)
    }

    // ---- черновые: сверка только с последними настоящими

    @Test fun `черновое, повторяющее недавнее сообщение, не показывается`() {
        val real = (1..100).map { ChatMessage(id = "c$it", out = false, text = "сообщение $it", time = "") }
        val draft = ChatMessage(id = "dom1_x", out = false, text = "сообщение 100 10:00", time = "")
        val merged = HistoryMerge.messages(real, real + draft)
        assertEquals(100, merged.size)
    }

    @Test fun `черновое, которого нет среди недавних, остаётся`() {
        val real = (1..100).map { ChatMessage(id = "c$it", out = false, text = "сообщение $it", time = "") }
        val draft = ChatMessage(id = "dom1_x", out = true, text = "совсем новое", time = "")
        assertEquals("dom1_x", HistoryMerge.messages(real, real + draft).last().id)
    }

    @Test fun `ответ отправки разбирается`() {
        val d = ReaderPayload.parse("""{"type":"delivered","peerId":"100","text":"привет","cmid":17,"n":3}""") as ReaderPayload.Delivered
        assertEquals("100", d.peerId)
        assertEquals("привет", d.text)
        assertEquals(17L, d.cmid)
        val legacy = ReaderPayload.parse("""{"type":"delivered","peerId":"100","text":"привет"}""") as ReaderPayload.Delivered
        assertEquals(0L, legacy.cmid)
    }

    @Test fun `голосовое сохраняет неопределённость доставки и номер операции`() {
        val result = ReaderPayload.parse("""{"type":"voiceSent","ok":false,"reason":"timeout","uncertain":true,"operation":"token"}""") as ReaderPayload.VoiceSent
        assertTrue(result.uncertain)
        assertEquals("token", result.operation)
        val stage = ReaderPayload.parse("""{"type":"voiceStage","operation":"token","documentId":"5_7"}""") as ReaderPayload.VoiceStage
        assertEquals("5_7", stage.documentId)
        assertEquals("token", stage.operation)
    }

    // ---- пересланные

    private val forwardedRaw = """{"type":"history","peerId":"5","title":"T","items":[{"id":"c7","out":false,"text":"","time":"10:00",
        "forwards":[{"sender":"Иван Петров","time":"09:15","depth":1,"text":"Смотри","photos":[{"url":"https://x/a.jpg","id":"7_1"}]},
                    {"sender":"","time":"","depth":9,"text":"глубже"},
                    {"sender":"","text":""}]}]}"""

    @Test fun `сообщение только с пересланным не пропадает, пересланное разбирается`() {
        val m = (ReaderPayload.parse(forwardedRaw) as ReaderPayload.History).items.single()
        assertEquals(2, m.forwards.size)
        val f = m.forwards[0]
        assertEquals(listOf("Иван Петров", "09:15", "Смотри"), listOf(f.sender, f.time, f.text))
        assertEquals("7_1", f.photos.single().id)
        assertEquals("уровень ограничен", 3, m.forwards[1].depth)
    }

    @Test fun `урезанная версия без пересланного его не стирает, переподписанные картинки не меняют сообщение`() {
        val known = (ReaderPayload.parse(forwardedRaw) as ReaderPayload.History).items.single()
        val lite = ChatMessage("c7", false, "", "10:00")
        assertEquals(known.forwards, HistoryMerge.messages(listOf(known), listOf(lite)).single().forwards)
        val resigned = known.copy(forwards = known.forwards.mapIndexed { i, f ->
            if (i == 0) f.copy(photos = listOf(ChatPhoto("https://y/other.jpg?sig=2", id = "7_1"))) else f
        })
        assertEquals(listOf(known), HistoryMerge.messages(listOf(known), listOf(resigned)))
    }

    // ---- ответы

    @Test fun `цитата ответа разбирается, без номера — нет, урезанная версия её не стирает, имя дописывается`() {
        val raw = """{"type":"history","peerId":"5","title":"T","items":[{"id":"c9","out":true,"text":"Да","time":"1",
            "reply":{"cmid":7,"sender":"","text":"Идём?"}},{"id":"c10","out":true,"text":"Нет","time":"1","reply":{"cmid":0,"text":"x"}}]}"""
        val items = (ReaderPayload.parse(raw) as ReaderPayload.History).items
        assertEquals(ChatReply(7, "", "Идём?"), items[0].reply)
        assertEquals("c7", items[0].reply!!.messageId)
        assertEquals(null, items[1].reply)
        val lite = items[0].copy(reply = null)
        assertEquals(items[0].reply, HistoryMerge.messages(listOf(items[0]), listOf(lite)).single().reply)
        val named = items[0].copy(reply = ChatReply(7, "Анна", "Идём?"))
        assertEquals("Анна", HistoryMerge.messages(listOf(named), listOf(items[0])).single().reply!!.sender)
    }
}
