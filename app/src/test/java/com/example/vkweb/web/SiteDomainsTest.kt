package com.example.vkweb.web

import com.example.vkweb.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пути основного сайта, открытые на VK Видео, — на основной сайт (4.31). */
class SiteDomainsTest {

    @Test fun `разделы и страницы основного сайта на VK Видео переводятся на основной`() {
        val origin = AppConfig.SITE_ORIGIN
        assertEquals("$origin/friends", SiteDomains.mainSiteUrl("m.vkvideo.ru", "/friends", null))
        assertEquals("$origin/groups?tab=my", SiteDomains.mainSiteUrl("m.vkvideo.ru", "/groups", "tab=my"))
        assertEquals("$origin/club123", SiteDomains.mainSiteUrl("vkvideo.ru", "/club123", ""))
        assertEquals("$origin/id1", SiteDomains.mainSiteUrl("m.vkvideo.ru", "/id1", null))
        assertEquals("$origin/clip-1_2", SiteDomains.mainSiteUrl("m.vkvideo.ru", "/clip-1_2", null))
        assertEquals("$origin/clips", SiteDomains.mainSiteUrl("m.vkvideo.ru", "/clips", null))
        assertEquals("$origin/audio", SiteDomains.mainSiteUrl("M.VKVIDEO.RU", "/audio", null))
        assertEquals("$origin/wall-1_2", SiteDomains.mainSiteUrl("m.vkvideo.ru", "/wall-1_2", null))
        assertEquals("$origin/mail", SiteDomains.mainSiteUrl("m.vkvideo.ru", "/mail/", null)?.trimEnd('/'))
    }

    @Test fun `пути самого сайта видео остаются на нём`() {
        for (path in listOf("/", "", "/trends", "/for_kids", "/video-1_2", "/@vkvideo", "/playlist/-1_2", "/search", "/settings", "/settings/privacy", "/clubs_and_more")) {
            assertNull(path, SiteDomains.mainSiteUrl("m.vkvideo.ru", path, null))
        }
    }

    @Test fun `на основном сайте ничего не переводится`() {
        assertNull(SiteDomains.mainSiteUrl("m.vk.ru", "/friends", null))
        assertNull(SiteDomains.mainSiteUrl("m.vk.com", "/clip-1_2", null))
        assertNull(SiteDomains.mainSiteUrl(null, "/friends", null))
        assertNull(SiteDomains.mainSiteUrl("evilvkvideo.ru", "/friends", null))
    }

    @Test fun `каталог VK Видео`() {
        assertTrue(SiteDomains.isVideoCatalog("/"))
        assertTrue(SiteDomains.isVideoCatalog("/trends"))
        assertTrue(SiteDomains.isVideoCatalog("/politics/media"))
        assertFalse(SiteDomains.isVideoCatalog("/video-1_2"))
        assertFalse(SiteDomains.isVideoCatalog("/@channel"))
        assertFalse(SiteDomains.isVideoCatalog("/search"))
    }
}
