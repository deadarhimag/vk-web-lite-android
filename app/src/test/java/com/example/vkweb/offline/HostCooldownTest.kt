package com.example.vkweb.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCooldownTest {

    private val hosts = HostCooldown(cooldownMs = 60_000)

    @Test fun `не ответивший сервер пропускается, остальные — нет`() {
        assertTrue(hosts.onConnectFailure("sun9-62.vkuserphoto.ru", now = 0))
        assertTrue(hosts.isBlocked("sun9-62.vkuserphoto.ru", now = 1_000))
        assertFalse(hosts.isBlocked("sun9-64.vkuserphoto.ru", now = 1_000))
    }

    @Test fun `пропуск временный`() {
        hosts.onConnectFailure("a", now = 0)
        assertTrue(hosts.isBlocked("a", now = 59_999))
        assertFalse(hosts.isBlocked("a", now = 60_000))
    }

    @Test fun `в журнал — один раз за пропуск`() {
        assertTrue(hosts.onConnectFailure("a", now = 0))
        assertFalse("повтор во время пропуска", hosts.onConnectFailure("a", now = 10_000))
        assertTrue("после пропуска — снова", hosts.onConnectFailure("a", now = 200_000))
    }

    @Test fun `ответил — пропускать незачем`() {
        hosts.onConnectFailure("a", now = 0)
        hosts.onSuccess("a")
        assertFalse(hosts.isBlocked("a", now = 1))
    }
}
