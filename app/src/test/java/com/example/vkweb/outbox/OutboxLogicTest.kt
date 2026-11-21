package com.example.vkweb.outbox

import com.example.vkweb.messages.ChatMessage
import com.example.vkweb.messages.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxLogicTest {

    private val peer = "100"

    private fun item(id: String, text: String = "привет", at: Long = 1, peer: String = this.peer) =
        OutboxItem(id = id, peerId = peer, text = text, createdAt = at)

    private fun mine(id: String, text: String) = ChatMessage(id = id, out = true, text = text, time = "")
    private fun theirs(id: String, text: String) = ChatMessage(id = id, out = false, text = text, time = "")

    private fun state(items: List<OutboxItem>, id: String) = items.first { it.id == id }.state

    // ------------------------------------------------------------ порядок отправки

    @Test fun `порядок — сначала самое раннее, одно в полёте`() {
        val items = listOf(item("b", at = 2), item("a", at = 1))
        assertEquals("a", OutboxLogic.nextToSend(items, peer, now = 10)?.id)
        val sending = OutboxLogic.markClicked(items, "a", now = 10, baseline = emptyList())
        assertNull("пока первое в полёте, второе не отправляется", OutboxLogic.nextToSend(sending, peer, now = 11))
    }

    @Test fun `другая переписка не мешает`() {
        val items = listOf(item("a", peer = "100"), item("b", peer = "200"))
        val sending = OutboxLogic.markClicked(items, "a", now = 1, baseline = emptyList())
        assertEquals("b", OutboxLogic.nextToSend(sending, "200", now = 2)?.id)
        assertNull(OutboxLogic.nextToSend(sending, "300", now = 2))
    }

    @Test fun `сомнительное и неудавшееся очередь не блокируют`() {
        val items = listOf(item("a", at = 1), item("b", at = 2))
        val doubtful = OutboxLogic.restore(OutboxLogic.markClicked(items, "a", now = 1, baseline = emptyList()))
        assertEquals(OutboxState.UNCONFIRMED, state(doubtful, "a"))
        assertEquals("b", OutboxLogic.nextToSend(doubtful, peer, now = 2)?.id)
    }

    // ------------------------------------------------------------ доставка

    @Test fun `доставлено, когда в переписке появилось новое подтверждённое сообщение`() {
        val items = OutboxLogic.markClicked(listOf(item("a", "привет")), "a", now = 100, baseline = emptyList())
        val history = listOf(theirs("c1", "как дела"), mine("c2", "привет"))
        val result = OutboxLogic.reconcile(items, peer, history, now = 101)
        assertEquals(emptyList<OutboxItem>(), result.items)
        assertEquals(listOf("a"), result.delivered.map { it.id })
    }

    @Test fun `черновое сообщение страницы не считается доставкой`() {
        val items = OutboxLogic.markClicked(listOf(item("a", "привет")), "a", now = 100, baseline = emptyList())
        val history = listOf(mine("local1759", "привет"))
        val result = OutboxLogic.reconcile(items, peer, history, now = 101)
        assertEquals(1, result.items.size)
        assertTrue(result.delivered.isEmpty())
    }

    @Test fun `прежнее такое же сообщение не считается доставкой нового`() {
        val before = listOf(mine("c5", "ок"))
        val baseline = OutboxLogic.baselineFor(item("a", "ок"), before)
        assertEquals(listOf("c5"), baseline)
        val items = OutboxLogic.markClicked(listOf(item("a", "ок")), "a", now = 100, baseline = baseline)
        // В истории по-прежнему только старое «ок» — наше ещё не дошло.
        assertTrue(OutboxLogic.reconcile(items, peer, before, now = 101).delivered.isEmpty())
        // Появилось второе «ок» — вот оно и есть доставленное.
        val after = before + mine("c6", "ок")
        assertEquals(listOf("a"), OutboxLogic.reconcile(items, peer, after, now = 102).delivered.map { it.id })
    }

    @Test fun `одно сообщение истории засчитывается одному ожидающему`() {
        val items = OutboxLogic.markClicked(
            listOf(item("a", "ок", at = 1), item("b", "ок", at = 2)), "a", 100, emptyList(),
        )
            .let { OutboxLogic.markClicked(it, "b", 100, emptyList()) }
        val oneDelivered = OutboxLogic.reconcile(items, peer, listOf(mine("c1", "ок")), now = 101)
        assertTrue(oneDelivered.delivered.isEmpty())
        assertEquals(listOf("a", "b"), oneDelivered.items.map { it.id })
        assertTrue(oneDelivered.items.all { it.confirmationBlocked })
        val bothDelivered = OutboxLogic.reconcile(items, peer, listOf(mine("c1", "ок"), mine("c2", "ок")), now = 101)
        assertTrue(bothDelivered.delivered.isEmpty())
    }

    @Test fun `пробелы и переводы строк по краям не мешают сравнению`() {
        val items = OutboxLogic.markClicked(listOf(item("a", "  привет\n  мир  ")), "a", 100, emptyList())
        val history = listOf(mine("c1", "привет мир"))
        assertEquals(1, OutboxLogic.reconcile(items, peer, history, now = 101).delivered.size)
    }

    @Test fun `чужие сообщения с тем же текстом не засчитываются`() {
        val items = OutboxLogic.markClicked(listOf(item("a", "привет")), "a", 100, emptyList())
        assertTrue(OutboxLogic.reconcile(items, peer, listOf(theirs("c1", "привет")), now = 101).delivered.isEmpty())
    }

    @Test fun `сверка не трогает чужую переписку и неотправленные`() {
        val items = listOf(item("q"), item("o", "привет", peer = "200"))
            .let { OutboxLogic.markClicked(it, "o", 100, emptyList()) }
        val result = OutboxLogic.reconcile(items, peer, listOf(mine("c1", "привет")), now = 101)
        assertEquals(2, result.items.size)
        assertTrue(result.delivered.isEmpty())
    }

    // ------------------------------------------------------------ сомнительное

    @Test fun `не дождались подтверждения — сомнительное, но доставка потом всё равно находится`() {
        val sending = OutboxLogic.markClicked(listOf(item("a", "привет")), "a", now = 1_000, baseline = emptyList())
        val early = OutboxLogic.reconcile(sending, peer, emptyList(), now = 1_000 + OutboxLogic.CONFIRM_TIMEOUT_MS - 1)
        assertEquals(OutboxState.SENDING, state(early.items, "a"))
        val late = OutboxLogic.reconcile(sending, peer, emptyList(), now = 1_000 + OutboxLogic.CONFIRM_TIMEOUT_MS)
        assertEquals(OutboxState.UNCONFIRMED, state(late.items, "a"))
        val found = OutboxLogic.reconcile(late.items, peer, listOf(mine("c9", "привет")), now = 999_999)
        assertEquals(listOf("a"), found.delivered.map { it.id })
        assertTrue(found.items.isEmpty())
    }

    @Test fun `сомнительное само не отправляется повторно`() {
        val doubtful = OutboxLogic.restore(OutboxLogic.markClicked(listOf(item("a")), "a", 1, emptyList()))
        assertNull(OutboxLogic.nextToSend(doubtful, peer, now = Long.MAX_VALUE / 2))
    }

    @Test fun `повтор по решению человека сбрасывает всё прошлое`() {
        val doubtful = OutboxLogic.restore(OutboxLogic.markClicked(listOf(item("a")), "a", 1, listOf("c1")))
        val again = OutboxLogic.retry(doubtful, "a")
        val a = again.first()
        assertEquals(OutboxState.QUEUED, a.state)
        assertEquals(0, a.attempts)
        assertEquals(0L, a.sentAt)
        assertTrue(a.baseline.isEmpty())
        assertEquals("a", OutboxLogic.nextToSend(again, peer, now = 2)?.id)
    }

    @Test fun `повторить можно только сомнительное и неудавшееся`() {
        val queued = listOf(item("a"))
        assertEquals(queued, OutboxLogic.retry(queued, "a"))
        val sending = OutboxLogic.markClicked(queued, "a", 1, emptyList())
        assertEquals(OutboxState.SENDING, state(OutboxLogic.retry(sending, "a"), "a"))
    }

    // ------------------------------------------------------------ не нажалось

    @Test fun `страница не приняла ввод — повтор позже с нарастающей паузой`() {
        var items = listOf(item("a"))
        items = OutboxLogic.markNotSent(items, "a", now = 1_000)
        assertEquals(OutboxState.QUEUED, state(items, "a"))
        assertNull("пауза ещё не прошла", OutboxLogic.nextToSend(items, peer, now = 1_000 + 2_999))
        assertEquals("a", OutboxLogic.nextToSend(items, peer, now = 1_000 + 3_000)?.id)
        val second = OutboxLogic.markNotSent(items, "a", now = 10_000)
        assertEquals(10_000L + 10_000, second.first().retryAt)
    }

    @Test fun `после нескольких неудач — не удалось`() {
        var items = listOf(item("a"))
        repeat(OutboxLogic.MAX_ATTEMPTS) { items = OutboxLogic.markNotSent(items, "a", now = 1) }
        assertEquals(OutboxState.FAILED, state(items, "a"))
        assertNull(OutboxLogic.nextToSend(items, peer, now = Long.MAX_VALUE / 2))
    }

    @Test fun `второе сообщение не обгоняет первое во время паузы`() {
        val items = OutboxLogic.markNotSent(listOf(item("a", at = 1), item("b", at = 2)), "a", now = 1_000)
        assertNull(OutboxLogic.nextToSend(items, peer, now = 1_500))
    }

    // ------------------------------------------------------------ показ

    @Test fun `в переписке сначала сообщения сервера, затем неотправленные с пометкой`() {
        val server = listOf(theirs("c1", "привет"), mine("c2", "как дела"))
        val items = listOf(item("a", "ты где", at = 5))
        val shown = OutboxLogic.visibleMessages(server, items, peer)
        assertEquals(listOf("c1", "c2", "o:a"), shown.map { it.id })
        assertEquals(MessageStatus.QUEUED, shown.last().status)
        assertFalse(shown.first().isPending)
        assertTrue(shown.last().isPending)
    }

    @Test fun `черновая копия страницы прячется, пока сообщение в очереди`() {
        val server = listOf(mine("c1", "раньше"), mine("local77", "привет"))
        val items = OutboxLogic.markClicked(listOf(item("a", "привет")), "a", 1, emptyList())
        val shown = OutboxLogic.visibleMessages(server, items, peer)
        assertEquals(listOf("c1", "o:a"), shown.map { it.id })
        assertEquals(MessageStatus.SENDING, shown.last().status)
    }

    @Test fun `подтверждённое сообщение с таким же текстом не прячется`() {
        val server = listOf(mine("c1", "привет"))
        val items = OutboxLogic.markClicked(listOf(item("a", "привет")), "a", 1, listOf("c1"))
        assertEquals(listOf("c1", "o:a"), OutboxLogic.visibleMessages(server, items, peer).map { it.id })
    }

    @Test fun `чужая переписка в показ не попадает`() {
        val items = listOf(item("a", peer = "200"))
        assertEquals(emptyList<ChatMessage>(), OutboxLogic.visibleMessages(emptyList(), items, peer))
        assertEquals(setOf("200"), OutboxLogic.pendingPeers(items))
    }

    // ------------------------------------------------------------ прочее

    @Test fun `запуск после закрытия приложения — нажатое становится сомнительным`() {
        val items = OutboxLogic.markClicked(listOf(item("a"), item("b", at = 2)), "a", 1, emptyList())
        val restored = OutboxLogic.restore(items)
        assertEquals(OutboxState.UNCONFIRMED, state(restored, "a"))
        assertEquals(OutboxState.QUEUED, state(restored, "b"))
    }

    @Test fun `когда проверить очередь снова`() {
        assertNull(OutboxLogic.nextWakeupDelay(listOf(item("a")), now = 10))
        val waiting = OutboxLogic.markNotSent(listOf(item("a")), "a", now = 1_000)
        assertEquals(3_000L - 500, OutboxLogic.nextWakeupDelay(waiting, now = 1_500))
        val sending = OutboxLogic.markClicked(listOf(item("a")), "a", now = 1_000, baseline = emptyList())
        assertEquals(OutboxLogic.CONFIRM_TIMEOUT_MS - 1_000, OutboxLogic.nextWakeupDelay(sending, now = 2_000))
        assertEquals(0L, OutboxLogic.nextWakeupDelay(sending, now = 1_000 + OutboxLogic.CONFIRM_TIMEOUT_MS + 5))
    }

    @Test fun `очередь ограничена`() {
        var items = emptyList<OutboxItem>()
        repeat(OutboxLogic.MAX_ITEMS + 5) { items = OutboxLogic.enqueue(items, item("i$it")) }
        assertEquals(OutboxLogic.MAX_ITEMS, items.size)
        assertTrue(OutboxLogic.isFull(items))
    }

    @Test fun `сохранение и чтение очереди без потерь`() {
        val original = listOf(
            item("a", "привет, \"мир\"\nвторая строка", at = 10).copy(
                state = OutboxState.UNCONFIRMED, attempts = 2, sentAt = 55, retryAt = 70, baseline = listOf("c1", "c2"),
                confirmedThrough = 42, confirmationBlocked = true,
            ),
            item("b", "😀 эмодзи и юникод — ✓", at = 20, peer = "-5826399"),
        )
        assertEquals(original, OutboxItem.listFromJson(OutboxItem.listToJson(original)))
    }

    @Test fun `повреждённая запись пропускается, остальные читаются`() {
        val raw = """[{"id":"ok","peerId":"100","text":"целое","createdAt":1,"state":"QUEUED"},
            {"id":"","peerId":"100","text":"без id"},
            {"id":"x","peerId":"не число","text":"плохой собеседник"},
            {"id":"y","peerId":"100","text":"   "},
            {"id":"z","peerId":"100","text":"новое состояние","state":"ЧТО-ТО-НОВОЕ"}]"""
        val items = OutboxItem.listFromJson(raw)
        assertEquals(listOf("ok", "z"), items.map { it.id })
        assertEquals(OutboxState.QUEUED, items.last().state)
    }

    @Test fun `ожидание ограничено в любой переписке`() {
        val items = OutboxLogic.markClicked(
            listOf(item("a", peer = "100"), item("b", peer = "200", at = 2)), "a", now = 1_000, baseline = emptyList(),
        )
            .let { OutboxLogic.markClicked(it, "b", now = 40_000, baseline = emptyList()) }
        val later = OutboxLogic.expire(items, now = 1_000 + OutboxLogic.CONFIRM_TIMEOUT_MS)
        assertEquals(OutboxState.UNCONFIRMED, state(later, "a"))
        assertEquals("ещё не вышло время", OutboxState.SENDING, state(later, "b"))
    }

    // ------------------------------------------------------------ ответ отправки (messages.send)

    @Test fun `успешный ответ отправки подтверждает ожидающее сообщение с тем же текстом`() {
        val items = listOf(item("a", "привет").copy(state = OutboxState.SENDING), item("b", "пока").copy(state = OutboxState.SENDING))
        val r = OutboxLogic.deliveredByServer(items, peer, "  привет ", cmid = 1)
        assertEquals(listOf("a"), r.delivered.map { it.id })
        assertEquals(listOf("b"), r.items.map { it.id })
    }

    @Test fun `ответ отправки не трогает чужую переписку, ждущие в очереди и другой текст`() {
        val items = listOf(
            item("other", "привет", peer = "200").copy(state = OutboxState.SENDING),
            item("queued", "привет").copy(state = OutboxState.QUEUED),
            item("text", "другое").copy(state = OutboxState.SENDING),
        )
        val r = OutboxLogic.deliveredByServer(items, peer, "привет", cmid = 1)
        assertTrue(r.delivered.isEmpty())
        assertEquals(items, r.items)
    }

    @Test fun `неоднозначный ответ не удаляет одинаковые ожидающие сообщения`() {
        val items = listOf(item("a", "ок", at = 1).copy(state = OutboxState.SENDING), item("b", "ок", at = 2).copy(state = OutboxState.UNCONFIRMED))
        val first = OutboxLogic.deliveredByServer(items, peer, "ок", cmid = 1)
        assertTrue(first.delivered.isEmpty())
        assertEquals(2, first.items.size)
        val second = OutboxLogic.reconcile(first.items, peer, listOf(mine("c1", "ок")), 101)
        assertTrue(second.delivered.isEmpty())
        // Even discarding one ambiguous entry does not make the same evidence safe for the other.
        val afterDiscard = OutboxLogic.discard(second.items, "a")
        assertTrue(OutboxLogic.reconcile(afterDiscard, peer, listOf(mine("c1", "ок")), 102).delivered.isEmpty())
    }

    @Test fun `ответ без номера переписки или серверного сообщения не подтверждает очередь`() {
        val items = listOf(item("a", "привет", peer = "300").copy(state = OutboxState.SENDING))
        assertTrue(OutboxLogic.deliveredByServer(items, "", "привет", cmid = 1).delivered.isEmpty())
        assertTrue(OutboxLogic.deliveredByServer(items, "300", "привет").delivered.isEmpty())
    }

    @Test fun `ручная проверка и обычное ожидание различаются в сообщениях интерфейса`() {
        val entries = listOf(item("manual").copy(state = OutboxState.UNCONFIRMED, confirmationBlocked = true),
            item("waiting").copy(state = OutboxState.UNCONFIRMED))
        val messages = OutboxLogic.visibleMessages(emptyList(), entries, peer)
        assertTrue(messages[0].deliveryNeedsReview)
        assertFalse(messages[1].deliveryNeedsReview)
    }

    @Test fun `повтор ответа и истории не подтверждает следующее одинаковое сообщение`() {
        val items = listOf(item("a", "ок").copy(state = OutboxState.SENDING), item("b", "ок", at = 2))
        val first = OutboxLogic.deliveredByServer(items, peer, "ок", cmid = 10)
        assertEquals(listOf("a"), first.delivered.map { it.id })
        val next = OutboxLogic.markClicked(first.items, "b", 102, emptyList())
        assertTrue(OutboxLogic.deliveredByServer(next, peer, "ок", cmid = 10).delivered.isEmpty())
        assertTrue(OutboxLogic.reconcile(next, peer, listOf(mine("c10", "ок")), 103).delivered.isEmpty())
        assertEquals(listOf("b"), OutboxLogic.deliveredByServer(next, peer, "ок", cmid = 11).delivered.map { it.id })
    }

    @Test fun `номер использованной истории сохраняется для следующего одинакового сообщения`() {
        val items = listOf(item("a", "ок").copy(state = OutboxState.SENDING), item("b", "ок", at = 2))
        val first = OutboxLogic.reconcile(items, peer, listOf(mine("c10", "ок")), 101)
        val restored = OutboxLogic.restore(first.items)
        val next = OutboxLogic.markClicked(restored, "b", 102, emptyList())
        assertTrue(OutboxLogic.reconcile(next, peer, listOf(mine("c10", "ок")), 103).delivered.isEmpty())
        assertEquals(listOf("b"), OutboxLogic.reconcile(next, peer, listOf(mine("c11", "ок")), 104).delivered.map { it.id })
    }
}
