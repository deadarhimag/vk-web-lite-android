package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryMergeTest {

    private fun msg(id: String, text: String = id) = ChatMessage(id = id, out = false, text = text, time = "")
    private fun dlg(peer: String, snippet: String = "") = DialogEntry(peer, "Имя $peer", snippet, "", false, false)

    @Test fun `частичная порция не сжимает показанную переписку`() {
        val saved = (1..30).map { msg("c$it") }
        val partial = listOf(msg("c30", "обновлено"))
        val merged = HistoryMerge.messages(saved, partial)
        assertEquals(30, merged.size)
        assertEquals("обновлено", merged.last().text)
    }

    @Test fun `новые сообщения добавляются в конец по номеру`() {
        val shown = listOf(msg("c1"), msg("c2"))
        val merged = HistoryMerge.messages(shown, listOf(msg("c4"), msg("c3")))
        assertEquals(listOf("c1", "c2", "c3", "c4"), merged.map { it.id })
    }

    @Test fun `старые сообщения из догрузки встают в начало`() {
        val shown = listOf(msg("c10"), msg("c11"))
        val merged = HistoryMerge.messages(shown, listOf(msg("c8"), msg("c9")))
        assertEquals(listOf("c8", "c9", "c10", "c11"), merged.map { it.id })
    }

    @Test fun `черновое сообщение исчезает, когда в новой порции его уже нет`() {
        val shown = listOf(msg("c1"), msg("local1", "привет"))
        val merged = HistoryMerge.messages(shown, listOf(msg("c1"), msg("c2", "привет")))
        assertEquals(listOf("c1", "c2"), merged.map { it.id })
    }

    @Test fun `черновое из новой порции остаётся в конце`() {
        val merged = HistoryMerge.messages(listOf(msg("c1")), listOf(msg("c1"), msg("dom5_x")))
        assertEquals(listOf("c1", "dom5_x"), merged.map { it.id })
    }

    @Test fun `лимит оставляет самые новые`() {
        val merged = HistoryMerge.messages((1..10).map { msg("c$it") }, emptyList(), limit = 3)
        assertEquals(listOf("c8", "c9", "c10"), merged.map { it.id })
    }

    @Test fun `диалоги — порядок из свежих, остальные из сохранённого после них`() {
        val saved = listOf(dlg("1", "старое"), dlg("2"), dlg("3"))
        val fresh = listOf(dlg("2", "новое"), dlg("1", "свежее"))
        val merged = HistoryMerge.dialogs(saved, fresh)
        assertEquals(listOf("2", "1", "3"), merged.map { it.peerId })
        assertEquals("свежее", merged[1].snippet)
    }

    @Test fun `пустая свежая порция не стирает список`() {
        val saved = listOf(dlg("1"))
        assertEquals(saved, HistoryMerge.dialogs(saved, emptyList()))
    }

    @Test fun `высота фото по размерам и в пределах`() {
        assertEquals(180, ChatPhoto("u", 4000, 3000).heightFor(240, 80, 320))
        assertEquals(320, ChatPhoto("u", 1000, 3000).heightFor(240, 80, 320))   // очень высокое — обрезается
        assertEquals(80, ChatPhoto("u", 3000, 500).heightFor(240, 80, 320))     // очень широкое — минимум
        assertEquals(180, ChatPhoto("u").heightFor(240, 80, 320))               // размеры неизвестны — 4:3
    }

    @Test fun `черновое, повторяющее настоящее по тексту, не показывается дублем`() {
        val real = ChatMessage(id = "c10", out = false, text = "Привет, как дела?", time = "12:30", sender = "")
        // В вёрстке к тексту прилипло время — раньше такая строка становилась дублем в конце переписки.
        val draft = ChatMessage(id = "dom20_x", out = true, text = "Привет, как дела? 12:30", time = "", sender = "")
        val merged = HistoryMerge.messages(listOf(real), listOf(real, draft))
        assertEquals(listOf("c10"), merged.map { it.id })
    }

    @Test fun `настоящее новое черновое остаётся`() {
        val real = ChatMessage(id = "c10", out = false, text = "Привет", time = "", sender = "")
        val draft = ChatMessage(id = "dom9_x", out = true, text = "Совсем другое", time = "", sender = "")
        assertEquals(listOf("c10", "dom9_x"), HistoryMerge.messages(listOf(real), listOf(real, draft)).map { it.id })
    }

    @Test fun `на диск — только подтверждённые сервером`() {
        val list = listOf(
            ChatMessage(id = "c1", out = false, text = "a", time = "", sender = ""),
            ChatMessage(id = "dom5_x", out = true, text = "b", time = "", sender = ""),
            ChatMessage(id = "i3", out = false, text = "c", time = "", sender = ""),
        )
        assertEquals(listOf("c1"), HistoryMerge.persistable(list).map { it.id })
    }

    @Test fun `сравнение по тексту без регистра, пробелов и знаков`() {
        assertEquals("приветкакдела1230", HistoryMerge.matchText("Привет, как дела? 12:30"))
        assertEquals(true, HistoryMerge.duplicatesKnown("ПРИВЕТ!!", listOf("привет")))
        assertEquals(false, HistoryMerge.duplicatesKnown("ок", listOf("окно")))
    }

    @Test fun `урезанная версия того же сообщения не затирает полную`() {
        val full = ChatMessage(
            id = "c10", out = false, text = "Смотри", time = "12:30", sender = "Аня",
            photos = listOf(ChatPhoto("https://sun9-1.vkuserphoto.ru/a.jpg", 800, 600)),
        )
        val lite = ChatMessage(id = "c10", out = false, text = "Смотри", time = "12:30") // из списка диалогов
        val merged = HistoryMerge.messages(listOf(full), listOf(lite))
        assertEquals(listOf(full), merged)
    }

    @Test fun `исправленный текст берётся из новой порции`() {
        val old = ChatMessage(id = "c10", out = false, text = "Привет", time = "12:30", sender = "Аня")
        val edited = ChatMessage(id = "c10", out = false, text = "Привет!", time = "12:30")
        val merged = HistoryMerge.messages(listOf(old), listOf(edited)).single()
        assertEquals("Привет!", merged.text)
        assertEquals("Аня", merged.sender)
    }

    @Test fun `повторный вход не меняет сохранённое`() {
        val full = (1..5).map { ChatMessage(id = "c$it", out = it % 2 == 0, text = "m$it", time = "10:0$it", sender = if (it % 2 == 0) "" else "Аня") }
        // Сохранённое → неполная порция (одно последнее, без автора) → полная порция: итог тот же, что был.
        var shown = HistoryMerge.messages(full, emptyList())
        shown = HistoryMerge.messages(shown, listOf(full.last().copy(sender = "")))
        shown = HistoryMerge.messages(shown, full)
        assertEquals(full, HistoryMerge.persistable(shown))
    }

    @Test fun `урезанная версия с подписью вместо фото не добавляет подпись`() {
        val full = ChatMessage(id = "c7", out = true, text = "", time = "09:00", photos = listOf(ChatPhoto("https://sun9-2.vkuserphoto.ru/b.jpg")))
        val lite = ChatMessage(id = "c7", out = true, text = "[Фото]", time = "09:00")
        assertEquals(listOf(full), HistoryMerge.messages(listOf(full), listOf(lite)))
    }

    @Test fun `описание изменений называет поля`() {
        val a = ChatMessage(id = "c1", out = false, text = "x", time = "1", sender = "Аня")
        val b = a.copy(sender = "", photos = listOf(ChatPhoto("u")))
        assertEquals("c1: автор+фото 0→1", HistoryMerge.describeChanges(listOf(a), listOf(b)))
        assertEquals("", HistoryMerge.describeChanges(listOf(a), listOf(a)))
    }

    @Test fun `предпросмотр не заменяет известное, даже с другими непустыми полями`() {
        val full = ChatMessage(id = "c9", out = false, text = "", time = "08:00", photos = listOf(ChatPhoto("https://x/big.jpg", 1280, 960)))
        // В списке диалогов у фото меньше размеров — другой адрес; пустое поле правило «не беднее» тут не спасло бы.
        val preview = ChatMessage(id = "c9", out = false, text = "", time = "08:00", photos = listOf(ChatPhoto("https://x/small.jpg", 604, 453)), preview = true)
        assertEquals(listOf(full), HistoryMerge.messages(listOf(full), listOf(preview)))
    }

    @Test fun `предпросмотр нового сообщения показывается, полная версия его заменяет`() {
        val old = ChatMessage(id = "c1", out = false, text = "a", time = "1")
        val preview = ChatMessage(id = "c2", out = false, text = "[Ответ]", time = "2", preview = true)
        val full = ChatMessage(id = "c2", out = false, text = "ответ на вопрос", time = "2")
        val withPreview = HistoryMerge.messages(listOf(old), listOf(preview))
        assertEquals(listOf("c1", "c2"), withPreview.map { it.id })
        assertEquals(full, HistoryMerge.messages(withPreview, listOf(full)).last())
    }

    @Test fun `вход в переписку с отличающимся предпросмотром не меняет сохранённое`() {
        val saved = (1..4).map { ChatMessage(id = "c$it", out = false, text = "m$it", time = "10:0$it", photos = listOf(ChatPhoto("https://x/$it-big.jpg"))) }
        var shown = HistoryMerge.messages(saved, emptyList())
        shown = HistoryMerge.messages(shown, listOf(saved.last().copy(photos = listOf(ChatPhoto("https://x/4-small.jpg")), preview = true)))
        assertEquals("после предпросмотра — без изменений", saved, shown)
        shown = HistoryMerge.messages(shown, saved)
        assertEquals(saved, HistoryMerge.persistable(shown))
    }

    @Test fun `то же фото с другой подписью в адресе — сообщение не меняется`() {
        val known = ChatMessage(id = "c3", out = false, text = "", time = "1",
            photos = listOf(ChatPhoto("https://sun9-62.vkuserphoto.ru/s/v1/ig2/abc.jpg?size=1280x960&quality=95&sign=AAAA&c_uniq_tag=XXXX&type=album", 1280, 960)))
        val fresh = known.copy(
            photos = listOf(ChatPhoto("https://sun9-52.vkuserphoto.ru/s/v1/ig2/abc.jpg?size=1280x960&quality=95&sign=BBBB&c_uniq_tag=YYYY&type=album", 1280, 960)))
        assertEquals(listOf(known), HistoryMerge.messages(listOf(known), listOf(fresh)))
    }

    @Test fun `другое фото — заменяется`() {
        val known = ChatMessage(id = "c3", out = false, text = "", time = "1", photos = listOf(ChatPhoto("https://x/a.jpg?sign=1")))
        val fresh = known.copy(photos = listOf(ChatPhoto("https://x/b.jpg?sign=1")))
        assertEquals(listOf(fresh), HistoryMerge.messages(listOf(known), listOf(fresh)))
    }

    @Test fun `название беседы — настоящее имя, а не заглушка`() {
        assertEquals("Анна Петрова", HistoryMerge.chatTitle("id12345", "Анна Петрова"))
        assertEquals("Анна Петрова", HistoryMerge.chatTitle("Анна Петрова", "id12345"))
        assertEquals("Клуб", HistoryMerge.chatTitle("Сообщество 5", "Клуб"))
        assertEquals("Друзья", HistoryMerge.chatTitle("Друзья", ""))
        assertEquals("id12345", HistoryMerge.chatTitle("id12345", ""))
        assertEquals(true, HistoryMerge.isPlaceholderTitle("Беседа"))
        assertEquals(false, HistoryMerge.isPlaceholderTitle("Беседа выпускников"))
    }
}
