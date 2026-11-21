package com.example.vkweb.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoResumeRulesTest {

    @Test fun `что запоминается`() {
        assertEquals(300_000L, VideoResumeRules.toSave(300_000, 600_000))
        assertNull("короткий", VideoResumeRules.toSave(30_000, 50_000))
        assertNull("начало", VideoResumeRules.toSave(5_000, 600_000))
        assertNull("досмотрел", VideoResumeRules.toSave(595_000, 600_000))
    }

    @Test fun `ключ и хранение`() {
        assertEquals("/video-1_2", VideoResumeRules.keyOf("/video-1_2", "https://x/a.mp4?sig=1"))
        assertEquals("https://x/a.mp4", VideoResumeRules.keyOf("", "https://x/a.mp4?sig=1"))
        val m = VideoResumeRules.put(emptyMap(), "a", 100_000)
        assertEquals(m, VideoResumeRules.decode(VideoResumeRules.encode(m)))
        assertEquals(emptyMap<String, Long>(), VideoResumeRules.put(m, "a", null))
    }
}
