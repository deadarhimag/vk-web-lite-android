package com.example.vkweb.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Чей переход страницы — человека или самой страницы (4.38). */
class PageIntentTest {

    @Test fun `переход вскоре после касания или команды приложения — желанный`() {
        assertTrue(PageIntent.isWanted(nowMs = 10_000, lastIntentAtMs = 9_000))
        assertTrue(PageIntent.isWanted(nowMs = 10_000, lastIntentAtMs = 10_000 - PageIntent.WINDOW_MS))
    }

    @Test fun `давно ничего не нажимали или не нажимали вовсе — переход страницы сам`() {
        assertFalse(PageIntent.isWanted(nowMs = 20_000, lastIntentAtMs = 9_000))
        assertFalse(PageIntent.isWanted(nowMs = 20_000, lastIntentAtMs = 0))
        // Часы назад (перезапуск отсчёта) — не желанный, а не «вечно желанный».
        assertFalse(PageIntent.isWanted(nowMs = 5_000, lastIntentAtMs = 9_000))
    }

    @Test fun `страница сама ушла на клип или видео под своим экраном — блуждание`() {
        // Отчёт 4.33: «страница сама перешла с /clip… на /clips_trends», затем снова на клип.
        assertTrue(PageIntent.isWander("/clip-212788102_456239591", wanted = false, screenShown = true))
        assertTrue(PageIntent.isWander("/video-1_2", wanted = false, screenShown = true))
    }

    @Test fun `не блуждание — желанный переход, страница на экране или не проигрыватель`() {
        assertFalse(PageIntent.isWander("/clip-1_2", wanted = true, screenShown = true))
        assertFalse(PageIntent.isWander("/clip-1_2", wanted = false, screenShown = false))
        assertFalse(PageIntent.isWander("/friends", wanted = false, screenShown = true))
        assertFalse(PageIntent.isWander("/clips_trends", wanted = false, screenShown = true))
        // 4.39: вкладки ленты клипов — не блуждание (их меняет свой экран клипов).
        assertFalse(PageIntent.isWander("/clips", wanted = false, screenShown = true))
        assertFalse(PageIntent.isWander("/clips/subscriptions", wanted = false, screenShown = true))
    }
}
