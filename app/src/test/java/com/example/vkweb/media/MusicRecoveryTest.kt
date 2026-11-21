package com.example.vkweb.media

import org.junit.Assert.*
import org.junit.Test

class MusicRecoveryTest {
    @Test fun expiredAndRejectedSignedUrlsAllowOneRefresh() {
        assertTrue(MusicRecovery.shouldRefresh(MusicRecovery.Expired(), 0))
        listOf(401, 403, 404, 410).forEach { assertTrue(MusicRecovery.shouldRefresh(MusicRecovery.HttpFailure(it), 0)) }
    }
    @Test fun recoveryCannotRepeatItself() {
        assertFalse(MusicRecovery.shouldRefresh(MusicRecovery.Expired(), 1))
        assertFalse(MusicRecovery.shouldRefresh(MusicRecovery.HttpFailure(403), 1))
        assertFalse(MusicRecovery.shouldRefresh(MusicRecovery.HttpFailure(403), 2))
    }
    @Test fun unrelatedFailuresDoNotTriggerPlaybackOrAnAutomaticRetryLoop() {
        listOf(400, 429, 500, 503).forEach { assertFalse(MusicRecovery.shouldRefresh(MusicRecovery.HttpFailure(it), 0)) }
        assertFalse(MusicRecovery.shouldRefresh(java.io.IOException(), 0))
        assertFalse(MusicRecovery.shouldRefresh(IllegalStateException("conversion failed"), 0))
    }
    @Test fun cancellationAndCacheClearInvalidateRecoveryIndependently() {
        assertTrue(MusicRecovery.isCurrent(10, 20, 10, 20))
        assertFalse(MusicRecovery.isCurrent(10, 20, 11, 20))
        assertFalse(MusicRecovery.isCurrent(10, 20, 10, 21))
    }
    @Test fun refreshPageKeepsTrustedPlaylistContext() {
        val value = "https://m.vk.ru/audio?act=playlist&id=1_2"
        assertEquals(value, MusicRecovery.page(value))
    }
    @Test fun recoveryCannotNavigateToForeignOrCredentialBearingPages() {
        listOf("https://evil.example/", "https://vk.ru.evil.example/audio", "http://m.vk.ru/audio", "https://u:p@m.vk.ru/audio", "javascript:run()", "").forEach {
            assertEquals(MusicRecovery.DEFAULT_PAGE, MusicRecovery.page(it))
        }
    }
}
