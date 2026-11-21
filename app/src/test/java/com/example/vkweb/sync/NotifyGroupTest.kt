package com.example.vkweb.sync

import com.example.vkweb.messages.DialogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сгруппированные уведомления (4.48): что поставить, что убрать, когда сводка и кто звучит. */
class NotifyGroupTest {

    private fun d(peer: String, muted: Boolean = false) =
        DialogEntry(peerId = peer, title = "T$peer", snippet = "s$peer", time = "", unread = true, online = false, muted = muted)

    @Test fun `один диалог — без сводки, звучит сам`() {
        val p = NotifyGroup.plan(listOf(d("1")), listOf(d("1")), emptyMap(), emptyMap())
        assertEquals(listOf("1"), p.post.map { it.dialog.peerId })
        assertTrue(p.post.single().loud)
        assertNull(p.summary)
        assertFalse(p.childrenSilent)
    }

    @Test fun `два диалога — у каждого своё, сводка звучит одна`() {
        val p = NotifyGroup.plan(listOf(d("1"), d("2")), listOf(d("1"), d("2")), emptyMap(), emptyMap())
        assertEquals(listOf("1", "2"), p.post.map { it.dialog.peerId })
        assertTrue(p.childrenSilent)
        val s = p.summary!!
        assertEquals(listOf("1", "2"), s.dialogs.map { it.peerId })
        assertTrue(s.alerts)
        assertTrue(s.loudChannel)
    }

    @Test fun `новое в одном — остальные не ставятся заново, но остаются в сводке`() {
        val p = NotifyGroup.plan(listOf(d("1"), d("2")), listOf(d("2")), shown = mapOf("1" to true), rules = emptyMap())
        assertEquals(listOf("2"), p.post.map { it.dialog.peerId })
        assertEquals(listOf("1", "2"), p.summary!!.dialogs.map { it.peerId })
        assertTrue(p.cancel.isEmpty())
    }

    @Test fun `прочитанное в другом месте убирается, сводка уходит, когда остался один`() {
        val p = NotifyGroup.plan(listOf(d("2")), emptyList(), shown = mapOf("1" to true, "2" to true), rules = emptyMap())
        assertEquals(setOf("1"), p.cancel)
        assertTrue(p.post.isEmpty())
        assertNull(p.summary)
    }

    @Test fun `смахнутое без нового не возвращается`() {
        val p = NotifyGroup.plan(listOf(d("1"), d("2")), listOf(d("2")), shown = emptyMap(), rules = emptyMap())
        assertEquals(listOf("2"), p.post.map { it.dialog.peerId })
        assertNull(p.summary)
    }

    @Test fun `новое только в тихом диалоге — сводка не звучит, но остаётся в обычном канале`() {
        val rules = mapOf("2" to DialogNotify(NotifyMode.SILENT))
        val p = NotifyGroup.plan(listOf(d("1"), d("2")), listOf(d("2")), shown = mapOf("1" to true), rules = rules)
        assertFalse(p.post.single().loud)
        val s = p.summary!!
        assertFalse(s.alerts)
        assertTrue(s.loudChannel)
    }

    @Test fun `все показанные тихие — сводка в тихом канале`() {
        val rules = mapOf("1" to DialogNotify(NotifyMode.SILENT), "2" to DialogNotify(NotifyMode.SILENT))
        val p = NotifyGroup.plan(listOf(d("1"), d("2")), listOf(d("1"), d("2")), emptyMap(), rules)
        assertFalse(p.summary!!.loudChannel)
        assertFalse(p.summary!!.alerts)
    }

    @Test fun `«всегда» звучит и в заглушённой в VK беседе`() {
        val rules = mapOf("1" to DialogNotify(NotifyMode.ALWAYS))
        val p = NotifyGroup.plan(listOf(d("1", muted = true)), listOf(d("1", muted = true)), emptyMap(), rules)
        assertTrue(p.post.single().loud)
    }

    @Test fun `ничего непрочитанного — всё убирается`() {
        val p = NotifyGroup.plan(emptyList(), emptyList(), shown = mapOf("1" to false), rules = emptyMap())
        assertEquals(setOf("1"), p.cancel)
        assertNull(p.summary)
    }

    @Test fun `сводка нужна от двух`() {
        assertFalse(NotifyGroup.keepsSummary(1))
        assertTrue(NotifyGroup.keepsSummary(2))
    }
}
