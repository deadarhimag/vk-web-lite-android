package com.example.vkweb.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {

    @Test fun `по кругу`() {
        assertEquals(1.5f, PlaybackSpeed.next(1f))
        assertEquals(2f, PlaybackSpeed.next(1.5f))
        assertEquals(1f, PlaybackSpeed.next(2f))
        assertEquals(1f, PlaybackSpeed.next(3.3f), 0f)
        assertEquals(1.25f, PlaybackSpeed.next(1f, PlaybackSpeed.VIDEO))
    }

    @Test fun `подписи и проверка`() {
        assertEquals("1×", PlaybackSpeed.label(1f))
        assertEquals("1,5×", PlaybackSpeed.label(1.5f))
        assertEquals("0,75×", PlaybackSpeed.label(0.75f))
        assertEquals(2f, PlaybackSpeed.valid(7f))
    }
}
