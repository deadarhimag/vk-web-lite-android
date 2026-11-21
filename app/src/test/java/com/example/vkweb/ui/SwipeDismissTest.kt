package com.example.vkweb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeDismissTest {

    private val h = 2000
    private val d = 3f

    @Test fun `вертикальный жест начинает смахивание, горизонтальный — листание`() {
        assertTrue(SwipeDismiss.starts(dx = 5f, dy = 40f, slopPx = 20f))
        assertFalse(SwipeDismiss.starts(dx = 40f, dy = 30f, slopPx = 20f))
        assertFalse(SwipeDismiss.starts(dx = 0f, dy = 10f, slopPx = 20f))
    }

    @Test fun `далеко — закрыть, близко без броска — вернуть`() {
        assertTrue(SwipeDismiss.shouldDismiss(dy = 400f, velocityY = 0f, heightPx = h, density = d))
        assertTrue(SwipeDismiss.shouldDismiss(dy = -400f, velocityY = 0f, heightPx = h, density = d))
        assertFalse(SwipeDismiss.shouldDismiss(dy = 150f, velocityY = 0f, heightPx = h, density = d))
    }

    @Test fun `быстрый бросок в ту же сторону закрывает и с малого расстояния`() {
        assertTrue(SwipeDismiss.shouldDismiss(dy = 100f, velocityY = 4000f, heightPx = h, density = d))
        assertFalse(SwipeDismiss.shouldDismiss(dy = 100f, velocityY = -4000f, heightPx = h, density = d))
        assertFalse(SwipeDismiss.shouldDismiss(dy = 30f, velocityY = 4000f, heightPx = h, density = d))
    }

    @Test fun `фон тускнеет к половине экрана, не прозрачнее 0_2`() {
        assertEquals(1.0, SwipeDismiss.backgroundAlpha(0f, h).toDouble(), 0.001)
        assertEquals(0.2, SwipeDismiss.backgroundAlpha(1500f, h).toDouble(), 0.001)
    }
}
