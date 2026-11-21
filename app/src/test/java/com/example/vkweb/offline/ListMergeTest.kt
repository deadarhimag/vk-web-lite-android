package com.example.vkweb.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class ListMergeTest {

    private data class Row(val id: Int, val text: String = "")

    private fun ids(list: List<Row>) = list.map { it.id }

    @Test fun `stable - строки на местах, новые снизу`() {
        val shown = listOf(Row(1), Row(2), Row(3))
        val fresh = listOf(Row(4), Row(2, "обновлено"), Row(1))   // настоящий порядок другой
        val merged = ListMerge.stable(shown, fresh) { it.id }
        assertEquals(listOf(1, 2, 3, 4), ids(merged))
        assertEquals("обновлено", merged[1].text)
    }

    @Test fun `stable - частичная свежая порция ничего не убирает`() {
        val shown = (1..50).map { Row(it) }
        val merged = ListMerge.stable(shown, listOf(Row(1, "x"))) { it.id }
        assertEquals(50, merged.size)
        assertEquals("x", merged[0].text)
    }

    @Test fun `stable - пустой экран получает свежий порядок`() {
        assertEquals(listOf(3, 1), ids(ListMerge.stable(emptyList(), listOf(Row(3), Row(1))) { it.id }))
    }

    @Test fun `stable - повторы в данных не дублируются`() {
        val merged = ListMerge.stable(listOf(Row(1), Row(1)), listOf(Row(2), Row(2))) { it.id }
        assertEquals(listOf(1, 2), ids(merged))
    }

    @Test fun `appendMissing - позднее сохранённое не заменяет свежее`() {
        val shown = listOf(Row(1, "свежее"))
        val merged = ListMerge.appendMissing(shown, listOf(Row(1, "старое"), Row(2))) { it.id }
        assertEquals(listOf(1, 2), ids(merged))
        assertEquals("свежее", merged[0].text)
    }

    @Test fun `freshFirst - настоящий порядок, затем то, до чего страница не дошла`() {
        val known = listOf(Row(1), Row(2), Row(3), Row(4))
        val merged = ListMerge.freshFirst(known, listOf(Row(3), Row(1))) { it.id }
        assertEquals(listOf(3, 1, 2, 4), ids(merged))
    }

    @Test fun `freshFirst - пустая свежая порция не стирает известное`() {
        val known = listOf(Row(1))
        assertEquals(known, ListMerge.freshFirst(known, emptyList()) { it.id })
    }

    @Test fun `refresh - свежий порядок сверху, сохранённый хвост остаётся`() {
        val saved = listOf(Row(1), Row(2), Row(3), Row(4), Row(5))
        val merged = ListMerge.refresh(saved, listOf(Row(9, "новый"), Row(1, "свежее")), complete = false) { it.id }
        assertEquals(listOf(9, 1, 2, 3, 4, 5), ids(merged))
        assertEquals("свежее", merged[1].text)
    }

    @Test fun `refresh - весь список пришёл, удалённое уходит`() {
        val saved = listOf(Row(1), Row(2), Row(3))
        assertEquals(listOf(3, 1), ids(ListMerge.refresh(saved, listOf(Row(3), Row(1)), complete = true) { it.id }))
    }

    @Test fun `refresh - пустая порция ничего не убирает`() {
        val saved = listOf(Row(1), Row(2))
        assertEquals(saved, ListMerge.refresh(saved, emptyList(), complete = true) { it.id })
    }
}
