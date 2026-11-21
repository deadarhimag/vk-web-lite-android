package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshBackoffTest {

    @Test fun `сначала — пауза из настройки`() {
        assertEquals(60_000L, RefreshBackoff(60_000).nextDelayMs())
    }

    @Test fun `выключено в настройке — не обновлять`() {
        assertNull(RefreshBackoff(0).nextDelayMs())
    }

    @Test fun `перезагрузки без нового — пауза удваивается до предела`() {
        val b = RefreshBackoff(60_000, maxFactor = 8)
        b.onReload(); assertEquals(120_000L, b.nextDelayMs())
        b.onReload(); assertEquals(240_000L, b.nextDelayMs())
        b.onReload(); assertEquals(480_000L, b.nextDelayMs())
        repeat(10) { b.onReload() }
        assertEquals("не реже чем в 8 раз от настройки", 480_000L, b.nextDelayMs())
    }

    @Test fun `пришло новое — снова часто`() {
        val b = RefreshBackoff(60_000)
        repeat(3) { b.onReload() }
        b.onChange()
        b.onReload()
        assertEquals(60_000L, b.nextDelayMs())
    }

    @Test fun `действие человека возвращает частое обновление`() {
        val b = RefreshBackoff(30_000)
        repeat(5) { b.onReload() }
        b.onUserActivity()
        assertEquals(30_000L, b.nextDelayMs())
    }

    @Test fun `новое учитывается один период`() {
        val b = RefreshBackoff(60_000)
        b.onChange()
        b.onReload()
        b.onReload()
        assertEquals(120_000L, b.nextDelayMs())
    }
}
