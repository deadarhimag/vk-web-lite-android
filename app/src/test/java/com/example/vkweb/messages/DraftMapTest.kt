package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftMapTest {

    @Test fun `пустой текст убирает черновик`() {
        val m = DraftMap.put(emptyMap(), "1", "привет")
        assertEquals(mapOf("1" to "привет"), m)
        assertEquals(emptyMap<String, String>(), DraftMap.put(m, "1", "   "))
    }

    @Test fun `не больше предела, уходят самые старые`() {
        var m: Map<String, String> = emptyMap()
        (1..DraftMap.MAX_DRAFTS + 2).forEach { m = DraftMap.put(m, it.toString(), "т$it") }
        assertEquals(DraftMap.MAX_DRAFTS, m.size)
        assertTrue("1" !in m && "2" !in m && "3" in m)
        // Изменённый черновик становится самым свежим и не уходит следующим.
        m = DraftMap.put(m, "3", "новое")
        m = DraftMap.put(m, "x", "ещё")
        assertTrue("3" in m && "4" !in m)
    }

    @Test fun `сохранение и чтение`() {
        val m = DraftMap.put(DraftMap.put(emptyMap(), "1", "а\nб"), "-5", "в")
        assertEquals(m, DraftMap.decode(DraftMap.encode(m)))
        assertEquals(emptyMap<String, String>(), DraftMap.decode("не json"))
        assertEquals("а б", DraftMap.preview(" а \n б "))
    }
}
