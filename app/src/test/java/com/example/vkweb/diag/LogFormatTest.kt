package com.example.vkweb.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LogFormatTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val at = LocalDateTime.of(2026, 10, 3, 12, 0, 0, 123_000_000).atZone(zone).toInstant().toEpochMilli()

    @Test fun `строка журнала`() {
        val line = LogFormat.line(
            at, LogLevel.INFO, LogTag.SESSION, "обновление запущено", listOf("причина" to "таймаут"), zone,
        )
        assertEquals("2026-10-03 12:00:00.123 I SESSION обновление запущено причина=таймаут", line)
    }

    @Test fun `пустые поля пропускаются, пробелы в значении — в кавычках`() {
        val line = LogFormat.line(
            at, LogLevel.WARN, LogTag.PAGE, "ошибка", listOf("код" to null, "описание" to "net::ERR FAILED"), zone,
        )
        assertEquals("2026-10-03 12:00:00.123 W PAGE ошибка описание=\"net::ERR FAILED\"", line)
    }

    @Test fun `многострочное сводится к одной строке и обрезается`() {
        val line = LogFormat.line(at, LogLevel.ERROR, LogTag.APP, "строка1\nстрока2\t  три", zone = zone)
        assertTrue(line.endsWith("строка1 строка2 три"))
        assertFalse(line.contains('\n'))
        assertTrue(LogFormat.value("я".repeat(1000)).length < 200)
    }

    @Test fun `адрес без параметров и длинных чисел`() {
        assertEquals("m.vk.com/id#", LogFormat.url("https://m.vk.com/id40201973?z=photo1_2#x"))
        assertEquals("m.vk.com/wall-#_#", LogFormat.url("https://m.vk.com/wall-5826399_698"))
        assertEquals("m.vk.com/friends", LogFormat.url("https://m.vk.com/friends?id=40201973"))
        assertEquals("", LogFormat.url(null))
        assertEquals("", LogFormat.url("  "))
    }

    @Test fun `короткие числа в адресе остаются`() {
        assertEquals("m.vk.com/club1", LogFormat.url("https://m.vk.com/club1"))
    }

    @Test fun `фильтр уровней`() {
        assertTrue(LogFormat.allows(LogLevel.INFO, LogLevel.WARN))
        assertTrue(LogFormat.allows(LogLevel.INFO, LogLevel.INFO))
        assertFalse(LogFormat.allows(LogLevel.INFO, LogLevel.DEBUG))
        assertTrue(LogFormat.allows(LogLevel.DEBUG, LogLevel.DEBUG))
        assertFalse(LogFormat.allows(null, LogLevel.ERROR))
    }

    @Test fun `уровень из сообщения скрипта`() {
        assertEquals(LogLevel.ERROR, LogFormat.levelOf("error"))
        assertEquals(LogLevel.WARN, LogFormat.levelOf("WARN"))
        assertEquals(LogLevel.INFO, LogFormat.levelOf("info"))
        assertEquals(LogLevel.DEBUG, LogFormat.levelOf("debug"))
        assertEquals(LogLevel.DEBUG, LogFormat.levelOf("что-то новое"))
        assertEquals(LogLevel.DEBUG, LogFormat.levelOf(null))
    }

    @Test fun `настройка журнала`() {
        assertEquals(null, LogSetting.level("off"))
        assertEquals(LogLevel.DEBUG, LogSetting.level("debug"))
        assertEquals(LogLevel.INFO, LogSetting.level("info"))
        assertEquals(LogLevel.INFO, LogSetting.level(null))
        assertEquals(LogLevel.INFO, LogSetting.level("мусор"))
        assertEquals(listOf("off", "info", "debug"), LogSetting.VALUES)
    }
}
