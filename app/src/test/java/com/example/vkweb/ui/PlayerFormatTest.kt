package com.example.vkweb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerFormatTest {

    @Test fun `время трека`() {
        assertEquals("0:00", PlayerFormat.time(0.0))
        assertEquals("0:07", PlayerFormat.time(7.9))
        assertEquals("3:07", PlayerFormat.time(187.0))
        assertEquals("1:02:03", PlayerFormat.time(3723.0))
        assertEquals("0:00", PlayerFormat.time(-5.0))
        assertEquals("0:00", PlayerFormat.time(Double.NaN))
    }

    @Test fun `пока играет — позиция досчитывается по времени`() {
        assertEquals(12.5, PlayerFormat.position(true, position = 10.0, duration = 200.0, receivedAtMs = 1_000, nowMs = 3_500), 0.001)
    }

    @Test fun `на паузе позиция стоит`() {
        assertEquals(10.0, PlayerFormat.position(false, position = 10.0, duration = 200.0, receivedAtMs = 1_000, nowMs = 60_000), 0.001)
    }

    @Test fun `не дальше конца трека`() {
        assertEquals(200.0, PlayerFormat.position(true, position = 199.0, duration = 200.0, receivedAtMs = 0, nowMs = 10_000), 0.001)
    }

    @Test fun `длина неизвестна — просто считаем`() {
        assertEquals(15.0, PlayerFormat.position(true, position = 5.0, duration = 0.0, receivedAtMs = 0, nowMs = 10_000), 0.001)
    }

    @Test fun `одно время для секунд, миллисекунд и «позиция _ длина» (4_65)`() {
        assertEquals("0:00", PlayerFormat.timeSec(-3))
        assertEquals("1:05", PlayerFormat.timeSec(65))
        assertEquals("1:02:30", PlayerFormat.timeSec(3750))
        assertEquals("0:07", PlayerFormat.timeMs(7_999))
        assertEquals("0:00", PlayerFormat.timeMs(-1))
        // Длиннее 24 дней в миллисекундах — без переполнения Int (раньше позиция видео приводилась к Int).
        assertEquals("600:00:00", PlayerFormat.timeMs(600L * 3600 * 1000))
        assertEquals("1:15 / 3:40", PlayerFormat.clockMs(75_000, 220_000))
    }

    @Test fun `цифры латинские при любой локали`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("1:02:03", PlayerFormat.timeSec(3723))
            assertEquals("0:07", com.example.vkweb.messages.VoiceFormat.time(7_000))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
