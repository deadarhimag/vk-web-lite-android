package com.example.vkweb.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfProfileTest {

    @Test fun `проверка данных страницы`() {
        assertEquals(SelfProfile(1, "Иван Петров", "https://x/a.jpg"), SelfProfile.from(1, "  Иван \n Петров ", "https://x/a.jpg"))
        assertEquals("только https", "", SelfProfile.from(1, "Иван", "http://x/a.jpg")!!.photo)
        assertNull(SelfProfile.from(0, "Иван", ""))
        assertNull(SelfProfile.from(5, "   ", ""))
    }

    @Test fun `в JSON и обратно`() {
        val p = SelfProfile(42, "Аня", "https://x/a.jpg")
        assertEquals(p, SelfProfile.fromJson(p.toJson()))
        assertEquals("/id42", p.path)
    }
}
