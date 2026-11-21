package com.example.vkweb.outbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ScheduleOptionsTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(h: Int, m: Int, day: Int = 14) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun `днём — пять вариантов, вечер и утро по поясу телефона`() {
        val now = at(13, 0)
        val o = ScheduleOptions.quick(now, zone)
        assertEquals(listOf("Через 30 минут", "Через 1 час", "Через 3 часа", "Сегодня в 20:00", "Завтра в 09:00"), o.map { it.label })
        assertEquals(now + 30 * 60_000L, o[0].at)
        assertEquals(at(20, 0), o[3].at)
        assertEquals(at(9, 0, day = 15), o[4].at)
    }

    @Test fun `вечером «сегодня в 20_00» не предлагается`() {
        assertTrue(ScheduleOptions.quick(at(19, 45), zone).none { it.label.startsWith("Сегодня") })
    }

    @Test fun `вручную — прошлое и ближе минуты отклоняются`() {
        val now = at(13, 0)
        assertEquals(at(18, 30), ScheduleOptions.custom(LocalDate.of(2026, 10, 14), LocalTime.of(18, 30), now, zone))
        assertNull(ScheduleOptions.custom(LocalDate.of(2026, 10, 14), LocalTime.of(12, 0), now, zone))
        assertNull(ScheduleOptions.custom(LocalDate.of(2026, 10, 14), LocalTime.of(13, 0), now, zone))
    }
}
