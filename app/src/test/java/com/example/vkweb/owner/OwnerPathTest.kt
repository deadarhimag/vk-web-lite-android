package com.example.vkweb.owner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerPathTest {

    @Test fun `id по адресу`() {
        assertEquals(40201973L, OwnerPath.ownerIdOf("/id40201973"))
        assertEquals(-5826399L, OwnerPath.ownerIdOf("/club5826399"))
        assertEquals(-1L, OwnerPath.ownerIdOf("/public1/"))
        assertEquals(-7L, OwnerPath.ownerIdOf("/event7"))
        assertEquals(null, OwnerPath.ownerIdOf("/id0"))
        assertEquals(null, OwnerPath.ownerIdOf("/durov"))
        assertEquals(null, OwnerPath.ownerIdOf("/id1/friends"))
    }

    @Test fun `короткие имена — кандидаты`() {
        assertTrue(OwnerPath.isCandidate("/skels777"))
        assertTrue(OwnerPath.isCandidate("/animenation1"))
        assertTrue(OwnerPath.isCandidate("/durov"))
        assertTrue(OwnerPath.isCandidate("/id1"))
        assertTrue(OwnerPath.isCandidate("/unbfacts"))
    }

    @Test fun `разделы и служебные страницы — не кандидаты`() {
        listOf(
            "/", "/feed", "/friends", "/groups", "/mail", "/audio", "/video", "/clips", "/settings",
            "/wall40201973_698", "/photo40201973_311975856", "/albums40201973", "/videos-5826399",
            "/video-5826399_1", "/audios1", "/topic-1_2", "/page-1_2", "/doc1_2", "/app7944688_-5826399",
            "/away.php", "/al_profile.php", "/search", "/menu", "/bookmarks", "/services", "/market",
            "/id1/friends", "/a", "/friends/requests",
        ).forEach { assertFalse(it, OwnerPath.isCandidate(it)) }
    }

    @Test fun `ключ страницы`() {
        assertEquals("/durov", OwnerPath.keyOf("/durov/"))
        assertTrue(OwnerPath.sameKey("/Durov", "/durov/"))
        assertFalse(OwnerPath.sameKey("/durov", null))
    }

    @Test fun `лента новостей — свой экран без владельца`() {
        org.junit.Assert.assertTrue(OwnerPath.isFeed("/feed"))
        org.junit.Assert.assertTrue(OwnerPath.isFeed("/feed/"))
        org.junit.Assert.assertFalse(OwnerPath.isFeed("/feeds"))
        org.junit.Assert.assertFalse(OwnerPath.isCandidate("/feed"))
        val payload = OwnerPayload.fromJson(org.json.JSONObject().put("key", "/feed").put("ownerId", OwnerPath.FEED_ID)
            .put("posts", org.json.JSONArray().put(org.json.JSONObject().put("key", "-5_10").put("url", "/wall-5_10").put("text", "x"))))!!
        org.junit.Assert.assertEquals(1, payload.posts.size)
        org.junit.Assert.assertNull(payload.header)
    }
}
