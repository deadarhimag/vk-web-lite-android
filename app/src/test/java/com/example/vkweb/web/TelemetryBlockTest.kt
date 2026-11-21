package com.example.vkweb.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryBlockTest {

    private val policy = UrlPolicy()

    @Test fun `статистика и отчёты VK блокируются`() {
        assertTrue(policy.isBlockedResource("api.vk.ru", "/method/statEvents.addWeb"))
        assertTrue(policy.isBlockedResource("api.vk.com", "/method/stats.trackEvents"))
        assertTrue(policy.isBlockedResource("api.vk.ru", "/method/stats.trackVisitor"))
        assertTrue(policy.isBlockedResource("sdk-api.apptracer.ru", "/api/perf/upload"))
        assertTrue(policy.isBlockedResource("tracker-api.my.com", "/v3/"))
    }

    @Test fun `запросы, без которых приложение не работает, не блокируются`() {
        listOf(
            "/method/messages.getHistory", "/method/messages.send", "/method/friends.get", "/method/users.get",
            "/method/catalog.getGroups", "/method/account.getCounters", "/method/owners.getMainTab",
        ).forEach { assertFalse(it, policy.isBlockedResource("api.vk.ru", it)) }
    }

    @Test fun `блокировка трекеров выключена — ничего не блокируется`() {
        policy.blockTrackers = false
        assertFalse(policy.isBlockedResource("api.vk.ru", "/method/statEvents.addWeb"))
        assertFalse(policy.isBlockedResource("sdk-api.apptracer.ru", "/api/perf/upload"))
    }

    @Test fun `пробелы отчёта 4_12 закрыты — проверки сертификата, события плеера, промо (4_19)`() {
        assertTrue(policy.isBlockedResource("mincifry-cert.vkvideo.ru", "/"))
        assertTrue(policy.isBlockedResource("r3-test.vkvideo.ru", "/"))
        assertTrue(policy.isBlockedResource("api.vkvideo.ru", "/method/video.trackPlayerEvents"))
        assertTrue(policy.isBlockedResource("api.vk.ru", "/method/video.getMvkPromo"))
        assertTrue(policy.isBlockedResource("web.api.vk.ru", "/method/audio.getFullscreenBanner"))
        // Само видео и его каталог — нет.
        assertFalse(policy.isBlockedResource("api.vkvideo.ru", "/method/video.get"))
        assertFalse(policy.isBlockedResource("api.vkvideo.ru", "/method/catalog.getVideo"))
        assertFalse(policy.isBlockedResource("m.vkvideo.ru", "/"))
    }

    @Test fun `промо — по настройке рекламы, а не трекеров`() {
        policy.blockAds = false
        assertFalse(policy.isBlockedResource("api.vk.ru", "/method/video.getMvkPromo"))
    }
}
