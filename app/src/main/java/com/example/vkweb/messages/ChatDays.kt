package com.example.vkweb.messages

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Дни и время в переписке (3.94): разделители «Сегодня», «Вчера», «12 октября», «3 марта 2024» между сообщениями
 * разных дней и время «14:32» под сообщением (дата уже видна в разделителе). Группы подряд идущих сообщений одного
 * автора — [groupedWithPrevious]. Без зависимостей от Android: проверяется обычными тестами.
 */
object ChatDays {

    /** Дата раньше 2001 года — не настоящая (часы теста, мусор): такой день не показывается. */
    private const val MIN_REAL_SEC = 1_000_000_000L

    /** Подряд идущие сообщения одного автора дальше этого — уже отдельные «реплики». */
    const val GROUP_GAP_SEC = 5 * 60L

    private val RU = Locale("ru")
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", RU)
    private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
    private val HOURS = DateTimeFormatter.ofPattern("HH:mm", RU)

    fun isKnown(dateSec: Long) = dateSec >= MIN_REAL_SEC

    /** День сообщения (номер дня с 1970 года в часовом поясе [zone]); null — дата неизвестна. */
    fun day(dateSec: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        if (isKnown(dateSec)) Instant.ofEpochSecond(dateSec).atZone(zone).toLocalDate().toEpochDay() else null

    /**
     * Дни сообщений по порядку: у сообщения без даты (неотправленное без времени, старое сохранённое) — день
     * предыдущего, чтобы оно не порождало разделитель посреди переписки.
     */
    fun days(items: List<ChatMessage>, zone: ZoneId = ZoneId.systemDefault()): List<Long?> {
        var last: Long? = null
        return items.map { m -> (day(m.dateSec, zone) ?: last).also { last = it } }
    }

    /** Нужен ли разделитель перед сообщением [index]: первое с известной датой или день сменился. */
    fun headerAt(days: List<Long?>, index: Int): Long? {
        val day = days.getOrNull(index) ?: return null
        val previous = if (index > 0) days[index - 1] else null
        return if (day != previous) day else null
    }

    /** Подпись разделителя. */
    fun label(day: Long, nowSec: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        val date = LocalDate.ofEpochDay(day)
        return when {
            date == today -> "Сегодня"
            date == today.minusDays(1) -> "Вчера"
            date.year == today.year -> DAY_MONTH.format(date)
            else -> DAY_MONTH_YEAR.format(date)
        }
    }

    /** Время под сообщением: по дате — «14:32» (день — в разделителе); без неё — то, что прислала страница. */
    fun time(message: ChatMessage, zone: ZoneId = ZoneId.systemDefault()): String =
        if (isKnown(message.dateSec)) HOURS.format(Instant.ofEpochSecond(message.dateSec).atZone(zone)) else message.time

    /**
     * Сообщение продолжает «реплику» предыдущего: тот же автор (свои — свои; в беседе — то же имя), тот же день и не
     * дальше [GROUP_GAP_SEC]. Тогда у него меньше отступ, нет повторного имени автора, а «хвостик» — только у последнего.
     */
    /** Место под аватаром автора слева от входящего сообщения (4.53). */
    enum class AvatarSlot {
        /** Своё сообщение или личная переписка (автора не подписывают): места нет. */
        NONE,
        /** Внутри «реплики» автора в беседе: место пустое, пузыри стоят ровно. */
        SPACE,
        /** Последнее сообщение «реплики»: аватар. */
        SHOW,
    }

    fun avatarSlot(items: List<ChatMessage>, index: Int, zone: ZoneId = ZoneId.systemDefault()): AvatarSlot {
        val m = items.getOrNull(index) ?: return AvatarSlot.NONE
        if (m.out || m.sender.isEmpty()) return AvatarSlot.NONE
        return if (groupedWithPrevious(items, index + 1, zone)) AvatarSlot.SPACE else AvatarSlot.SHOW
    }

    fun groupedWithPrevious(items: List<ChatMessage>, index: Int, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val m = items.getOrNull(index) ?: return false
        val p = items.getOrNull(index - 1) ?: return false
        if (m.out != p.out || m.sender != p.sender) return false
        if (!isKnown(m.dateSec) || !isKnown(p.dateSec)) return !isKnown(m.dateSec) && m.isPending && p.out
        if (day(m.dateSec, zone) != day(p.dateSec, zone)) return false
        return m.dateSec - p.dateSec in 0..GROUP_GAP_SEC
    }
}
