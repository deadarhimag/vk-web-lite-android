package com.example.vkweb.owner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostMergeTest {

    private fun photo(host: String, query: String, open: String = "") =
        PostPhoto("https://$host/s/v1/ig2/AbC.jpg?$query", 800, 600, "https://$host/s/v1/ig2/AbC_full.jpg?$query", open)

    private fun post(avatar: String, photos: List<PostPhoto> = emptyList(), likes: Int = 1, repost: WallPost? = null) = WallPost(
        key = "wall-1_2", url = "/wall-1_2", fromId = -1, fromName = "Клуб", fromPhoto = avatar, date = 1, text = "Текст",
        photos = photos, extras = emptyList(), likes = likes, repost = repost,
    )

    @Test fun `те же картинки с другой подписью — запись остаётся прежней`() {
        val known = post("https://sun9-1.vkuserphoto.ru/s/v1/ig2/av.jpg?u=AAA", listOf(photo("sun9-1.vkuserphoto.ru", "u=AAA&cs=1280x960")))
        val fresh = post("https://sun9-24.userapi.com/s/v1/ig2/av.jpg?u=BBB", listOf(photo("sun9-24.userapi.com", "u=BBB&cs=604x453")))
        assertEquals(known, PostMerge.stabilize(known, fresh))
    }

    @Test fun `счётчики и текст берутся свежими, адреса — известными`() {
        val known = post("https://a.x/s/av.jpg?u=1", likes = 1)
        val fresh = post("https://b.x/s/av.jpg?u=2", likes = 7)
        val merged = PostMerge.stabilize(known, fresh)
        assertEquals(7, merged.likes)
        assertEquals("https://a.x/s/av.jpg?u=1", merged.fromPhoto)
    }

    @Test fun `другая картинка заменяется, по странице фото узнаётся то же фото в другом размере`() {
        val known = post("https://a.x/s/old.jpg", listOf(photo("a.x", "u=1", open = "/photo-1_5")))
        assertEquals("https://b.x/s/new.jpg", PostMerge.stabilize(known, post("https://b.x/s/new.jpg", known.photos)).fromPhoto)
        val otherSize = PostPhoto("https://c.x/other/path.jpg", 100, 75, "https://c.x/other/full.jpg", "/photo-1_5")
        assertEquals(known.photos, PostMerge.stabilize(known, post("https://a.x/s/old.jpg", listOf(otherSize))).photos)
        val differentPhoto = known.photos.map { it.copy(open = "/photo-1_9") }
        assertEquals(differentPhoto, PostMerge.stabilize(known, known.copy(photos = differentPhoto)).photos)
    }

    @Test fun `репост стабилизируется так же, новая запись возвращается как есть`() {
        val inner = post("https://a.x/s/in.jpg?u=1")
        val known = post("https://a.x/s/av.jpg", repost = inner)
        val fresh = post("https://a.x/s/av.jpg", repost = inner.copy(fromPhoto = "https://b.x/s/in.jpg?u=2"))
        assertEquals(inner.fromPhoto, PostMerge.stabilize(known, fresh).repost?.fromPhoto)
        assertEquals(fresh, PostMerge.stabilize(null, fresh))
    }

    @Test fun `описание изменений называет поля и не больше трёх записей`() {
        val a = post("https://a.x/1.jpg", likes = 1)
        val b = a.copy(likes = 2, fromPhoto = "https://a.x/2.jpg")
        assertEquals("wall-1_2: аватар+счётчики", PostMerge.describeChanges(listOf(a), listOf(b)))
        assertEquals("", PostMerge.describeChanges(listOf(a), listOf(a)))
        val many = (1..6).map { a.copy(key = "k$it") }
        assertTrue(PostMerge.describeChanges(many, many.map { it.copy(likes = 9) }).split("; ").size <= 3)
    }
}
