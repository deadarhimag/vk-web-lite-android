package com.example.vkweb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoGalleryTest {

    @Test fun `открывается нужное фото`() {
        val content = PhotoGallery.clean(listOf("a", "b", "c"), 2)
        assertEquals(listOf("a", "b", "c"), content.urls)
        assertEquals(2, content.index)
    }

    @Test fun `пустые адреса отбрасываются, открытое фото остаётся тем же`() {
        val content = PhotoGallery.clean(listOf("", "a", "", "b"), 3)
        assertEquals(listOf("a", "b"), content.urls)
        assertEquals(1, content.index)
    }

    @Test fun `номер вне списка — первое фото, пустой список — нечего показывать`() {
        assertEquals(0, PhotoGallery.clean(listOf("a", "b"), 9).index)
        assertEquals(0, PhotoGallery.clean(listOf("a", "b"), -1).index)
        assertEquals(emptyList<String>(), PhotoGallery.clean(listOf("", ""), 0).urls)
        assertEquals(emptyList<String>(), PhotoGallery.clean(null, 0).urls)
    }

    @Test fun `число фото ограничено`() {
        assertEquals(PhotoGallery.MAX_PHOTOS, PhotoGallery.clean(List(200) { "u$it" }, 0).urls.size)
    }

    @Test fun `листание не выходит за края`() {
        assertEquals(1, PhotoGallery.step(0, 3, PageGesture.NEXT))
        assertEquals(0, PhotoGallery.step(0, 3, PageGesture.PREVIOUS))
        assertEquals(2, PhotoGallery.step(2, 3, PageGesture.NEXT))
        assertEquals(1, PhotoGallery.step(2, 3, PageGesture.PREVIOUS))
    }

    @Test fun `подпись счётчика`() {
        assertEquals("1 / 5", PhotoGallery.counter(0, 5))
        assertEquals("5 / 5", PhotoGallery.counter(4, 5))
    }
}
