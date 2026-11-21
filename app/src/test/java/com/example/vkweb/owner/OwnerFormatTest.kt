package com.example.vkweb.owner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class OwnerFormatTest {

    @Test fun `счётчики как у VK`() {
        assertEquals("478", OwnerFormat.compact(478))
        assertEquals("1K", OwnerFormat.compact(1_000))
        assertEquals("4,8K", OwnerFormat.compact(4_812))
        assertEquals("48K", OwnerFormat.compact(48_120))
        assertEquals("1,2M", OwnerFormat.compact(1_250_000))
        assertEquals("15M", OwnerFormat.compact(15_400_000))
    }

    @Test fun `счётчик в шапке`() {
        assertEquals("29 друзей", OwnerFormat.counter(29, "друг", "друга", "друзей"))
        assertEquals("1 друг", OwnerFormat.counter(1, "друг", "друга", "друзей"))
        assertEquals("", OwnerFormat.counter(0, "друг", "друга", "друзей"))
        assertEquals("1 521 фото", OwnerFormat.counter(1521, "фото", "фото", "фото"))
    }

    @Test fun `упоминания`() {
        val s = OwnerFormat.segments("Спасибо [id1|Павлу] и [club5826399|Аниме]! Сайт [https://vk.ru/x|тут].")
        assertEquals(
            listOf(
                OwnerFormat.Segment("Спасибо "), OwnerFormat.Segment("Павлу", "/id1"), OwnerFormat.Segment(" и "),
                OwnerFormat.Segment("Аниме", "/club5826399"), OwnerFormat.Segment("! Сайт "),
                OwnerFormat.Segment("тут", "https://vk.ru/x"), OwnerFormat.Segment("."),
            ),
            s,
        )
        assertEquals("Привет, Павел", OwnerFormat.plain("Привет, [id1|Павел]"))
        assertEquals(listOf(OwnerFormat.Segment("[не ссылка]")), OwnerFormat.segments("[не ссылка]"))
    }

    @Test fun `сокращение длинного текста`() {
        assertEquals(null, OwnerFormat.collapse("коротко"))
        val long = "слово ".repeat(200)
        val short = OwnerFormat.collapse(long)!!
        assertTrue(short.length <= 451)
        assertTrue(short.endsWith("…"))
        assertTrue(!short.dropLast(1).endsWith(" "))
        val lines = (1..20).joinToString("\n") { "строка $it" }
        assertEquals((1..8).joinToString("\n") { "строка $it" } + "…", OwnerFormat.collapse(lines))
    }

    @Test fun `упоминание не режется`() {
        val text = "а".repeat(440) + " [id1|Очень длинное имя человека]" + " б".repeat(50)
        val short = OwnerFormat.collapse(text)!!
        assertTrue(short, !short.contains("[id1"))
    }

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val now = LocalDateTime.of(2026, 10, 2, 22, 30).atZone(zone).toEpochSecond()

    private fun user(
        online: Boolean = false, city: String = "", occupation: String = "", deactivated: String = "",
        friends: Long = 0, photos: Long = 0,
    ) = OwnerHeader(
        isUser = true, id = 1, name = "Иван", photo = "", photoFull = "", status = "", verified = false,
        deactivated = deactivated, canWrite = true, sex = 2, online = online, city = city, occupation = occupation,
        friends = friends, photos = photos,
    )

    private fun group(activity: String = "", membersText: String = "", members: Long = 0, closed: Int = 0) = OwnerHeader(
        isUser = false, id = 5826399, name = "Аниме", photo = "", photoFull = "", status = "", verified = false,
        deactivated = "", canWrite = false, activity = activity, membersText = membersText, members = members,
        groupType = "page", closedGroup = closed,
    )

    @Test fun `строка под именем человека`() {
        assertEquals(
            "в сети · Москва · ВолгГТУ",
            OwnerFormat.headerMeta(user(online = true, city = "Москва", occupation = "ВолгГТУ"), now, zone),
        )
        assertEquals(
            "Страница удалена", OwnerFormat.headerMeta(user(deactivated = "deleted", city = "Москва"), now, zone),
        )
        assertEquals("", OwnerFormat.headerMeta(user(), now, zone))
    }

    @Test fun `строка под названием сообщества`() {
        assertEquals(
            "Аниме · 42 783 подписчика", OwnerFormat.headerMeta(group("Аниме", "42 783 подписчика"), now, zone),
        )
        assertEquals(
            "1 521 подписчик · закрытое сообщество",
            OwnerFormat.headerMeta(group(members = 1521, closed = 1), now, zone),
        )
    }

    @Test fun `счётчики шапки`() {
        assertEquals(
            listOf(OwnerFormat.Counter("29 друзей", "/friends?id=1"), OwnerFormat.Counter("6 фото", "/albums1")),
            OwnerFormat.counters(user(friends = 29, photos = 6)),
        )
        assertEquals(emptyList<OwnerFormat.Counter>(), OwnerFormat.counters(user(deactivated = "banned", friends = 29)))
        assertEquals(listOf("/albums-5826399", "/videos-5826399"), OwnerFormat.counters(group()).map { it.link })
    }

    @Test fun `дата записи`() {
        val yesterday = LocalDateTime.of(2026, 10, 1, 21, 40).atZone(zone).toEpochSecond()
        val post = WallPost("1_1", "/wall1_1", 1, "", "", yesterday, "", emptyList(), emptyList())
        assertEquals("вчера в 21:40", OwnerFormat.postDate(post, now, zone))
        assertEquals("вчера в 21:40 · закреплено", OwnerFormat.postDate(post.copy(pinned = true), now, zone))
        assertEquals("закреплено", OwnerFormat.postDate(post.copy(date = 0, pinned = true), now, zone))
    }

    @Test fun `ссылка на автора записи`() {
        assertEquals(null, OwnerFormat.authorLink(1, 1))
        assertEquals(null, OwnerFormat.authorLink(-5, -5))
        assertEquals("/id7", OwnerFormat.authorLink(7, -5))
        assertEquals("/club72985232", OwnerFormat.authorLink(-72985232, 40201973))
    }
}
