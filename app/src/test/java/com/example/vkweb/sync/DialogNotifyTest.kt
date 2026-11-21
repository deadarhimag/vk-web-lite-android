package com.example.vkweb.sync

import com.example.vkweb.messages.DialogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Свои настройки уведомлений диалогов (4.47). */
class DialogNotifyTest {

    private fun d(peer: String, muted: Boolean = false, snippet: String = "привет") =
        DialogEntry(peerId = peer, title = "T$peer", snippet = snippet, time = "", unread = true, online = false, muted = muted)

    @Test fun `как в VK — заглушённая в VK беседа молчит, обычная звучит`() {
        assertTrue(DialogNotifications.shows(d("1"), null))
        assertTrue(DialogNotifications.sounds(d("1"), null))
        assertFalse(DialogNotifications.shows(d("1", muted = true), null))
        assertTrue(DialogNotifications.quiet(d("1", muted = true), DialogNotify()))
    }

    @Test fun `всегда — даже если в VK выключены`() {
        val r = DialogNotify(NotifyMode.ALWAYS)
        assertTrue(DialogNotifications.shows(d("1", muted = true), r))
        assertTrue(DialogNotifications.sounds(d("1", muted = true), r))
        assertFalse(DialogNotifications.quiet(d("1", muted = true), r))
    }

    @Test fun `без звука — в уведомлении, но тихо`() {
        val r = DialogNotify(NotifyMode.SILENT)
        assertTrue(DialogNotifications.shows(d("1"), r))
        assertFalse(DialogNotifications.sounds(d("1"), r))
        assertTrue(DialogNotifications.quiet(d("1"), r))
    }

    @Test fun `не уведомлять — нигде`() {
        val r = DialogNotify(NotifyMode.OFF)
        assertFalse(DialogNotifications.shows(d("1"), r))
        assertFalse(DialogNotifications.sounds(d("1"), r))
    }

    @Test fun `уведомление звучит, если звучит хоть один из новых`() {
        val rules = mapOf("1" to DialogNotify(NotifyMode.SILENT))
        assertFalse(DialogNotifications.alerts(listOf(d("1")), rules))
        assertTrue(DialogNotifications.alerts(listOf(d("1"), d("2")), rules))
        assertFalse(DialogNotifications.alerts(emptyList(), rules))
    }

    @Test fun `текст — по общей настройке и по своей у диалога`() {
        val rules = mapOf("1" to DialogNotify(hideText = true))
        assertFalse(DialogNotifications.showsText("1", rules, preview = true))
        assertTrue(DialogNotifications.showsText("2", rules, preview = true))
        assertFalse(DialogNotifications.showsText("2", rules, preview = false))
    }

    @Test fun `настройка по умолчанию не хранится`() {
        var rules = DialogNotifications.with(emptyMap(), "1", DialogNotify(NotifyMode.OFF, title = "Аня"))
        assertEquals(setOf("1"), rules.keys)
        rules = DialogNotifications.with(rules, "1", DialogNotify(title = "Аня"))
        assertTrue(rules.isEmpty())
        assertTrue(DialogNotifications.with(emptyMap(), "", DialogNotify(NotifyMode.OFF)).isEmpty())
    }

    @Test fun `сохраняется и читается без потерь`() {
        val rules = mapOf(
            "1" to DialogNotify(NotifyMode.SILENT, hideText = true, title = "Аня"),
            "2000000005" to DialogNotify(NotifyMode.OFF, title = "Беседа «дом»"),
            "-7" to DialogNotify(NotifyMode.ALWAYS, title = ""),
        )
        assertEquals(rules, DialogNotifications.fromJson(DialogNotifications.toJson(rules)))
    }

    @Test fun `повреждённое и неизвестное читается безопасно`() {
        assertTrue(DialogNotifications.fromJson("{ не json").isEmpty())
        assertTrue(DialogNotifications.fromJson(null).isEmpty())
        // Неизвестный режим — «как в VK», а такая запись не хранится.
        assertTrue(DialogNotifications.fromJson("""{"1":{"m":"loud"}}""").isEmpty())
        assertEquals(NotifyMode.DEFAULT, NotifyMode.of("что-то"))
        // Неизвестный режим, но скрытый текст — запись остаётся.
        assertEquals(DialogNotify(NotifyMode.DEFAULT, hideText = true), DialogNotifications.fromJson("""{"1":{"m":"x","h":true}}""")["1"])
    }
}

/** Число на «Сообщениях» нижней панели (4.50). */
class BadgeCountTest {
    private fun d(peer: String, unread: Boolean = true, muted: Boolean = false) =
        DialogEntry(peerId = peer, title = "", snippet = "", time = "", unread = unread, online = false, muted = muted)

    @Test fun `считаются непрочитанные, о которых сообщают`() {
        val dialogs = listOf(d("1"), d("2", muted = true), d("3", unread = false), d("4"), d("5"), d("6", muted = true))
        val rules = mapOf(
            "4" to DialogNotify(NotifyMode.OFF),
            "5" to DialogNotify(NotifyMode.SILENT),
            "6" to DialogNotify(NotifyMode.ALWAYS),
        )
        // 1 — обычный; 5 — без звука, но в шторке; 6 — «всегда»; 2 — выключен в VK; 3 — прочитан; 4 — «не уведомлять».
        assertEquals(3, DialogNotifications.badgeCount(dialogs, rules))
    }
}
