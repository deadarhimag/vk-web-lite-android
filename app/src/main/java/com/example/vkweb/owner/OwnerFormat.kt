package com.example.vkweb.owner

import com.example.vkweb.people.PeopleEntry
import com.example.vkweb.people.PeopleFormat
import com.example.vkweb.people.PeopleKind
import java.time.ZoneId

/**
 * Тексты своего экрана профиля/сообщества. Без зависимостей от Android (проверяется тестами).
 */
object OwnerFormat {

    private const val SEP = " · "

    /** Счётчик под шапкой: текст и путь раздела на сайте (null — не открывается). */
    data class Counter(val text: String, val link: String?)

    /** Строка под именем: «был в сети вчера в 21:40 · Москва · ВолгГТУ» или «Аниме · 42 783 подписчика». */
    fun headerMeta(h: OwnerHeader, nowSec: Long, zone: ZoneId): String {
        if (!h.isUser) {
            val members = h.membersText.ifBlank { if (h.members > 0) PeopleFormat.membersText(h.members, h.groupType) else "" }
            val closed = when (h.closedGroup) {
                1 -> "закрытое сообщество"
                2 -> "частное сообщество"
                else -> ""
            }
            return join(h.activity, members, closed)
        }
        val asPerson = PeopleEntry(
            id = h.id, kind = PeopleKind.FRIENDS, name = h.name, sex = h.sex, online = h.online,
            mobile = h.mobile, lastSeen = h.lastSeen, seen = h.seen, deactivated = h.deactivated,
        )
        if (h.deactivated.isNotEmpty()) return PeopleFormat.friendSubtitle(asPerson, nowSec, zone)
        return join(PeopleFormat.presence(asPerson, nowSec, zone), h.city, h.occupation)
    }

    /** Счётчики под шапкой. У удалённых страниц их нет; у сообществ VK не присылает чисел — только разделы. */
    fun counters(h: OwnerHeader): List<Counter> {
        if (h.deactivated.isNotEmpty()) return emptyList()
        if (!h.isUser) return listOf(Counter("Фотографии", "/albums-${h.id}"), Counter("Видео", "/videos-${h.id}"))
        return listOf(
            Counter(counter(h.friends, "друг", "друга", "друзей"), "/friends?id=${h.id}"),
            Counter(counter(h.followers, "подписчик", "подписчика", "подписчиков"), null),
            Counter(counter(h.photos, "фото", "фото", "фото"), "/albums${h.id}"),
            Counter(counter(h.videos, "видео", "видео", "видео"), "/videos${h.id}"),
            Counter(counter(h.groups, "сообщество", "сообщества", "сообществ"), null),
        ).filter { it.text.isNotEmpty() }
    }

    /** «вчера в 21:40», у закреплённой записи — «вчера в 21:40 · закреплено». */
    fun postDate(post: WallPost, nowSec: Long, zone: ZoneId): String {
        val date = if (post.date > 0) PeopleFormat.whenText(post.date, nowSec, zone) else ""
        return if (post.pinned) join(date, "закреплено") else date
    }

    /** Путь к странице автора записи или null, если это сам владелец страницы. */
    fun authorLink(fromId: Long, ownerId: Long): String? = when {
        fromId == 0L || fromId == ownerId -> null
        fromId > 0 -> "/id$fromId"
        else -> "/club${-fromId}"
    }

    private fun join(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(SEP)

    /** 478 → «478», 4 812 → «4,8K», 48 120 → «48K», 1 250 000 → «1,2M» — как счётчики VK. */
    fun compact(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 10_000 -> oneDecimal(n / 100) + "K"
        n < 1_000_000 -> "${n / 1_000}K"
        n < 10_000_000 -> oneDecimal(n / 100_000) + "M"
        else -> "${n / 1_000_000}M"
    }

    private fun oneDecimal(tenths: Long): String {
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) whole.toString() else "$whole,$frac"
    }

    /** Счётчик под шапкой: «29 друзей», «6 фото». Пустая строка — не показывать. */
    fun counter(n: Long, one: String, few: String, many: String): String =
        if (n <= 0) "" else PeopleFormat.groupDigits(n) + " " + PeopleFormat.plural(n, one, few, many)

    /** Отрезок текста поста: обычный или ссылка (упоминание [id1|Имя], [club1|Имя], [https://…|текст]). */
    data class Segment(val text: String, val link: String? = null)

    private val MENTION = Regex("\\[((?:id|club|public|event)\\d+|https?://[^|\\]\\s]+)\\|([^\\]]{1,200})]")

    /** Разбивает текст на отрезки; упоминание показывается подписью и ведёт на страницу. */
    fun segments(text: String): List<Segment> {
        val result = ArrayList<Segment>()
        var last = 0
        for (m in MENTION.findAll(text)) {
            if (m.range.first > last) result += Segment(text.substring(last, m.range.first))
            val target = m.groupValues[1]
            val link = if (target.startsWith("http")) target else "/$target"
            result += Segment(m.groupValues[2], link)
            last = m.range.last + 1
        }
        if (last < text.length) result += Segment(text.substring(last))
        return result
    }

    /** Текст без разметки упоминаний — для подсчёта длины и поиска. */
    fun plain(text: String): String = segments(text).joinToString("") { it.text }

    /**
     * Сокращённый текст для ленты или null, если он и так короткий. Режем по границе слова,
     * не больше [maxChars] символов и [maxLines] строк.
     */
    fun collapse(text: String, maxChars: Int = 450, maxLines: Int = 8): String? {
        var cut = text.length
        var lines = 0
        for (i in text.indices) {
            if (text[i] == '\n' && ++lines >= maxLines) { cut = i; break }
        }
        if (cut > maxChars) cut = maxChars
        if (cut >= text.length) return null
        val space = text.lastIndexOfAny(charArrayOf(' ', '\n'), cut)
        if (space > cut * 2 / 3) cut = space
        // Не режем упоминание посередине: иначе на экране осталась бы разметка «[id1|Ив…».
        MENTION.findAll(text).firstOrNull { cut > it.range.first && cut <= it.range.last }?.let { cut = it.range.first }
        return text.substring(0, cut).trimEnd() + "…"
    }
}
