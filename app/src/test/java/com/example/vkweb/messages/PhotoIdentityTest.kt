package com.example.vkweb.messages

import com.example.vkweb.offline.ImageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoIdentityTest {

    private fun photo(host: String, query: String) = ChatPhoto("https://$host/s/v1/ig2/AbC_hash1.jpg?$query", 1280, 960)
    private fun other(query: String) = ChatPhoto("https://sun9-62.vkuserphoto.ru/s/v1/ig2/Zzz_hash2.jpg?$query", 800, 600)

    @Test fun `одно фото с другой подписью, размером и сервером — то же`() {
        val a = listOf(photo("sun9-62.vkuserphoto.ru", "quality=95&as=32x24,1280x960&from=bu&u=AAA&cs=1280x960"))
        val b = listOf(photo("sun9-24.userapi.com", "quality=95&as=32x24,604x453&from=bu&u=BBB&cs=604x453"))
        assertTrue(HistoryMerge.samePhotos(a, b))
    }

    @Test fun `другой порядок тех же фото — то же`() {
        val p1 = photo("sun9-62.vkuserphoto.ru", "u=1")
        val p2 = other("u=2")
        assertTrue(HistoryMerge.samePhotos(listOf(p1, p2), listOf(p2, p1)))
    }

    @Test fun `другое фото или другое число — не то же`() {
        assertFalse(HistoryMerge.samePhotos(listOf(photo("h", "u=1")), listOf(other("u=1"))))
        assertFalse(HistoryMerge.samePhotos(listOf(photo("h", "u=1")), listOf(photo("h", "u=1"), other("u=1"))))
    }

    @Test fun `сообщение с тем же фото не меняется при обновлении`() {
        val known = ChatMessage(id = "c24046", out = false, text = "", time = "10:00",
            photos = listOf(photo("sun9-62.vkuserphoto.ru", "u=AAA&cs=1280x960"), other("u=A2")))
        val fresh = known.copy(photos = listOf(other("u=B2&cs=604x453"), photo("sun9-24.userapi.com", "u=BBB&cs=604x453")))
        val merged = HistoryMerge.messages(listOf(known), listOf(fresh))
        assertEquals(listOf(known), merged)
        assertEquals("", HistoryMerge.describeChanges(listOf(known), merged))
    }

    @Test fun `путь картинки и параметр u в ключе кэша`() {
        assertEquals("/s/v1/ig2/AbC_hash1.jpg", ImageKey.identity("https://sun9-62.vkuserphoto.ru/s/v1/ig2/AbC_hash1.jpg?size=1&u=2#f"))
        assertEquals(
            ImageKey.of("https://sun9-62.vkuserphoto.ru/s/v1/ig2/A.jpg?cs=604x453&u=AAA"),
            ImageKey.of("https://sun9-24.userapi.com/s/v1/ig2/A.jpg?cs=604x453&u=BBB"),
        )
    }
}
