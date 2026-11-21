package com.example.vkweb.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesPanesTest {

    @Test fun `телефон — одна колонка`() {
        assertFalse(MessagesPanes.shouldSplit(true, 411, 0, 340))
        assertFalse("телефон боком", MessagesPanes.shouldSplit(true, 800, 0, 340))
    }

    @Test fun `планшет — две колонки, если справа хватает места`() {
        assertTrue(MessagesPanes.shouldSplit(true, 900, 0, 340))
        assertFalse("меню закреплено и съедает место", MessagesPanes.shouldSplit(true, 1000, 288, 340))
        assertTrue(MessagesPanes.shouldSplit(true, 1280, 288, 340))
    }

    @Test fun `выключено в настройках`() {
        assertFalse(MessagesPanes.shouldSplit(false, 1280, 0, 340))
    }
}
