package com.example.vkweb.diag

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLogTest {

    @Test fun `пустая запись пропускается`() {
        assertNull(PageLog.parse(JSONObject("""{"type":"log","message":"   "}""")))
        assertNull(PageLog.parse(JSONObject("""{"type":"log"}""")))
    }

    @Test fun `запись разбирается, длинное режется`() {
        val r = PageLog.parse(JSONObject("""{"type":"log","level":"warn","message":"${"я".repeat(900)}","page":"${"/p".repeat(200)}"}"""))!!
        assertEquals(LogLevel.WARN, r.level)
        assertEquals(700, r.message.length)
        assertEquals(100, r.page.length)
    }

    @Test fun `служебные сообщения страницы — журнал и трафик — обрабатываются, остальные нет`() {
        assertTrue(DiagnosticMessages.handle(PageLog.TYPE, """{"type":"log","message":"привет"}"""))
        assertTrue(DiagnosticMessages.handle(TrafficSummary.TYPE, """{"type":"traffic","items":[{"h":"a","e":"b","s":"fetch","n":1,"b":2,"err":0}]}"""))
        assertFalse(DiagnosticMessages.handle("history", """{"type":"history"}"""))
        assertFalse(DiagnosticMessages.handle("", "не json"))
    }

    @Test fun `битое служебное сообщение не роняет приложение, но и дальше не идёт`() {
        assertTrue(DiagnosticMessages.handle(PageLog.TYPE, "{не json"))
        assertTrue(DiagnosticMessages.handle(TrafficSummary.TYPE, "[]"))
    }

    @Test fun `ошибка плеера скрытой страницы — подробность, а не предупреждение (4_30)`() {
        val msg = "клип: error {пауза,готов=0,сеть=3,поз=0/?,перемотка до=0,ошибка=4,источник=vkvd290.okcdn.ru,controls=false,376x579}"
        val warn = PageLog.Record(LogLevel.WARN, msg, "/clip-#_#")
        val hidden = PageLog.adjusted(warn, pageMediaBlocked = true)
        assertEquals(LogLevel.DEBUG, hidden.level)
        assertTrue(hidden.message.startsWith(msg) && "ожидаемо" in hidden.message)
        assertEquals("/clip-#_#", hidden.page)
        // Страница на экране — ошибка настоящая и остаётся предупреждением, без пометки.
        val shown = PageLog.adjusted(warn, pageMediaBlocked = false)
        assertEquals(LogLevel.WARN, shown.level)
        assertEquals(msg, shown.message)
    }
}
