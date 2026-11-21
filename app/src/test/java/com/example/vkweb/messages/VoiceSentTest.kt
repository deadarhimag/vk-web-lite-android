package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Test

/** Итог отправки голосового от страницы (4.59). */
class VoiceSentTest {
    @Test fun `итог голосового читается`() {
        assertEquals(ReaderPayload.VoiceSent(true, ""), ReaderPayload.parse("""{"type":"voiceSent","ok":true,"reason":""}"""))
        assertEquals(ReaderPayload.VoiceSent(false, "нет адреса загрузки"), ReaderPayload.parse("""{"type":"voiceSent","ok":false,"reason":"нет адреса загрузки"}"""))
    }
}
