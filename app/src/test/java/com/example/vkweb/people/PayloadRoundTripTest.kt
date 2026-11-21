package com.example.vkweb.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Сохранённые списки читаются обратно без потерь (проверяется настоящим org.json). */
class PayloadRoundTripTest {

    @Test fun `друзья сохраняются и читаются без потерь`() {
        val friend = PeopleEntry(
            id = 40201973, kind = PeopleKind.FRIENDS, name = "Анна Иванова", photo = "https://sun1.vkuserphoto.ru/a.jpg",
            sex = 1, online = true, mobile = true, lastSeen = 1790968315, seen = "recently", city = "Москва",
            mutual = 3, canWrite = true, deactivated = "",
        )
        val request = friend.copy(id = 7, name = "Пётр", request = true)
        val payload = PeoplePayload(
            kind = PeopleKind.FRIENDS, key = "/friends", own = true, hasMore = true, total = 128,
            items = listOf(friend), requests = listOf(request), listSeen = true,
        )
        val back = PeoplePayload.fromJson(payload.toJson())
        assertEquals(payload, back)
    }

    @Test fun `сообщества сохраняются и читаются без потерь`() {
        val group = PeopleEntry(
            id = 5826399, kind = PeopleKind.GROUPS, name = "Аниме", photo = "https://sun1.vkuserphoto.ru/g.jpg",
            members = "42 783 подписчика", membersCount = 42783, activity = "Аниме", groupType = "page",
            verified = true, closed = 1, admin = false, counter = 4,
        )
        val payload = PeoplePayload(
            kind = PeopleKind.GROUPS, key = "/groups?tab=my", own = true, hasMore = false, total = 1,
            items = listOf(group), listSeen = true,
        )
        assertEquals(payload, PeoplePayload.fromJson(payload.toJson()))
    }

    @Test fun `не https-фото отбрасывается, лишние поля не мешают`() {
        val json = org.json.JSONObject(
            """{"type":"people","kind":"friends","key":"/friends","items":[{"id":1,"name":"X","photo":"http://evil/a.jpg","новое":5}]}""",
        )
        val payload = PeoplePayload.fromJson(json)!!
        assertEquals("", payload.items.single().photo)
    }

    @Test fun `неизвестный раздел не разбирается`() {
        assertNull(PeoplePayload.fromJson(org.json.JSONObject("""{"kind":"музыка"}""")))
    }
}
