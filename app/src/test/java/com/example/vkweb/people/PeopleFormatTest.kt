package com.example.vkweb.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PeopleFormatTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private fun sec(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toEpochSecond()
    private val now = sec(2026, 10, 2, 22, 30)

    private fun friend(
        sex: Int = 0, online: Boolean = false, mobile: Boolean = false, lastSeen: Long = 0, seen: String = "",
        city: String = "", mutual: Int = 0, deactivated: String = "",
    ) = PeopleEntry(1, PeopleKind.FRIENDS, "Анна Иванова", sex = sex, online = online, mobile = mobile,
        lastSeen = lastSeen, seen = seen, city = city, mutual = mutual, deactivated = deactivated)

    @Test fun `склонения`() {
        assertEquals("1 общий друг", PeopleFormat.mutual(1))
        assertEquals("3 общих друга", PeopleFormat.mutual(3))
        assertEquals("5 общих друзей", PeopleFormat.mutual(5))
        assertEquals("11 общих друзей", PeopleFormat.mutual(11))
        assertEquals("21 общий друг", PeopleFormat.mutual(21))
        assertEquals("112 общих друзей", PeopleFormat.mutual(112))
    }

    @Test fun `в сети`() {
        assertEquals("в сети", PeopleFormat.presence(friend(online = true), now, zone))
        assertEquals("в сети с телефона", PeopleFormat.presence(friend(online = true, mobile = true), now, zone))
    }

    @Test fun `время последнего входа`() {
        assertEquals("была в сети только что", PeopleFormat.presence(friend(sex = 1, lastSeen = now - 20), now, zone))
        assertEquals(
            "был в сети 7 мин. назад", PeopleFormat.presence(friend(sex = 2, lastSeen = now - 7 * 60), now, zone),
        )
        assertEquals(
            "был(а) в сети сегодня в 9:05", PeopleFormat.presence(friend(lastSeen = sec(2026, 10, 2, 9, 5)), now, zone),
        )
        assertEquals(
            "был(а) в сети вчера в 21:40",
            PeopleFormat.presence(friend(lastSeen = sec(2026, 10, 1, 21, 40)), now, zone),
        )
        assertEquals(
            "был(а) в сети 3 марта", PeopleFormat.presence(friend(lastSeen = sec(2026, 3, 3, 12, 0)), now, zone),
        )
        assertEquals(
            "был(а) в сети 3 марта 2024", PeopleFormat.presence(friend(lastSeen = sec(2024, 3, 3, 12, 0)), now, zone),
        )
    }

    @Test fun `скрытое время входа`() {
        assertEquals("была в сети недавно", PeopleFormat.presence(friend(sex = 1, seen = "recently"), now, zone))
        assertEquals("", PeopleFormat.presence(friend(seen = "что-то новое"), now, zone))
    }

    @Test fun `подпись друга`() {
        assertEquals("в сети · Москва · 2 общих друга",
            PeopleFormat.friendSubtitle(friend(online = true, city = "Москва", mutual = 2), now, zone))
        assertEquals(
            "Страница удалена",
            PeopleFormat.friendSubtitle(friend(deactivated = "deleted", city = "Москва"), now, zone),
        )
        assertEquals("", PeopleFormat.friendSubtitle(friend(), now, zone))
    }

    @Test fun `подпись сообщества`() {
        val vk = PeopleEntry(5826399, PeopleKind.GROUPS, "Аниме", members = "42 783 подписчика", activity = "Аниме")
        assertEquals("Аниме · 42 783 подписчика", PeopleFormat.groupSubtitle(vk))
        val noText = PeopleEntry(1, PeopleKind.GROUPS, "Клуб", membersCount = 1521, groupType = "group", closed = 1)
        assertEquals("1 521 участник · закрытое", PeopleFormat.groupSubtitle(noText))
        assertEquals("70 471 подписчик", PeopleFormat.membersText(70471, "page"))
    }

    @Test fun `поиск без учёта регистра и ё`() {
        val e = PeopleEntry(1, PeopleKind.FRIENDS, "Пётр Семёнов", city = "Москва")
        assertTrue(PeopleFormat.matches(e, "петр"))
        assertTrue(PeopleFormat.matches(e, "  СЕМЁН "))
        assertTrue(PeopleFormat.matches(e, "моск"))
        assertTrue(PeopleFormat.matches(e, ""))
        assertFalse(PeopleFormat.matches(e, "анна"))
    }

    @Test fun `адреса страниц и собеседника`() {
        val friend = PeopleEntry(40201973, PeopleKind.FRIENDS, "x")
        val group = PeopleEntry(5826399, PeopleKind.GROUPS, "y")
        assertTrue(friend.pageUrl.endsWith("/id40201973"))
        assertTrue(group.pageUrl.endsWith("/club5826399"))
        assertEquals("40201973", friend.peerId)
        assertEquals("-5826399", group.peerId)
    }

    @Test fun `отметка подтверждённой страницы`() {
        assertEquals("Аниме ✓", PeopleFormat.displayName("Аниме", true))
        assertEquals("Аниме", PeopleFormat.displayName("Аниме", false))
    }
}
