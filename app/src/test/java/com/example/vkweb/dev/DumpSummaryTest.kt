package com.example.vkweb.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DumpSummaryTest {

    private fun line(window: String?, page: String, at: String): String {
        val body = "\"type\":\"dump\",\"page\":\"$page\",\"at\":\"$at\",\"response\":{\"page\":\"inner\",\"at\":\"x\"}}"
        return if (window != null) "{\"window\":\"$window\",$body" else "{$body"
    }

    @Test fun `считает записи, окна, страницы и время`() {
        val s = DumpSummary()
        s.add(line("главное", "m.vk.com/feed", "2026-10-14T10:00:00Z"))
        s.add(line("чтение", "m.vk.com/mail", "2026-10-14T10:05:00Z"))
        s.add(line("главное", "m.vk.com/feed", "2026-10-14T10:09:00Z"))
        s.add(line(null, "m.vk.com/audio", "2026-10-14T10:10:00Z"))
        val json = s.toJson()
        assertEquals(4, json.getInt("records"))
        assertEquals("2026-10-14T10:00:00Z", json.getString("from"))
        assertEquals("2026-10-14T10:10:00Z", json.getString("to"))
        val windows = json.getJSONObject("windows")
        assertEquals(2, windows.getInt("главное"))
        assertEquals(1, windows.getInt("чтение"))
        assertEquals(1, windows.getInt("?"))
        val pages = json.getJSONObject("pages")
        assertEquals(2, pages.getInt("m.vk.com/feed"))
        assertFalse("поля внутри ответа не путаются с полями записи", pages.has("inner"))
    }

    @Test fun `пусто — нули без времени`() {
        val json = DumpSummary().toJson()
        assertEquals(0, json.getInt("records"))
        assertFalse(json.has("from"))
    }
}
