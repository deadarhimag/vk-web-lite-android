package com.example.vkweb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoBudgetTest {

    private val mb = 1024L * 1024L

    @Test fun `большая куча — полный размер`() {
        assertEquals(4096, PhotoGallery.maxSidePx(512 * mb))
    }

    @Test fun `средняя куча — картинка не больше восьмой части`() {
        val side = PhotoGallery.maxSidePx(256 * mb)
        assertTrue(side in 2800..2950)
        assertTrue("байт на картинку", side.toLong() * side * 4 <= 256 * mb / 8)
    }

    @Test fun `маленькая куча — не меньше разумного минимума`() {
        assertEquals(1448, PhotoGallery.maxSidePx(64 * mb))
        assertEquals(1024, PhotoGallery.maxSidePx(16 * mb))
    }

    @Test fun `предел сверху задаётся`() {
        assertEquals(2048, PhotoGallery.maxSidePx(512 * mb, cap = 2048))
    }
}
