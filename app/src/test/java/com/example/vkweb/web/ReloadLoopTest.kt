package com.example.vkweb.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadLoopTest {

    @Test fun `четыре загрузки одного пути за восемь секунд — круг`() {
        val r = ReloadLoop()
        assertFalse(r.onStart("/audio", 0))
        assertFalse(r.onStart("/audio", 1000))
        assertFalse(r.onStart("/audio", 2000))
        assertTrue(r.onStart("/audio", 3000))
    }

    @Test fun `разные пути и редкие загрузки — не круг`() {
        val r = ReloadLoop()
        listOf("/feed", "/audio", "/feed", "/audio").forEachIndexed { i, p -> assertFalse(r.onStart(p, i * 500L)) }
        val slow = ReloadLoop()
        (0 until 6).forEach { assertFalse(slow.onStart("/audio", it * 5_000L)) }
    }
}
