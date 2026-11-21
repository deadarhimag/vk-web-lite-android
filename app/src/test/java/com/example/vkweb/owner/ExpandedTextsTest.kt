package com.example.vkweb.owner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandedTextsTest {

    @Test fun `развёрнутое помнится, пока страница открыта, и сбрасывается`() {
        val texts = ExpandedTexts()
        assertFalse(texts.isExpanded("post1"))
        texts.expand("post1")
        texts.expand("post1")
        assertTrue(texts.isExpanded("post1"))
        assertFalse(texts.isExpanded("post2"))
        texts.clear()
        assertFalse(texts.isExpanded("post1"))
    }

    @Test fun `ключ репоста отличается от ключа самой записи и описания`() {
        assertEquals("wall-1_2#repost", ExpandedTexts.repostKey("wall-1_2"))
        assertFalse(ExpandedTexts.repostKey("wall-1_2") == "wall-1_2")
        assertFalse(ExpandedTexts.DESCRIPTION == ExpandedTexts.repostKey("x"))
    }
}
