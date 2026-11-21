package com.example.vkweb.web

import org.junit.Assert.assertEquals
import org.junit.Test

class PayloadTypeTest {

    @Test fun `тип из начала сообщения без разбора`() {
        assertEquals("owner", PayloadType.of("""{"type":"owner","key":"/club1","posts":[]}"""))
        assertEquals("dump", PayloadType.of("""{ "type" : "dump", "method":"users.get"}"""))
        assertEquals("state", PayloadType.of("""{"type":"state","playing":true}"""))
    }

    @Test fun `не в начале или не строкой — пусто, сообщение разберут целиком`() {
        assertEquals("", PayloadType.of("""{"key":"/x","type":"owner"}"""))
        assertEquals("", PayloadType.of("""{"type":5}"""))
        assertEquals("", PayloadType.of("не json"))
        assertEquals("", PayloadType.of(""))
    }
}
