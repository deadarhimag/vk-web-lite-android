package com.example.vkweb.outbox

import com.example.vkweb.messages.MessageStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class OutboxScheduleTest {
    @Test fun `ready conversation is not delayed by another conversation backoff`() {
        val now = 100_000L
        val ready = OutboxItem("a", "5", "ready", 1)
        val later = OutboxItem("b", "6", "retry", 2, retryAt = now + 60_000)
        assertEquals(0L, OutboxLogic.nextRetryDelay(listOf(ready, later), now))
    }

    @Test fun `retry respects order within a conversation`() {
        val now = 100_000L
        val head = OutboxItem("a", "5", "first", 1, retryAt = now + 10_000)
        val tail = OutboxItem("b", "5", "second", 2)
        assertEquals(10_000L, OutboxLogic.nextRetryDelay(listOf(tail, head), now))
    }

    @Test fun `future schedules do not hold ordinary retries`() {
        val now = 100_000L
        val scheduled = OutboxItem("a", "5", "scheduled", 1, sendAt = now + 300_000, retryAt = now + 400_000)
        val retry = OutboxItem("b", "6", "retry", 2, retryAt = now + 3_000)
        assertEquals(3_000L, OutboxLogic.nextRetryDelay(listOf(scheduled, retry), now))
        assertEquals(0L, OutboxLogic.nextRetryDelay(emptyList(), now))
    }

    private val now = 1_000_000_000L
    private fun item(id: String, createdAt: Long, sendAt: Long = 0, peer: String = "7") =
        OutboxItem(id = id, peerId = peer, text = "t$id", createdAt = createdAt, sendAt = sendAt)

    @Test fun `отложенное не держит очередь, своё время — уходит`() {
        val items = listOf(item("a", 1, sendAt = now + 60_000), item("b", 2))
        assertEquals("b", OutboxLogic.nextToSend(items, "7", now)?.id)
        val later = listOf(item("a", 1, sendAt = now + 60_000))
        assertNull(OutboxLogic.nextToSend(later, "7", now))
        assertEquals("a", OutboxLogic.nextToSend(later, "7", now + 60_000)?.id)
    }

    @Test fun `пробуждение — ко времени отложенного, работа для фона — только пришедшее`() {
        val items = listOf(item("a", 1, sendAt = now + 90_000))
        assertEquals(90_000L, OutboxLogic.nextWakeupDelay(items, now))
        assertFalse(OutboxLogic.hasDueWork(items, now))
        assertEquals(now + 90_000, OutboxLogic.nextScheduledAt(items, now))
        assertTrue(OutboxLogic.hasDueWork(items, now + 90_000))
        assertEquals(setOf("7"), OutboxLogic.duePeers(items, now + 90_000))
        assertEquals(emptySet<String>(), OutboxLogic.duePeers(listOf(item("b", 2)), now))
    }

    @Test fun `в переписке — «запланировано» с временем`() {
        val shown = OutboxLogic.visibleMessages(emptyList(), listOf(item("a", 1, sendAt = now + 3_600_000)), "7", now)
        assertEquals(MessageStatus.SCHEDULED, shown.single().status)
        assertTrue(shown.single().time.isNotEmpty())
        val due = OutboxLogic.visibleMessages(emptyList(), listOf(item("a", 1, sendAt = now - 1)), "7", now)
        assertEquals(MessageStatus.QUEUED, due.single().status)
    }

    @Test fun `подпись времени — сегодня, завтра, дата`() {
        val zone = ZoneId.of("Europe/Moscow")
        fun t(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
        val base = t(14, 13, 0)
        assertEquals("в 18:30", OutboxLogic.scheduleLabel(t(14, 18, 30), base, zone))
        assertEquals("завтра в 09:00", OutboxLogic.scheduleLabel(t(15, 9, 0), base, zone))
        assertEquals("20 октября в 09:00", OutboxLogic.scheduleLabel(t(20, 9, 0), base, zone))
    }

    @Test fun `время отправки и название переживают сохранение`() {
        val i = item("a", 1, sendAt = now).copy(title = "Аня")
        assertEquals(i, OutboxItem.fromJson(JSONObject(i.toJson().toString())))
    }

    @Test fun `номер отправки — постоянный и в пределах API (4_22)`() {
        val a = OutboxLogic.randomIdOf("item-1")
        assertEquals(a, OutboxLogic.randomIdOf("item-1"))
        assertTrue(a in 1..Int.MAX_VALUE)
        assertTrue(OutboxLogic.randomIdOf("") >= 1)
    }
}
