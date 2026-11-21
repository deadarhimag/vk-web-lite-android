package com.example.vkweb.diag

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Уровень записи журнала событий. Порядок важен: фильтр пропускает записи не ниже выбранного. */
enum class LogLevel(val letter: Char) {
    DEBUG('D'), INFO('I'), WARN('W'), ERROR('E'),
}

/** Область приложения, к которой относится запись. Короткие имена — чтобы журнал читался глазами. */
enum class LogTag {
    /** Запуск, жизненный цикл, настройки. */
    APP,
    /** Загрузка страниц VK, переходы, ошибки загрузки. */
    PAGE,
    /** Сообщения скрипта страницы (разбор ответов, пропуски, ошибки структуры). */
    SCRIPT,
    /** Сообщения между страницей и приложением. */
    BRIDGE,
    /** Свои экраны поверх страниц: показ, свежие данные, таймауты. */
    SCREEN,
    /** Фоновое обновление сессии. */
    SESSION,
    /** Сеть. */
    NET,
    /** Сохранённое для работы без сети, картинки, очистка. */
    STORE,
    /** Свой интерфейс сообщений. */
    MESSAGES,
    /** Плеер и уведомление. */
    MEDIA,
    /** Сбор данных страниц для разработки. */
    DUMP,
    /** Очередь неотправленных сообщений. */
    OUTBOX,
    /** Фоновая проверка новых сообщений и уведомления. */
    SYNC,
}

/**
 * Оформление записей журнала. Без зависимостей от Android: проверяется обычными тестами.
 * Запись — одна строка: `2026-10-03 12:00:00.123 I SESSION обновление запущено причина=таймаут`.
 * Личного в журнал не попадает: адреса страниц очищаются ([url]), тексты сообщений и данных не пишутся.
 */
object LogFormat {

    private const val MAX_VALUE = 160
    private const val MAX_MESSAGE = 300
    private val TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val DIGIT_RUN = Regex("\\d{3,}")
    private val WHITESPACE = Regex("\\s+")

    fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Одна строка журнала. [fields] — пары «имя → значение», пустые значения пропускаются. */
    fun line(
        millis: Long,
        level: LogLevel,
        tag: LogTag,
        message: String,
        fields: List<Pair<String, Any?>> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        append(time(millis, zone)).append(' ').append(level.letter).append(' ').append(tag.name).append(' ')
        append(oneLine(message, MAX_MESSAGE))
        for ((name, value) in fields) {
            if (value == null) continue
            append(' ').append(name).append('=').append(value(value))
        }
    }

    /** Значение поля: одной строкой, ограниченной длины; с пробелами — в кавычках. */
    fun value(value: Any?): String {
        val text = oneLine(value.toString(), MAX_VALUE)
        return if (text.isEmpty() || text.any { it == ' ' || it == '=' }) "\"$text\"" else text
    }

    /**
     * Адрес страницы без личного: без параметров и якоря, длинные числа в пути (id людей, записей)
     * заменены на «#». Из `https://m.vk.com/id40201973?z=photo1_2` получается `m.vk.com/id#`.
     */
    fun url(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val noScheme = raw.substringAfter("://", raw)
        val clean = noScheme.substringBefore('?').substringBefore('#')
        return oneLine(DIGIT_RUN.replace(clean, "#"), MAX_VALUE)
    }

    /** Уровень по имени из сообщения скрипта страницы; неизвестное — самый тихий (DEBUG). */
    fun levelOf(name: String?): LogLevel = when (name?.lowercase()) {
        "error" -> LogLevel.ERROR
        "warn" -> LogLevel.WARN
        "info" -> LogLevel.INFO
        else -> LogLevel.DEBUG
    }

    /** Фильтр: пишется ли запись уровня [level] при выбранном минимальном [min] (null — журнал выключен). */
    fun allows(min: LogLevel?, level: LogLevel): Boolean = min != null && level >= min

    private fun oneLine(text: String, limit: Int): String {
        val flat = WHITESPACE.replace(text, " ").trim()
        return if (flat.length > limit) flat.take(limit) + "…" else flat
    }
}
