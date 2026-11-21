package com.example.vkweb.outbox

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Готовые варианты отложенной отправки (4.17): «через 30 минут», «через час», «через 3 часа», «сегодня в 20:00»
 * (пока до него больше получаса), «завтра в 09:00». Без Android: проверяется [ScheduleOptionsTest].
 */
object ScheduleOptions {

    data class Option(val label: String, val at: Long)

    /** Отложить меньше, чем на минуту, нельзя: уйдёт раньше, чем человек поймёт, что отложил. */
    const val MIN_DELAY_MS = 60_000L

    private val EVENING = LocalTime.of(20, 0)
    private val MORNING = LocalTime.of(9, 0)
    private const val EVENING_MARGIN_MIN = 30L

    fun quick(now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Option> {
        val options = ArrayList<Option>()
        options += Option("Через 30 минут", now + 30 * 60_000L)
        options += Option("Через 1 час", now + 60 * 60_000L)
        options += Option("Через 3 часа", now + 3 * 60 * 60_000L)
        val today = Instant.ofEpochMilli(now).atZone(zone)
        val evening = today.toLocalDate().atTime(EVENING).atZone(zone)
        if (today.plusMinutes(EVENING_MARGIN_MIN).isBefore(evening)) options += Option("Сегодня в 20:00", evening.toInstant().toEpochMilli())
        options += Option("Завтра в 09:00", today.toLocalDate().plusDays(1).atTime(MORNING).atZone(zone).toInstant().toEpochMilli())
        return options
    }

    /** Дата и время, выбранные вручную, — момент в поясе телефона; в прошлом или раньше чем через минуту — null. */
    fun custom(date: LocalDate, time: LocalTime, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        return at.takeIf { it - now >= MIN_DELAY_MS }
    }
}
