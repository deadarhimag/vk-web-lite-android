package com.example.vkweb.ui

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Экран не гаснет, пока ролик играет или подгружается для игры (4.88). */
class ScreenAwakeTest {

    @Test fun `играет или подгружается — экран держится`() {
        assertTrue(ScreenAwake.shouldKeep(playWhenReady = true, playbackState = Player.STATE_READY))
        assertTrue("подгрузка на плохой сети", ScreenAwake.shouldKeep(true, Player.STATE_BUFFERING))
    }

    @Test fun `пауза, конец и простой — экран гаснет как обычно`() {
        assertFalse("пауза", ScreenAwake.shouldKeep(false, Player.STATE_READY))
        assertFalse("пауза во время подгрузки", ScreenAwake.shouldKeep(false, Player.STATE_BUFFERING))
        assertFalse("ролик кончился", ScreenAwake.shouldKeep(true, Player.STATE_ENDED))
        assertFalse("проигрыватель пуст", ScreenAwake.shouldKeep(true, Player.STATE_IDLE))
    }
}
