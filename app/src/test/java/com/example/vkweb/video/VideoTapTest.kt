package com.example.vkweb.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTapTest {

    @Test fun `трети экрана — назад, пауза, вперёд`() {
        assertEquals(VideoTap.Zone.BACK, VideoTap.zone(10f, 900))
        assertEquals(VideoTap.Zone.PLAY_PAUSE, VideoTap.zone(450f, 900))
        assertEquals(VideoTap.Zone.FORWARD, VideoTap.zone(890f, 900))
    }

    @Test fun `перемотка не уходит за начало и конец`() {
        assertEquals(0L, VideoTap.target(4_000, -10_000, 60_000))
        assertEquals(60_000L, VideoTap.target(55_000, 10_000, 60_000))
        assertEquals(30_000L, VideoTap.target(20_000, 10_000, 60_000))
    }
}
