package com.example.vkweb.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadMoreGuardTest {

    @Test fun `список не растёт — догрузка останавливается`() {
        val g = LoadMoreGuard(maxStalls = 3)
        repeat(2) { g.onRequested(100); g.onLoaded(100) }
        assertFalse(g.stopped)
        g.onRequested(100); g.onLoaded(100)
        assertTrue(g.stopped)
    }

    @Test fun `рост сбрасывает счёт, новый список — тоже`() {
        val g = LoadMoreGuard(maxStalls = 2)
        g.onRequested(100); g.onLoaded(100)
        g.onRequested(100); g.onLoaded(200)
        g.onRequested(200); g.onLoaded(200)
        assertFalse(g.stopped)
        g.onRequested(200); g.onLoaded(200)
        assertTrue(g.stopped)
        g.reset()
        assertFalse(g.stopped)
    }

    @Test fun `порция без запроса не считается`() {
        val g = LoadMoreGuard(maxStalls = 1)
        g.onLoaded(5)
        assertFalse(g.stopped)
    }
}
