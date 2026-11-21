package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Test

/** Подпись «печатает…» (4.60). */
class TypingTextTest {

    @Test fun `личная — просто печатает`() {
        assertEquals(TypingText.Label(TypingText.Kind.PRIVATE, emptyList(), false), TypingText.of(isChat = false, names = listOf("Анна К"), voice = false))
        assertEquals(true, TypingText.of(isChat = false, names = emptyList(), voice = true).voice)
    }

    @Test fun `беседа — по именам, коротко`() {
        assertEquals(TypingText.Label(TypingText.Kind.ONE, listOf("Анна"), false), TypingText.of(true, listOf("Анна Каренина"), false))
        assertEquals(TypingText.Label(TypingText.Kind.TWO, listOf("Анна", "Борис"), false), TypingText.of(true, listOf("Анна К", "Борис Л"), false))
        assertEquals(TypingText.Kind.SOMEONE, TypingText.of(true, listOf("А", "Б", "В"), false).kind)
    }

    @Test fun `беседа — неизвестно кто`() {
        assertEquals(TypingText.Label(TypingText.Kind.SOMEONE, emptyList(), true), TypingText.of(true, listOf("", " "), true))
        assertEquals(TypingText.Kind.SOMEONE, TypingText.of(true, emptyList(), false).kind)
    }
}
