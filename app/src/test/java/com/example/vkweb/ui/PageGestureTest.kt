package com.example.vkweb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageGestureTest {

    private val width = 1080f
    private val density = 3f

    private fun swipe(dx: Float, dy: Float = 0f, vx: Float? = null, zoomed: Boolean = false) =
        PageGesture.direction(dx, dy, vx, width, zoomed, density)

    @Test fun `быстрый бросок влево — следующее, вправо — предыдущее`() {
        assertEquals(PageGesture.NEXT, swipe(dx = -200f, vx = -3000f))
        assertEquals(PageGesture.PREVIOUS, swipe(dx = 200f, vx = 3000f))
    }

    @Test fun `медленное, но длинное движение тоже листает`() {
        assertEquals(PageGesture.NEXT, swipe(dx = -400f))
        assertEquals(PageGesture.PREVIOUS, swipe(dx = 400f))
    }

    @Test fun `короткое медленное движение не листает`() {
        assertEquals(PageGesture.NONE, swipe(dx = -100f))
        assertEquals(PageGesture.NONE, swipe(dx = 0f))
    }

    @Test fun `движение вверх-вниз не листает`() {
        assertEquals(PageGesture.NONE, swipe(dx = -400f, dy = 400f))
        assertEquals(PageGesture.NONE, swipe(dx = -300f, dy = 500f, vx = -3000f))
    }

    @Test fun `увеличенное фото листает только бросок, длинное движение двигает картинку`() {
        assertEquals(PageGesture.NONE, swipe(dx = -600f, zoomed = true))
        assertEquals(PageGesture.NEXT, swipe(dx = -200f, vx = -3000f, zoomed = true))
    }

    @Test fun `бросок в другую сторону, чем движение, не листает`() {
        // Палец ушёл вправо, а в конце «отдёрнулся» влево.
        assertEquals(PageGesture.NONE, swipe(dx = 100f, vx = -3000f))
    }

    @Test fun `слабый бросок на короткой дистанции не листает`() {
        assertEquals(PageGesture.NONE, swipe(dx = -150f, vx = -300f))
        assertEquals(PageGesture.NONE, swipe(dx = -20f, vx = -3000f))
    }
}
