package com.example.vkweb.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOrientationTest {

    @Test fun `горизонтальное — альбомно само, вертикальное и почти квадратное — как у системы`() {
        assertEquals(VideoOrientation.Mode.LANDSCAPE, VideoOrientation.choose(1280, 720, autoRotate = true, manual = null))
        assertEquals(VideoOrientation.Mode.SYSTEM, VideoOrientation.choose(720, 1280, autoRotate = true, manual = null))
        assertEquals(VideoOrientation.Mode.SYSTEM, VideoOrientation.choose(1000, 960, autoRotate = true, manual = null))
        assertEquals(VideoOrientation.Mode.SYSTEM, VideoOrientation.choose(0, 0, autoRotate = true, manual = null))
    }

    @Test fun `выключено в настройках — как у системы, ручной выбор главнее`() {
        assertEquals(VideoOrientation.Mode.SYSTEM, VideoOrientation.choose(1280, 720, autoRotate = false, manual = null))
        assertEquals(VideoOrientation.Mode.PORTRAIT, VideoOrientation.choose(1280, 720, autoRotate = true, manual = VideoOrientation.Mode.PORTRAIT))
        assertEquals(VideoOrientation.Mode.LANDSCAPE, VideoOrientation.choose(720, 1280, autoRotate = false, manual = VideoOrientation.Mode.LANDSCAPE))
    }

    @Test fun `кнопка — в противоположную`() {
        assertEquals(VideoOrientation.Mode.PORTRAIT, VideoOrientation.toggled(isLandscapeNow = true))
        assertEquals(VideoOrientation.Mode.LANDSCAPE, VideoOrientation.toggled(isLandscapeNow = false))
    }
}
