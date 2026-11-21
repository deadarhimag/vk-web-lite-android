package com.example.vkweb.diag

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSummaryTest {

    private val json = """{"type":"traffic","page":"/audio","items":[
        {"h":"web.api.vk.ru","e":"method/audio.get","s":"fetch","n":12,"b":46080,"err":1},
        {"h":"web.api.vk.ru","e":"method/users.get","s":"fetch","n":3,"b":900,"err":0},
        {"h":"queuev4.vk.ru","e":"ws","s":"ws","n":40,"b":8000,"err":0},
        {"h":"x","e":"y","s":"fetch","n":0,"b":5,"err":0}]}"""

    @Test fun `разбор — пустые записи отбрасываются, итоги считаются`() {
        val s = TrafficSummary.parse(JSONObject(json))!!
        assertEquals("/audio", s.page)
        assertEquals(3, s.items.size)
        assertEquals(55, s.requests)
        assertEquals(46080L + 900 + 8000, s.bytes)
        assertEquals(1, s.errors)
    }

    @Test fun `серверы — по числу запросов`() {
        val s = TrafficSummary.parse(JSONObject(json))!!
        assertEquals("queuev4.vk.ru ×40, web.api.vk.ru ×15", TrafficSummary.hosts(s))
    }

    @Test fun `главные адреса с размером и ошибками`() {
        val s = TrafficSummary.parse(JSONObject(json))!!
        assertEquals("ws ws ×40 7 КБ; method/audio.get fetch ×12 45 КБ ошибок 1; method/users.get fetch ×3 900 Б", TrafficSummary.top(s))
    }

    @Test fun `пустая или битая сводка не разбирается`() {
        assertNull(TrafficSummary.parse(JSONObject("""{"type":"traffic","items":[]}""")))
        assertNull(TrafficSummary.parse(JSONObject("""{"type":"traffic"}""")))
    }

    @Test fun `ответ evaluateJavascript — строка со строкой JSON`() {
        // evaluateJavascript возвращает результат JS-строки как JSON-литерал: кавычки внутри экранированы.
        val literal = JSONObject.quote("""{"type":"traffic","page":"/mail","items":[{"h":"h","e":"e","s":"fetch","n":2,"b":10,"err":0}]}""")
        val s = TrafficSummary.fromEvaluateResult(literal)!!
        assertEquals("/mail", s.page)
        assertEquals(2, s.requests)
        assertNull(TrafficSummary.fromEvaluateResult("null"))
        assertNull(TrafficSummary.fromEvaluateResult("\"\""))
        assertNull(TrafficSummary.fromEvaluateResult(null))
        assertNull(TrafficSummary.fromEvaluateResult("not json"))
    }

    @Test fun `размер текстом`() {
        assertEquals("900 Б", TrafficSummary.kilobytes(900))
        assertTrue(TrafficSummary.kilobytes(2048).contains("2 КБ"))
    }
}
