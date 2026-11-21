package com.example.vkweb.web

import com.example.vkweb.config.Tab
import com.example.vkweb.diag.ReportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleAndStartPolicyTest {

    @Test fun `пока виден экран сообщений, WebView не усыпляются, иначе окно чтения замирает`() {
        assertFalse(IdlePolicy.canPauseWeb(mainOpen = false, messagesOpen = true, musicPlaying = false))
        assertFalse(IdlePolicy.canPauseWeb(mainOpen = true, messagesOpen = false, musicPlaying = false))
    }

    @Test fun `играет музыка — не усыпляем`() {
        assertFalse(IdlePolicy.canPauseWeb(mainOpen = false, messagesOpen = false, musicPlaying = true))
    }

    @Test fun `идёт фоновая проверка сообщений — не усыпляем, иначе её окно замрёт`() {
        assertFalse(IdlePolicy.canPauseWeb(mainOpen = false, messagesOpen = false, musicPlaying = false, syncRunning = true))
    }

    @Test fun `приложение не на экране и тихо — можно усыпить`() {
        assertTrue(IdlePolicy.canPauseWeb(mainOpen = false, messagesOpen = false, musicPlaying = false))
    }

    @Test fun `старт на сообщениях со своим экраном не грузит страницу VK`() {
        assertTrue(StartPolicy.opensNativeMessages(Tab.MESSAGES, nativeMessages = true))
        assertFalse(StartPolicy.opensNativeMessages(Tab.MESSAGES, nativeMessages = false))
        assertFalse(StartPolicy.opensNativeMessages(Tab.FRIENDS, nativeMessages = true))
    }

    @Test fun `домашняя страница — стартовый раздел, а для своих сообщений первый обычный`() {
        assertEquals(Tab.FRIENDS, StartPolicy.homeTab(Tab.FRIENDS, nativeMessages = true, hidden = emptySet()))
        assertEquals(Tab.MESSAGES, StartPolicy.homeTab(Tab.MESSAGES, nativeMessages = false, hidden = emptySet()))
        val home = StartPolicy.homeTab(Tab.MESSAGES, nativeMessages = true, hidden = emptySet())
        assertNotEquals(Tab.MESSAGES, home)
    }

    @Test fun `домашняя страница не берёт скрытый раздел`() {
        val first = StartPolicy.homeTab(Tab.MESSAGES, nativeMessages = true, hidden = emptySet())
        val next = StartPolicy.homeTab(Tab.MESSAGES, nativeMessages = true, hidden = setOf(first))
        assertNotEquals(first, next)
        assertNotEquals(Tab.MESSAGES, next)
    }

    @Test fun `группы ожидания в отчёте`() {
        assertTrue(ReportFormat.standbyBucket(10).startsWith("активно"))
        assertTrue(ReportFormat.standbyBucket(40).contains("откладывается"))
        assertTrue(ReportFormat.standbyBucket(45).startsWith("ограничено"))
        assertTrue(ReportFormat.standbyBucket(5).startsWith("освобождено"))
        assertTrue(ReportFormat.standbyBucket(40).endsWith("[40]"))
    }
}
