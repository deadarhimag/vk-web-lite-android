package com.example.vkweb.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDownloadRulesTest {
    private val base = "https://sun9-1.userapi.com/a/b.jpg"

    @Test fun `перенаправление на сервер картинок VK — можно`() {
        assertEquals("https://sun9-2.userapi.com/c.jpg", ImageDownloadRules.redirectTarget(base, "https://sun9-2.userapi.com/c.jpg"))
        // Относительный адрес — на том же сервере.
        assertEquals("https://sun9-1.userapi.com/x/y.jpg", ImageDownloadRules.redirectTarget(base, "/x/y.jpg"))
    }

    @Test fun `чужой сервер, http и битый адрес — нет`() {
        assertNull(ImageDownloadRules.redirectTarget(base, "https://evil.example/x.jpg"))
        assertNull(ImageDownloadRules.redirectTarget(base, "http://sun9-2.userapi.com/c.jpg"))
        assertNull(ImageDownloadRules.redirectTarget(base, "https://userapi.com.evil.example/x"))
        assertNull(ImageDownloadRules.redirectTarget(base, "ht tp://::"))
        assertNull(ImageDownloadRules.redirectTarget(base, ""))
        assertNull(ImageDownloadRules.redirectTarget(base, null))
    }

    @Test fun `коды перенаправлений и предел размера`() {
        listOf(301, 302, 303, 307, 308).forEach { assertTrue(ImageDownloadRules.isRedirect(it)) }
        listOf(200, 204, 304, 404).forEach { assertFalse(ImageDownloadRules.isRedirect(it)) }
        assertFalse(ImageDownloadRules.tooLarge(-1))
        assertFalse(ImageDownloadRules.tooLarge(ImageDownloadRules.MAX_BYTES))
        assertTrue(ImageDownloadRules.tooLarge(ImageDownloadRules.MAX_BYTES + 1))
    }
}
