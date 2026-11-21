package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReaderCallTest {

    @Test fun `вызов с проверкой, что функция есть`() {
        assertEquals(
            "(window.__vkReader&&typeof window.__vkReader.go==='function')?window.__vkReader.go(\"/mail\"):false",
            ReaderCall.script("go", "\"/mail\""),
        )
        assertEquals(
            "(window.__vkReader&&typeof window.__vkReader.markRead==='function')?window.__vkReader.markRead():false",
            ReaderCall.script("markRead"),
        )
        assertEquals(
            "(window.__vkReader&&typeof window.__vkReader.forward==='function')?window.__vkReader.forward(\"5\",[7]):false",
            ReaderCall.script("forward", "\"5\"", "[7]"),
        )
    }

    @Test fun `имя функции — только буквы и цифры`() {
        listOf("", "go()", "a.b", "x;alert(1)", "1go", "__proto__").forEach { name ->
            assertThrows(name, IllegalArgumentException::class.java) { ReaderCall.script(name) }
        }
    }
}
