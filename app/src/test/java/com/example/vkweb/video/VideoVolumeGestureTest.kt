package com.example.vkweb.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoVolumeGestureTest {

    @Test fun `жест — только вертикальное движение больше порога`() {
        assertTrue(VideoVolumeGesture.starts(dx = 5f, dy = -40f, slop = 20f))
        assertFalse("меньше порога", VideoVolumeGesture.starts(dx = 0f, dy = 10f, slop = 20f))
        assertFalse("наискосок", VideoVolumeGesture.starts(dx = 35f, dy = 40f, slop = 20f))
        assertFalse("горизонтально", VideoVolumeGesture.starts(dx = 100f, dy = 5f, slop = 20f))
    }

    @Test fun `громкость — только правая половина видео (4_16)`() {
        assertTrue(VideoVolumeGesture.inVolumeZone(700f, 1000))
        assertTrue(VideoVolumeGesture.inVolumeZone(500f, 1000))
        assertFalse(VideoVolumeGesture.inVolumeZone(499f, 1000))
        assertFalse(VideoVolumeGesture.inVolumeZone(10f, 1000))
        assertFalse("размер ещё не известен", VideoVolumeGesture.inVolumeZone(700f, 0))
    }

    @Test fun `у верхнего и нижнего края — системный жест`() {
        assertTrue(VideoVolumeGesture.inSystemEdge(10f, 1000, 48f))
        assertTrue(VideoVolumeGesture.inSystemEdge(990f, 1000, 48f))
        assertFalse(VideoVolumeGesture.inSystemEdge(500f, 1000, 48f))
    }

    @Test fun `вверх — громче, вниз — тише, в пределах`() {
        // 0,6 высоты (600 из 1000) — весь диапазон 0…15.
        assertEquals(15, VideoVolumeGesture.level(0, 15, dy = -600f, height = 1000))
        assertEquals(0, VideoVolumeGesture.level(15, 15, dy = 600f, height = 1000))
        assertEquals(10, VideoVolumeGesture.level(5, 15, dy = -200f, height = 1000))
        assertEquals(15, VideoVolumeGesture.level(10, 15, dy = -2000f, height = 1000))
        assertEquals(0, VideoVolumeGesture.level(3, 15, dy = 2000f, height = 1000))
        assertEquals(7, VideoVolumeGesture.level(7, 15, dy = 0f, height = 1000))
    }

    @Test fun `странные размеры не ломают`() {
        assertEquals(4, VideoVolumeGesture.level(4, 0, dy = -100f, height = 1000))
        assertEquals(4, VideoVolumeGesture.level(4, 15, dy = -100f, height = 0))
        assertEquals(0, VideoVolumeGesture.percent(3, 0))
    }

    @Test fun `проценты`() {
        assertEquals(100, VideoVolumeGesture.percent(15, 15))
        assertEquals(53, VideoVolumeGesture.percent(8, 15))
        assertEquals(0, VideoVolumeGesture.percent(0, 15))
    }
}
