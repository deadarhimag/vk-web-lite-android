package com.example.vkweb.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VkLinksTest {

    @Test fun `ссылки сайта VK — путь на нашем адресе`() {
        assertEquals("https://m.vk.com/id1?w=wall1_2", VkLinks.siteUrl("https", "vk.com", "/id1", "w=wall1_2"))
        assertEquals("https://m.vk.com/club1", VkLinks.siteUrl("HTTPS", "M.VK.RU", "/club1", null))
        assertEquals("https://m.vk.com/", VkLinks.siteUrl("https", "www.vk.com", "", ""))
    }

    @Test fun `чужие хосты и схемы не принимаются`() {
        assertNull(VkLinks.sitePath("https", "evil.com", "/id1", null))
        assertNull(VkLinks.sitePath("https", "vk.com.evil.com", "/id1", null))
        assertNull(VkLinks.sitePath("javascript", "vk.com", "/id1", null))
        assertNull(VkLinks.sitePath("intent", "vk.com", "/id1", null))
        assertNull(VkLinks.sitePath(null, null, null, null))
    }

    @Test fun `путь не уводит на другой хост`() {
        assertNull(VkLinks.sitePath("https", "vk.com", "//evil.com/x", null))
        assertNull(VkLinks.sitePath("https", "vk.com", "/\\evil.com", null))
    }
}
