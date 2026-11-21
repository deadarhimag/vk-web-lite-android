package com.example.vkweb.people

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Подписи в своих списках друзей и сообществ: «в сети», «был в сети вчера в 21:40», «Москва»,
 * «2 общих друга», «Музыка · 42 783 подписчика».
 * Без зависимостей от Android: время передаётся снаружи, поэтому проверяется обычными тестами.
 * Приложение только на русском, поэтому тексты здесь, а не в strings.xml (нужны склонения).
 */
object PeopleFormat {

    /** 1 января 2100 года: позже времени в данных VK не бывает. */
    const val MAX_EPOCH_SEC = 4_102_444_800L

    private const val SEP = " · "
    private val MONTHS_GEN = arrayOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** Имя с отметкой подтверждённой страницы. */
    fun displayName(name: String, verified: Boolean): String = if (verified) "$name ✓" else name

    /** Склонение по числу: 1 друг, 2 друга, 5 друзей; 11–14 — всегда «многие». */
    fun plural(n: Long, one: String, few: String, many: String): String =
        com.example.vkweb.text.RuPlural.pick(n, one, few, many)

    /** 42783 → «42 783». Locale.ROOT: разделитель не зависит от языка телефона. */
    fun groupDigits(n: Long): String = String.format(Locale.ROOT, "%,d", n).replace(',', ' ')

    /** Вторая строка под именем. */
    fun subtitle(e: PeopleEntry, nowSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (e.kind == PeopleKind.FRIENDS) friendSubtitle(e, nowSec, zone) else groupSubtitle(e)

    fun friendSubtitle(e: PeopleEntry, nowSec: Long, zone: ZoneId): String {
        when (e.deactivated) {
            "deleted" -> return "Страница удалена"
            "banned" -> return "Страница заблокирована"
            "" -> Unit
            else -> return "Страница недоступна"
        }
        val parts = ArrayList<String>(3)
        presence(e, nowSec, zone).takeIf { it.isNotEmpty() }?.let(parts::add)
        if (e.city.isNotBlank()) parts += e.city
        if (e.mutual > 0) parts += mutual(e.mutual)
        return parts.joinToString(SEP)
    }

    fun mutual(n: Int): String =
        "$n " + plural(n.toLong(), "общий друг", "общих друга", "общих друзей")

    /** «в сети», «в сети с телефона», «была в сети 5 мин. назад», «был в сети недавно» или пусто. */
    fun presence(e: PeopleEntry, nowSec: Long, zone: ZoneId): String {
        if (e.online) return if (e.mobile) "в сети с телефона" else "в сети"
        val verb = when (e.sex) {
            1 -> "была"
            2 -> "был"
            else -> "был(а)"
        }
        if (e.lastSeen > 0) return "$verb в сети " + whenText(e.lastSeen, nowSec, zone)
        return when (e.seen) {
            "recently" -> "$verb в сети недавно"
            "last_week" -> "$verb в сети на этой неделе"
            "last_month" -> "$verb в сети в этом месяце"
            "long_ago" -> "$verb в сети давно"
            else -> ""
        }
    }

    /** «только что», «7 мин. назад», «сегодня в 9:05», «вчера в 21:40», «3 марта», «3 марта 2024». */
    fun whenText(atSecRaw: Long, nowSec: Long, zone: ZoneId): String {
        // Время из данных страницы: за пределами разумного (до 2100 года) Instant.atZone бросал исключение, и список
        // падал при каждом показе — данные ещё и сохраняются (код ревью 3.59).
        val atSec = atSecRaw.coerceIn(0L, MAX_EPOCH_SEC)
        val diff = nowSec - atSec
        if (diff in 0 until 60) return "только что"
        if (diff in 60 until 3600) return "${diff / 60} мин. назад"
        val at = Instant.ofEpochSecond(atSec).atZone(zone)
        val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        val day: LocalDate = at.toLocalDate()
        val time = String.format(Locale.ROOT, "%d:%02d", at.hour, at.minute)
        return when {
            day == today -> "сегодня в $time"
            day == today.minusDays(1) -> "вчера в $time"
            day.year == today.year -> "${day.dayOfMonth} ${MONTHS_GEN[day.monthValue - 1]}"
            else -> "${day.dayOfMonth} ${MONTHS_GEN[day.monthValue - 1]} ${day.year}"
        }
    }

    fun groupSubtitle(e: PeopleEntry): String {
        val parts = ArrayList<String>(3)
        if (e.activity.isNotBlank()) parts += e.activity
        val members = e.members.ifBlank { if (e.membersCount > 0) membersText(e.membersCount.toLong(), e.groupType) else "" }
        if (members.isNotEmpty()) parts += members
        when (e.closed) {
            1 -> parts += "закрытое"
            2 -> parts += "частное"
        }
        return parts.joinToString(SEP)
    }

    /** У публичных страниц — подписчики, у групп и встреч — участники. */
    fun membersText(n: Long, groupType: String): String {
        val word = if (groupType == "page") plural(n, "подписчик", "подписчика", "подписчиков")
        else plural(n, "участник", "участника", "участников")
        return groupDigits(n) + " " + word
    }

    /** Совпадение с поиском: без учёта регистра, «ё» = «е». Ищем по имени, городу и тематике. */
    fun matches(e: PeopleEntry, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        return normalize(e.name).contains(q) || normalize(e.city).contains(q) || normalize(e.activity).contains(q)
    }

    /** То же, что [matches], для уже нормализованного запроса [q] (не пустого): без повторной нормализации на каждого человека. */
    fun matchesNormalized(e: PeopleEntry, q: String): Boolean =
        normalize(e.name).contains(q) || normalize(e.city).contains(q) || normalize(e.activity).contains(q)

    fun normalize(s: String): String = s.trim().lowercase().replace('ё', 'е')
}
