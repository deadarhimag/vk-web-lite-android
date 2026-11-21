package com.example.vkweb.web

import com.example.vkweb.config.HiddenSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    private val policy = UrlPolicy()

    private fun decide(url: String): Navigation {
        val uri = java.net.URI(url)
        return policy.decide(uri.scheme, uri.host, uri.path)
    }

    @Test fun `разрешённые разделы открываются внутри`() {
        listOf(
            "https://m.vk.com/mail", "https://m.vk.com/mail?act=show&peer=1",
            "https://m.vk.com/friends", "https://m.vk.com/groups",
            "https://m.vk.com/audio", "https://m.vk.com/video", "https://m.vk.com/id1",
            "https://m.vk.com/application", "https://m.vk.com/club123",
        ).forEach { assertEquals(it, Navigation.ALLOW, decide(it)) }
    }

    @Test fun `скрытые разделы ведут домой`() {
        listOf(
            "https://m.vk.com/", "https://m.vk.com", "https://vk.com/feed", "https://m.vk.com/feed?x=1",
            "https://m.vk.com/news/photos", "https://m.vk.com/app51234_1", "https://m.vk.com/apps",
            "https://m.vk.com/market", "https://m.vk.ru/games", "https://m.vk.com/settings/ads",
        ).forEach { assertEquals(it, Navigation.REDIRECT_HOME, decide(it)) }
    }

    @Test fun `раздел клипов скрыт по умолчанию и открывается при выключенной настройке`() {
        val sections = listOf(
            "https://m.vk.com/clips", "https://m.vk.ru/clips/trends", "https://m.vk.com/clip", "https://m.vk.com/clips-12345",
        )
        sections.forEach { assertEquals(it, Navigation.REDIRECT_HOME, decide(it)) }
        // Похожие адреса не задеваются.
        listOf("https://m.vk.com/clipsfan", "https://m.vk.com/video", "https://m.vk.com/video-1_2")
            .forEach { assertEquals(it, Navigation.ALLOW, decide(it)) }
        policy.hideClips = false
        sections.forEach { assertEquals(it, Navigation.ALLOW, decide(it)) }
    }

    @Test fun `отдельный клип открывается и при скрытых клипах`() {
        listOf("https://m.vk.com/clip-12345_678", "https://vk.com/clip123_456", "https://m.vkvideo.ru/clip-1_2")
            .forEach { assertEquals(it, Navigation.ALLOW, decide(it)) }
    }

    @Test fun `пока смотрят клип по ссылке, страницы клипов открыты и при выключенном разделе`() {
        policy.clipSession = true
        listOf("https://m.vk.com/clips", "https://m.vk.com/clip", "https://m.vk.com/clips-12345")
            .forEach { assertEquals(it, Navigation.ALLOW, decide(it)) }
        policy.clipSession = false
        assertEquals(Navigation.REDIRECT_HOME, decide("https://m.vk.com/clips"))
    }

    @Test fun `клипы и прочие разделы переключаются независимо`() {
        policy.hiddenSections = emptySet()
        assertEquals(Navigation.ALLOW, decide("https://m.vk.com/feed"))
        assertEquals(Navigation.REDIRECT_HOME, decide("https://m.vk.com/clips"))
    }

    @Test fun `каждый скрытый раздел открывается отдельно`() {
        policy.hiddenSections = HiddenSection.DEFAULT - HiddenSection.MARKET
        assertEquals(Navigation.ALLOW, decide("https://m.vk.com/market"))
        assertEquals(Navigation.ALLOW, decide("https://m.vk.ru/market/product/1"))
        assertEquals(Navigation.REDIRECT_HOME, decide("https://m.vk.com/feed"))
        assertEquals(Navigation.REDIRECT_HOME, decide("https://m.vk.com/games"))

        policy.hiddenSections = HiddenSection.DEFAULT - HiddenSection.FEED
        assertEquals(Navigation.ALLOW, decide("https://m.vk.com/feed"))
        assertEquals(Navigation.ALLOW, decide("https://m.vk.com/"))
        assertEquals(Navigation.REDIRECT_HOME, decide("https://m.vk.com/market"))

        policy.hiddenSections = HiddenSection.DEFAULT - HiddenSection.APPS
        assertEquals(Navigation.ALLOW, decide("https://m.vk.com/app51234_1"))
        assertEquals(Navigation.ALLOW, decide("https://m.vk.com/services"))
    }

    @Test fun `у каждого раздела с пунктом меню адрес пункта сам не скрыт при открытом разделе`() {
        HiddenSection.entries.filter { it.menuPath != null }.forEach { section ->
            policy.hiddenSections = HiddenSection.DEFAULT - section
            assertEquals(section.name, Navigation.ALLOW, decide("https://m.vk.com" + section.menuPath))
            // и при этом он действительно относится к своему разделу (иначе меню вело бы мимо)
            assertTrue(section.matches(section.menuPath!!))
        }
    }

    @Test fun `VK Видео открывается внутри, скрытые разделы там не мешают`() {
        // m.vk.com/video перенаправляет сюда; «/» здесь — каталог видео, а не лента.
        listOf("https://m.vkvideo.ru/", "https://m.vkvideo.ru/video-1_2", "https://vkvideo.ru/playlist/-1_2")
            .forEach { assertEquals(it, Navigation.ALLOW, decide(it)) }
        // Клипы на VK Видео скрываются так же.
        assertEquals(Navigation.REDIRECT_HOME, decide("https://m.vkvideo.ru/clips"))
        assertTrue(policy.isVideoSite("m.vkvideo.ru"))
        assertFalse(policy.isVideoSite("m.vk.com"))
        assertTrue(policy.isTrustedOrigin("https", "m.vkvideo.ru"))
    }

    @Test fun `страницы входа не блокируются`() {
        assertEquals(Navigation.ALLOW, decide("https://id.vk.com/"))
        assertEquals(Navigation.ALLOW, decide("https://login.vk.com/feed"))
        assertEquals(Navigation.ALLOW, decide("https://connect.ok.ru/"))
    }

    @Test fun `чужие сайты и похожие домены открываются снаружи`() {
        assertEquals(Navigation.OPEN_EXTERNAL, decide("https://example.com/"))
        assertEquals(Navigation.OPEN_EXTERNAL, decide("https://evilvk.com/mail"))
        assertEquals(Navigation.OPEN_EXTERNAL, decide("https://vk.com.evil.org/mail"))
        assertEquals(Navigation.OPEN_EXTERNAL, decide("http://example.com/"))
        assertEquals(Navigation.OPEN_EXTERNAL, decide("tel:+70000000000"))
    }

    @Test fun `http на VK поднимается до https`() {
        assertEquals(Navigation.UPGRADE_HTTPS, decide("http://vk.com/mail"))
    }

    @Test fun `опасные схемы игнорируются`() {
        assertEquals(Navigation.IGNORE, policy.decide("javascript", null, null))
        assertEquals(Navigation.IGNORE, policy.decide("file", null, "/sdcard/x"))
        assertEquals(Navigation.IGNORE, policy.decide(null, null, null))
    }

    @Test fun `реклама и трекеры блокируются по умолчанию`() {
        assertTrue(policy.isBlockedResource("ad.mail.ru"))
        assertTrue(policy.isBlockedResource("r.mradx.net"))
        assertTrue(policy.isBlockedResource("x.mc.yandex.ru"))
        assertTrue(policy.isBlockedResource("top-fwz1.mail.ru"))
        assertTrue(policy.isBlockedResource("www.google-analytics.com"))
        assertTrue(policy.isBlockedResource("stats.vk-portal.net"))
        assertTrue(policy.isBlockedResource("vk.com", "/rtrg"))
    }

    @Test fun `содержимое VK не блокируется`() {
        assertFalse(policy.isBlockedResource("mail.ru"))
        assertFalse(policy.isBlockedResource("sun1.userapi.com", "/impg/photo.jpg"))
        assertFalse(policy.isBlockedResource("m.vk.com", "/mail"))
        assertFalse(policy.isBlockedResource("m.vk.com", "/audio"))
        assertFalse(policy.isBlockedResource("example.com", "/rtrg"))
    }

    @Test fun `реклама и трекеры выключаются независимо`() {
        val adsOnly = UrlPolicy().apply { blockTrackers = false }
        assertTrue(adsOnly.isBlockedResource("ad.mail.ru"))
        assertFalse(adsOnly.isBlockedResource("mc.yandex.ru"))
        assertFalse(adsOnly.isBlockedResource("vk.com", "/rtrg"))

        val trackersOnly = UrlPolicy().apply { blockAds = false }
        assertFalse(trackersOnly.isBlockedResource("ad.mail.ru"))
        assertTrue(trackersOnly.isBlockedResource("mc.yandex.ru"))
    }

    @Test fun `доверенный origin только https и VK`() {
        assertTrue(policy.isTrustedOrigin("https", "m.vk.com"))
        assertFalse(policy.isTrustedOrigin("http", "m.vk.com"))
        assertFalse(policy.isTrustedOrigin("https", "ok.ru"))
        assertFalse(policy.isTrustedOrigin("https", "notvk.com"))
    }

    @Test fun `фильтры можно выключить в настройках`() {
        val open = UrlPolicy().apply { hiddenSections = emptySet(); blockAds = false; blockTrackers = false }
        val uri = java.net.URI("https://m.vk.com/feed")
        assertEquals(Navigation.ALLOW, open.decide(uri.scheme, uri.host, uri.path))
        assertFalse(open.isBlockedSection("m.vk.com", "/feed"))
        assertFalse(open.isBlockedResource("ad.mail.ru"))
        assertFalse(open.isBlockedResource("mc.yandex.ru"))
    }

    @Test fun `страницы в окне приложения — только сайт VK и вход, серверы файлов — нет`() {
        val policy = UrlPolicy()
        assertTrue(policy.isPageHost("m.vk.ru"))
        assertTrue(policy.isPageHost("id.vk.com"))
        assertTrue(policy.isPageHost("m.vkvideo.ru"))
        assertTrue(policy.isPageHost("connect.ok.ru"))
        assertFalse(policy.isPageHost("psv4.userapi.com"))
        assertFalse(policy.isPageHost("sun9-1.vkuserphoto.ru"))
        assertFalse(policy.isPageHost("evil.example"))
    }

    @Test fun `статистика просмотров не блокируется на страницах сообществ и записей, на остальных — да`() {
        val policy = UrlPolicy()
        assertFalse(policy.isBlockedResource("web.api.vk.ru", "/method/statEvents.addWebLegacy", "/club5826399"))
        assertFalse(policy.isBlockedResource("web.api.vk.ru", "/method/statEvents.addWebLegacy", "/wall-1_2"))
        assertFalse(policy.isBlockedResource("web.api.vk.ru", "/method/statEvents.addWebLegacy", "/durov"))
        assertTrue(policy.isBlockedResource("web.api.vk.ru", "/method/statEvents.addWebLegacy", "/audio"))
        assertTrue(policy.isBlockedResource("web.api.vk.ru", "/method/statEvents.addWebLegacy", null))
        assertTrue(policy.isBlockedResource("web.api.vk.ru", "/method/stats.trackVisitor", "/club1"))
    }

    @Test fun `open_group (stats trackEvents) проходит на страницах сообществ и в списке сообществ, на остальных нет`() {
        val policy = UrlPolicy()
        assertFalse(policy.isBlockedResource("web.api.vk.ru", "/method/stats.trackEvents", "/club14595052"))
        assertFalse(policy.isBlockedResource("web.api.vk.ru", "/method/stats.trackEvents", "/groups"))
        assertTrue(policy.isBlockedResource("web.api.vk.ru", "/method/stats.trackEvents", "/audio"))
        assertTrue(policy.isBlockedResource("web.api.vk.ru", "/method/stats.trackEvents", "/mail"))
    }
}
