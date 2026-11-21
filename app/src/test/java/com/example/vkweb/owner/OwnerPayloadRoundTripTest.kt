package com.example.vkweb.owner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Сохранённые страницы читаются обратно без потерь (проверяется настоящим org.json). */
class OwnerPayloadRoundTripTest {

    private val photo = PostPhoto(
        "https://sun1.vkuserphoto.ru/p.jpg", 800, 600, "https://sun1.vkuserphoto.ru/full.jpg", "",
    )
    private val video = PostPhoto("https://sun1.vkuserphoto.ru/v.jpg", 640, 360, "", "/video-1_2")

    private val repost = WallPost(
        key = "-1_9", url = "/wall-1_9", fromId = -1, fromName = "Группа", fromPhoto = "https://sun1.vkuserphoto.ru/g.jpg",
        date = 1790000000, text = "исходная запись", photos = listOf(photo), extras = emptyList(),
    )

    private val post = WallPost(
        key = "40201973_698", url = "/wall40201973_698", fromId = 40201973, fromName = "Иван", fromPhoto = "",
        date = 1790968315, text = "текст [id1|Павел] и ссылка", photos = listOf(photo, video),
        extras = listOf(
            PostExtra("audio", "Исполнитель — Трек", ""), PostExtra("link", "Сайт", "https://example.org/x"),
        ),
        pinned = true, repost = repost, likes = 12, liked = true, comments = 3, reposts = 1, views = 1500,
    )

    @Test fun `страница человека сохраняется и читается без потерь`() {
        val header = OwnerHeader(
            isUser = true, id = 40201973, name = "Иван Петров", photo = "https://sun1.vkuserphoto.ru/u.jpg",
            photoFull = "https://sun1.vkuserphoto.ru/ufull.jpg", status = "статус", verified = false, deactivated = "",
            canWrite = true, sex = 2, online = true, mobile = false, lastSeen = 1790968315, seen = "", city = "Москва",
            occupation = "ВолгГТУ", closedProfile = false, friends = 29, followers = 44, photos = 6, videos = 34, groups = 3,
        )
        val payload = OwnerPayload("/skels777", 40201973, header, listOf(post), hasMore = true, wallSeen = true)
        assertEquals(payload, OwnerPayload.fromJson(payload.toJson()))
    }

    @Test fun `страница сообщества сохраняется и читается без потерь`() {
        val header = OwnerHeader(
            isUser = false, id = 5826399, name = "Аниме", photo = "https://sun1.vkuserphoto.ru/g.jpg",
            photoFull = "", status = "", verified = true, deactivated = "", canWrite = false,
            cover = "https://sun1.vkuserphoto.ru/c.jpg", description = "описание", activity = "Аниме",
            members = 42783, membersText = "42 783 подписчика", groupType = "page", closedGroup = 1, isMember = true,
        )
        val payload = OwnerPayload("/animenation1", -5826399, header, listOf(post), hasMore = false, wallSeen = true)
        assertEquals(payload, OwnerPayload.fromJson(payload.toJson()))
    }

    @Test fun `при сохранении запись берутся не больше заданного числа`() {
        val payload = OwnerPayload(
            "/x", 1, null, List(10) { post.copy(key = "1_$it") }, hasMore = false, wallSeen = true,
        )
        assertEquals(3, OwnerPayload.fromJson(payload.toJson(maxPosts = 3))!!.posts.size)
    }

    @Test fun `ссылка не из этого сайта отбрасывается`() {
        val raw = org.json.JSONObject(
            """{"type":"owner","key":"/x","ownerId":1,"posts":[{"key":"1_1","url":"//evil.example/x","text":"t"}]}""",
        )
        assertEquals("", OwnerPayload.fromJson(raw)!!.posts.single().url)
    }

    @Test fun `ключ не пути отклоняется`() {
        assertNull(OwnerPayload.fromJson(org.json.JSONObject("""{"type":"owner","key":"evil","ownerId":1}""")))
    }
}
