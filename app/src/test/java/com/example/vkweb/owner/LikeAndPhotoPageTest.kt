package com.example.vkweb.owner

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LikeAndPhotoPageTest {

    @Test fun `итог лайка разбирается, чужой номер записи отклоняется`() {
        val r = LikeResult.fromJson(JSONObject("""{"type":"like","key":"-42_7","ok":true,"liked":true,"likes":11}"""))!!
        assertEquals(LikeResult("-42_7", true, true, 11, ""), r)
        assertNull(LikeResult.fromJson(JSONObject("""{"key":"drop table","ok":true}""")))
        assertEquals(0, LikeResult.fromJson(JSONObject("""{"key":"1_2","likes":-5}"""))!!.likes)
    }

    @Test fun `страница фото сохраняется и читается, чужой адрес отбрасывается`() {
        val photo = PostPhoto("https://x/p.jpg", 600, 400, "", "", page = "/photo7_5?access_key=ab12")
        assertEquals(photo, PostPhoto.fromJson(photo.toJson()))
        val bad = PostPhoto.fromJson(JSONObject("""{"url":"https://x/p.jpg","page":"//evil.example/photo"}"""))!!
        assertEquals("", bad.page)
        val notPhoto = PostPhoto.fromJson(JSONObject("""{"url":"https://x/p.jpg","page":"/settings"}"""))!!
        assertEquals("только страница фото", "", notPhoto.page)
    }

    @Test fun `у фото сообщества ключ из сохранённой страницы отбрасывается, у фото человека остаётся`() {
        val community = PostPhoto.fromJson(JSONObject("""{"url":"https://x/p.jpg","page":"/photo-42_5?access_key=ab12"}"""))!!
        assertEquals("/photo-42_5", community.page)
        val person = PostPhoto.fromJson(JSONObject("""{"url":"https://x/p.jpg","page":"/photo7_5?access_key=ab12"}"""))!!
        assertEquals("/photo7_5?access_key=ab12", person.page)
    }
}
