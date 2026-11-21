package com.example.vkweb.owner

import com.example.vkweb.people.PeopleEntry
import com.example.vkweb.people.PeopleKind
import com.example.vkweb.people.PeoplePayload
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUrlsTest {

    private fun post(i: Int, photo: String = "https://x.ru/p$i.jpg") = WallPost(
        key = "1_$i", url = "/wall1_$i", fromId = 1, fromName = "A", fromPhoto = "https://x.ru/a$i.jpg", date = 1, text = "",
        photos = listOf(PostPhoto(photo, 1, 1, "", ""), PostPhoto("https://x.ru/second$i.jpg", 1, 1, "", "")), extras = emptyList(),
    )

    @Test fun `для загрузки заранее берутся автор и первое фото каждой записи`() {
        val payload = OwnerPayload("/x", 1, null, listOf(post(1), post(2)), hasMore = false, wallSeen = true)
        assertEquals(
            listOf("https://x.ru/a1.jpg", "https://x.ru/p1.jpg", "https://x.ru/a2.jpg", "https://x.ru/p2.jpg"),
            payload.imageUrls(),
        )
    }

    @Test fun `число записей ограничено, пустые адреса пропускаются`() {
        val payload = OwnerPayload("/x", 1, null, List(50) { post(it) }, hasMore = false, wallSeen = true)
        assertEquals(40, payload.imageUrls(maxPosts = 20).size)
        assertEquals(
            emptyList<String>(), OwnerPayload("/x", 1, null, emptyList(), hasMore = false, wallSeen = true).imageUrls(),
        )
    }

    @Test fun `аватарки списка и заявок`() {
        val friend = PeopleEntry(1, PeopleKind.FRIENDS, "A", photo = "https://x.ru/1.jpg")
        val noPhoto = PeopleEntry(2, PeopleKind.FRIENDS, "B")
        val request = PeopleEntry(3, PeopleKind.FRIENDS, "C", photo = "https://x.ru/3.jpg", request = true)
        val payload = PeoplePayload(
            PeopleKind.FRIENDS, "/friends", own = true, hasMore = false, total = 0, items = listOf(friend, noPhoto),
            requests = listOf(request),
        )
        assertEquals(listOf("https://x.ru/1.jpg", "https://x.ru/3.jpg"), payload.imageUrls())
    }
}
