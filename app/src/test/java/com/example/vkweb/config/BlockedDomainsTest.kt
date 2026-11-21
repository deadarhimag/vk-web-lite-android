package com.example.vkweb.config

import com.example.vkweb.web.UrlPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedDomainsTest {

    @Test fun `рекламная сеть mradx — все хосты, а не один`() {
        val policy = UrlPolicy()
        for (host in listOf("r.mradx.net", "r0.mradx.net", "r1.mradx.net", "mradx.net")) {
            assertTrue(host, policy.isBlockedResource(host, "/"))
        }
        assertFalse(policy.isBlockedResource("st.vk.ru", "/"))
    }
}
