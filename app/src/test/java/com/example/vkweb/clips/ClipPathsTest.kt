package com.example.vkweb.clips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Адреса раздела «Клипы» (4.39): вкладки ленты и клипы под ними — отчёт 4.38. */
class ClipPathsTest {

    @Test fun `лента и все её вкладки — раздел`() {
        for (p in listOf("/clips", "/clips/", "/clips_trends", "/clips/subscriptions", "/clips?section=x")) assertTrue(p, ClipPaths.isFeed(p))
        for (p in listOf("/clip-1_2", "/clips_trends/clip-1_2", "/feed", "/clipsmaker/x/y", "/video-1_2")) assertFalse(p, ClipPaths.isFeed(p))
    }

    @Test fun `клип — сам по себе и под вкладкой ленты`() {
        for (p in listOf("/clip-212788102_456239591", "/clip1_2", "/clips_trends/clip-1_2", "/clips/subscriptions/clip-1_2", "/clips/clip-1_2"))
            assertTrue(p, ClipPaths.isClip(p))
        for (p in listOf("/clips", "/clips_trends", "/video-1_2", "/clipper-1_2", "/x/clip-1_2")) assertFalse(p, ClipPaths.isClip(p))
    }

    @Test fun `номер клипа — и из адреса под вкладкой`() {
        assertEquals("-1_2", ClipPaths.clipId("/clips_trends/clip-1_2"))
        assertEquals("5_6", ClipPaths.clipId("/clip5_6?from=feed"))
        assertNull(ClipPaths.clipId("/clips"))
    }
}
